package com.lucas.trippermeteor;

import android.os.Handler;
import android.os.Looper;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Navegação com rota própria: encaixa o GPS na rota, escolhe o que mostrar no pod, avisa radar e recalcula. Main thread. */
public class Navigator {

    public interface Out {
        void podFrame(byte[] f);                 // pacote a mostrar (vira keepalive)
        void say(String text);                   // voz
        void beep(int times);                    // bipe
        void status(Status s);                   // tela do app
        void log(String s, int kind);
        void finished();                         // navegação acabou
    }

    public static class Status {
        public boolean active, routing, maps;
        public int icon = -1, dist = -1, nextIcon = -1, exit = 0, limit = 0, speedKmh = -1;
        public long radarId = 0;      // radar da base na tela (0 = nenhum; para o "não é radar")
        public boolean radarOnly;     // modo só radar
        public String title = "", sub = "", bottom = "", radar = "", dest = "", error = "";
    }

    public static class Settings {
        public int bottom = Proto.BOTTOM_ARRIVAL;
        public boolean voiceTurns = false, voiceRadar = true, beepRadar = true, exitIcons = false, useRadarbot = true;
        public int radarDist = 500, radarIcon = 28;
        public int night = 0; // 0 automático (18h–6h), 1 sempre, 2 nunca
    }

    private final Out out;
    private final RadarStore radars;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService radarIo = Executors.newSingleThreadExecutor();
    public final Settings set;

    private Route route;
    private Router.Place dest;
    private boolean active = false, routing = false, rerouting = false;
    private int hint = -1, offCount = 0, reqId = 0;
    private long lastRouteAt = 0;
    private double curLat, curLon, curSpeed, curHeading = -1, curAcc = 99;
    private boolean haveFix = false;
    private final Set<String> spoken = new HashSet<>();
    private boolean arrived = false;
    private byte[] lastFrame;
    private double lastAlong = 0;

    // simulação
    private boolean sim = false;
    private double simAlong = 0, simKmh = 40;

    public Navigator(Out out, RadarStore radars, Settings set) {
        this.out = out;
        this.radars = radars;
        this.set = set;
    }

    public boolean isActive() { return active; }
    public boolean isSimulating() { return sim; }
    public Route route() { return route; }

    // ---------------- comandos ----------------

    public void start(Router.Place p) {
        dest = p;
        active = true;
        arrived = false;
        route = null;
        rerouting = false;
        spoken.clear();
        hint = -1;
        reqId++;
        routing = false;
        out.log("destino: " + p.name + String.format(Locale.US, " (%.5f, %.5f)", p.lat, p.lon), Pod.LOG_INFO);
        frame(Proto.idle(28, night()));
        if (haveFix) fetchRoute(); else publish("Esperando o GPS…");
    }

    public void stop() {
        if (!active) return;
        active = false;
        sim = false;
        route = null;
        routing = false;
        rerouting = false;
        reqId++;
        h.removeCallbacksAndMessages(null);
        frame(Proto.idle(28, night()));
        out.status(new Status());
        out.finished();
    }

    public void setSimulation(boolean on, double kmh) {
        simKmh = kmh;
        if (on == sim) return;
        sim = on;
        h.removeCallbacks(simTick);
        if (on) {
            if (route == null) { sim = false; out.log("calcule uma rota antes de simular", Pod.LOG_ERR); return; }
            simAlong = Math.max(0, lastAlong);
            out.log("simulação ligada a " + Math.round(kmh) + " km/h", Pod.LOG_INFO);
            h.post(simTick);
        } else out.log("simulação desligada (voltando ao GPS)", Pod.LOG_INFO);
    }

    private final Runnable simTick = new Runnable() {
        @Override public void run() {
            if (!sim || route == null) return;
            simAlong += simKmh / 3.6;
            double[] p = route.pointAt(simAlong);
            update(p[0], p[1], simKmh / 3.6, p[2], 5, true);
            if (simAlong < route.length + 30) h.postDelayed(this, 1000);
            else sim = false;
        }
    };

    // ---------------- GPS ----------------

    public void onLocation(double lat, double lon, double speed, double heading, double acc) {
        if (sim) return; // durante a simulação ignora o GPS de verdade
        update(lat, lon, speed, heading, acc, false);
    }

    private void update(double lat, double lon, double speed, double heading, double acc, boolean simulated) {
        curLat = lat; curLon = lon; curSpeed = speed; curAcc = acc;
        if (speed > 1.5 && heading >= 0) curHeading = heading;
        boolean first = !haveFix;
        haveFix = true;
        if (!active) return;
        if (route == null) {
            if (!routing && (first || System.currentTimeMillis() - lastRouteAt > 10000)) fetchRoute();
            return;
        }
        if (rerouting) return; // tela fica parada em "recalculando" até a rota nova chegar

        Route.Snap sn = route.snap(lat, lon, hint);
        if (sn.off > 60 && hint >= 0) sn = route.snap(lat, lon, -1); // perdeu a posição: procura na rota toda
        hint = sn.idx;
        double along = sn.along;
        lastAlong = along;

        // fora da rota? precisa de 5 leituras seguidas, GPS bom e 15 s desde a última rota
        if (!simulated && sn.off > 50 && acc < 25) offCount++; else offCount = 0;
        if (offCount >= 5 && !routing && System.currentTimeMillis() - lastRouteAt > 15000) {
            offCount = 0;
            out.log(String.format(Locale.US, "fora da rota (%.0f m), recalculando…", sn.off), Pod.LOG_INFO);
            if (set.voiceTurns) out.say("Recalculando");
            rerouting = true;
            frame(Proto.idle(28, night()));
            publish("Recalculando…");
            fetchRoute();
            return;
        }

        double remaining = Math.max(0, route.length - along);
        int etaMin = (int) Math.round(route.duration * (remaining / Math.max(1, route.length)) / 60.0);

        // chegou?
        if (remaining < 25 || arrived) {
            if (!arrived) {
                arrived = true;
                out.say("Você chegou ao destino");
                out.log("chegou ao destino", Pod.LOG_INFO);
                h.postDelayed(this::stop, 20000);
            }
            frame(Proto.nav(0, 0, 4, -1, 1, Proto.BOTTOM_NONE, 0, 0, night()));
            Status s = baseStatus();
            s.icon = 0; s.dist = 0; s.title = "Você chegou"; s.sub = dest.name;
            out.status(s);
            return;
        }

        // próxima manobra
        Route.Step target = null, following = null;
        for (int i = 0; i < route.steps.size(); i++) {
            Route.Step st = route.steps.get(i);
            if (st.along > along + 3) {
                target = st;
                if (i + 1 < route.steps.size()) following = route.steps.get(i + 1);
                break;
            }
        }
        if (target == null) target = route.steps.get(route.steps.size() - 1);
        int dist = (int) Math.max(0, Math.round(target.along - along));
        int nextIcon = -1;
        if (following != null && following.along - target.along < 250 && dist < 300) nextIcon = following.icon;

        // voz das curvas
        if (set.voiceTurns) {
            String key = target.lat + "," + target.lon;
            int far = speed > 20 ? 800 : 400;
            if (dist <= far && dist > 150 && spoken.add(key + "f")) out.say("Em " + niceDist(dist) + ", " + target.speech);
            if (dist <= 60 && spoken.add(key + "n")) out.say(target.speech);
        }

        // radar: alerta do Radarbot primeiro; se não tiver, o primeiro da rota que não ficou 30 m pra trás
        RadarAlert.Pick pk = RadarAlert.external(radars, set, speed);
        if (pk == null) {
            for (Route.Radar r : route.radars) {
                if (r.along > along - 30) {
                    pk = new RadarAlert.Pick();
                    pk.r = r.r;
                    pk.dist = (int) Math.round(r.along - along);
                    break;
                }
            }
        }
        int rdDist = pk == null ? 9999 : (pk.dist < 0 ? 0 : pk.dist);
        String radarTxt = "";
        boolean showRadar = pk != null && RadarAlert.radarWins(rdDist, dist, set.radarDist);
        if (pk != null && rdDist <= set.radarDist && rdDist >= -30) radarTxt = RadarAlert.handle(pk.r, pk.dist, speed, set, out, pk.kind);

        byte[] f;
        if (showRadar) {
            boolean lim = pk.r.maxspeed > 0;
            f = Proto.nav(set.radarIcon, Math.max(0, rdDist), -1, -1, 2,
                    lim ? Proto.BOTTOM_TOTAL : set.bottom, etaMin, lim ? pk.r.maxspeed : (int) remaining, night());
        } else {
            f = Proto.nav(target.icon, dist, -1, nextIcon, 1, set.bottom, etaMin, (int) remaining, night());
        }
        frame(f);

        Status s = baseStatus();
        s.icon = target.icon;
        s.dist = dist;
        s.exit = target.exit;
        s.nextIcon = nextIcon;
        s.title = Proto.iconName(target.icon) + (target.exit > 0 ? " · " + target.exit + "ª saída" : "");
        s.sub = target.name == null ? "" : target.name;
        s.bottom = bottomText(etaMin, (int) remaining);
        s.radar = radarTxt;
        s.limit = pk != null && !radarTxt.isEmpty() ? pk.r.maxspeed : 0;
        s.radarId = pk != null && !pk.external && !radarTxt.isEmpty() ? pk.r.id : 0;
        s.speedKmh = (int) Math.round(speed * 3.6);
        out.status(s);
    }

    static String bottomText(int etaMin, int remaining) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MINUTE, etaMin);
        return String.format(Locale.US, "Chegada %02d:%02d  ·  %s  ·  %d min", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
                niceDist(remaining).replace(" metros", " m"), etaMin);
    }

    private Status baseStatus() {
        Status s = new Status();
        s.active = active;
        s.routing = routing;
        s.dest = dest == null ? "" : dest.name;
        return s;
    }

    private void publish(String msg) {
        Status s = baseStatus();
        s.title = msg;
        s.icon = 28;
        out.status(s);
    }

    // ---------------- rota ----------------

    /** Um pedido por vez, sempre da posição atual. */
    private void fetchRoute() {
        if (dest == null || !haveFix || routing) return;
        routing = true;
        lastRouteAt = System.currentTimeMillis();
        if (route == null) publish("Calculando rota…");
        final int id = ++reqId;
        final double fl = curLat, fo = curLon, hd = curSpeed > 2 ? curHeading : -1;
        final Router.Place d = dest;
        final boolean exitIcons = set.exitIcons;
        io.execute(() -> {
            try {
                Route r = Router.route(fl, fo, d.lat, d.lon, hd, exitIcons);
                r.destName = d.name;
                r.attachRadars(radars);
                h.post(() -> onRoute(r, id));
            } catch (Exception e) {
                h.post(() -> {
                    if (id != reqId) return;
                    routing = false;
                    lastRouteAt = System.currentTimeMillis();
                    String err = e.getMessage() == null ? e.toString() : e.getMessage();
                    out.log("rota falhou: " + err, Pod.LOG_ERR);
                    if (route == null || rerouting) {
                        Status s = baseStatus();
                        s.title = "Sem rota (tentando de novo)";
                        s.error = err;
                        s.icon = 28;
                        out.status(s);
                    }
                    rerouting = false; // se tinha rota, volta a usar a antiga
                });
            }
        });
    }

    private void onRoute(Route r, int id) {
        if (id != reqId || !active) return; // resposta velha: descarta
        routing = false;
        rerouting = false;
        route = r;
        hint = -1;
        offCount = 0;
        lastRouteAt = System.currentTimeMillis();
        if (sim) simAlong = 0;
        out.log(String.format(Locale.US, "rota: %s, %d min, %d manobras, %d radares",
                niceDist((int) r.length), Math.round(r.duration / 60), r.steps.size(), r.radars.size()), Pod.LOG_INFO);
        // radares: se a área da rota ainda não foi baixada, baixa sem segurar a navegação
        final double[] bb = r.bbox();
        if (!radars.covers(bb[0], bb[1], bb[2], bb[3])) {
            radarIo.execute(() -> {
                try {
                    radars.fetch(bb[0], bb[1], bb[2], bb[3]);
                    h.post(() -> {
                        if (route != r) return;
                        r.attachRadars(radars);
                        out.log("radares na rota: " + r.radars.size(), Pod.LOG_INFO);
                    });
                } catch (Exception e) {
                    h.post(() -> out.log("radares: servidor não respondeu (" + e.getMessage() + "), usando os já salvos", Pod.LOG_ERR));
                }
            });
        }
        update(curLat, curLon, curSpeed, curHeading, curAcc, true);
    }

    // ---------------- util ----------------

    private void frame(byte[] f) {
        lastFrame = f;
        out.podFrame(f);
    }

    public byte[] lastFrame() { return lastFrame; }

    public boolean night() { return night(set); }

    public static boolean night(Settings set) {
        if (set.night == 1) return true;
        if (set.night == 2) return false;
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hr >= 18 || hr < 6;
    }

    public static String niceDist(int m) {
        if (m >= 1000) {
            double km = m / 1000.0;
            return km >= 10 ? Math.round(km) + " km" : String.format(Locale.US, "%.1f km", km).replace('.', ',');
        }
        if (m >= 100) return ((m + 25) / 50 * 50) + " metros";
        return ((m + 5) / 10 * 10) + " metros";
    }

    public double[] lastFix() { return haveFix ? new double[]{curLat, curLon, curHeading} : null; }

    public void shutdown() {
        h.removeCallbacksAndMessages(null);
        io.shutdownNow();
        radarIo.shutdownNow();
    }
}
