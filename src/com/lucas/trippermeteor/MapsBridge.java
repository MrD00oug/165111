package com.lucas.trippermeteor;

import android.os.Handler;
import android.os.Looper;

import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Modo Google Maps: a rota é do Maps (modo moto, trânsito, regras de SP);
 * o Tripper lê a notificação de navegação, mostra no pod e soma o aviso de radar.
 */
public class MapsBridge {

    private final Navigator.Out out;
    private final RadarStore radars;
    private final Navigator.Settings set;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private boolean enabled = false;
    /** Só radar: sem Maps, o pod fica na tela de espera e mostra os radares. */
    public boolean radarOnly = false;
    private MapsParser.Instr instr;
    private long instrAt = 0;
    private double lat, lon, speed, heading = -1;
    private boolean haveFix = false;
    private double fetchLat = 999, fetchLon = 999;
    private boolean fetching = false;
    private long lastFetchFail = 0;

    public MapsBridge(Navigator.Out out, RadarStore radars, Navigator.Settings set) {
        this.out = out;
        this.radars = radars;
        this.set = set;
    }

    public boolean isEnabled() { return enabled; }
    public boolean isMirroring() { return enabled && instr != null && System.currentTimeMillis() - instrAt < 60000; }

    public void setEnabled(boolean on) {
        enabled = on;
        instr = null;
        h.removeCallbacks(tick);
        if (!on) radarOnly = false;
        if (on) {
            out.log(radarOnly ? "modo radar ligado" : "modo Google Maps ligado: inicie a navegação no Maps", Pod.LOG_INFO);
            h.postDelayed(tick, 1000);
            out.podFrame(Proto.idle(28, Navigator.night(set)));
        } else {
            out.podFrame(Proto.idle(28, Navigator.night(set)));
        }
        publish();
    }

    /** Chegou/atualizou a notificação de navegação do Maps. */
    public void onMaps(MapsParser.Instr i) {
        if (!enabled) return;
        boolean firstOne = instr == null;
        instr = i;
        instrAt = System.currentTimeMillis();
        if (firstOne) out.log("espelhando o Google Maps", Pod.LOG_INFO);
        render();
    }

    /** A navegação no Maps acabou. */
    public void onMapsEnded() {
        if (!enabled || instr == null) return;
        instr = null;
        out.log("navegação do Maps terminou", Pod.LOG_INFO);
        out.podFrame(Proto.idle(28, Navigator.night(set)));
        publish();
    }

    public void onLocation(double la, double lo, double sp, double hd) {
        lat = la; lon = lo; speed = sp;
        if (sp > 1.5 && hd >= 0) heading = hd;
        haveFix = true;
        if (!enabled) return;
        // radares num raio de 15 km; baixa de novo quando andar 8 km
        if (!fetching && Geo.dist(la, lo, fetchLat, fetchLon) > 8000 && System.currentTimeMillis() - lastFetchFail > 60000) {
            fetching = true;
            final double fl = la, fo = lo;
            io.execute(() -> {
                try {
                    radars.ensureAround(fl, fo, 15);
                    h.post(() -> { fetching = false; fetchLat = fl; fetchLon = fo; out.log("radares em volta: " + radars.size() + " na base", Pod.LOG_INFO); });
                } catch (Exception e) {
                    h.post(() -> { fetching = false; lastFetchFail = System.currentTimeMillis(); out.log("radares: servidor não respondeu, usando os salvos", Pod.LOG_ERR); });
                }
            });
        }
        render();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!enabled) return;
            render();
            h.postDelayed(this, 1000);
        }
    };

    private void render() {
        if (!enabled) return;
        boolean night = Navigator.night(set);
        MapsParser.Instr i = instr;
        long now = System.currentTimeMillis();

        // distância estimada entre uma atualização do Maps e outra (usa a velocidade do GPS)
        int dist = -1;
        if (i != null && i.distM >= 0) {
            double el = (now - instrAt) / 1000.0;
            double est = i.distM - (speed > 1 ? speed * Math.min(el, 8) : 0);
            dist = (int) Math.max(0, Math.round(est));
        }
        int etaMin = -1;
        if (i != null && i.etaH >= 0) {
            Calendar c = Calendar.getInstance();
            int nowMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            etaMin = ((i.etaH * 60 + i.etaM) - nowMin + 1440) % 1440;
        } else if (i != null && i.remainMin >= 0) etaMin = i.remainMin;
        int remain = i == null ? -1 : i.remainM;

        // radar: primeiro o alerta do Radarbot; se não tiver, a base (pela posição e rumo)
        RadarAlert.Pick pk = RadarAlert.external(radars, set, speed);
        if (pk == null && haveFix) {
            Object[] a = radars.ahead(lat, lon, heading, set.radarDist);
            if (a != null) {
                pk = new RadarAlert.Pick();
                pk.r = (RadarStore.Radar) a[0];
                pk.dist = (int) Math.round((Double) a[1]);
            }
        }
        RadarStore.Radar rd = pk == null ? null : pk.r;
        int rdDist = pk == null ? 9999 : (pk.dist < 0 ? 0 : pk.dist);
        String radarTxt = pk != null ? RadarAlert.handle(pk.r, pk.dist, speed, set, out, pk.kind) : "";
        boolean showRadar = pk != null && RadarAlert.radarWins(rdDist, i == null ? -1 : dist, set.radarDist);

        int bottom = set.bottom;
        if (bottom == Proto.BOTTOM_ARRIVAL && etaMin < 0) bottom = remain >= 0 ? Proto.BOTTOM_TOTAL : Proto.BOTTOM_NONE;
        if (bottom == Proto.BOTTOM_TOTAL && remain < 0) bottom = Proto.BOTTOM_NONE;

        byte[] f;
        if (showRadar) {
            boolean lim = rd.maxspeed > 0;
            f = Proto.nav(set.radarIcon, Math.max(0, rdDist), -1, -1, 2, lim ? Proto.BOTTOM_TOTAL : bottom,
                    Math.max(0, etaMin), lim ? rd.maxspeed : Math.max(0, remain), night);
        } else if (i != null && now - instrAt < 60000) {
            int icon = i.icon >= 0 ? i.icon : 9;
            if (icon == 49 && set.exitIcons && i.exit >= 1 && i.exit <= 9) icon = 49 + i.exit;
            f = Proto.nav(icon, Math.max(0, dist), -1, -1, 1, bottom, Math.max(0, etaMin), Math.max(0, remain), night);
        } else {
            f = Proto.idle(28, night);
        }
        out.podFrame(f);

        Navigator.Status s = new Navigator.Status();
        s.active = true;
        s.maps = true;
        if (i != null && now - instrAt < 60000) {
            s.icon = i.icon >= 0 ? i.icon : 9;
            s.dist = dist;
            s.exit = i.exit;
            s.title = i.text.isEmpty() ? Proto.iconName(s.icon) : i.text;
            s.sub = i.street;
            if (etaMin >= 0 || remain >= 0) {
                s.bottom = (i.etaH >= 0 ? String.format(Locale.US, "Chegada %02d:%02d", i.etaH, i.etaM) : "")
                        + (remain >= 0 ? "  ·  " + Navigator.niceDist(remain).replace(" metros", " m") : "")
                        + (i.remainMin >= 0 ? "  ·  " + i.remainMin + " min" : "");
            }
        } else if (radarOnly) {
            s.icon = -1;
            s.radarOnly = true;
            s.title = "Modo radar";
            s.sub = rd == null ? "Nenhum radar por perto" : "";
        } else {
            s.icon = 28;
            s.title = "Esperando a navegação do Google Maps";
            s.sub = "Abra o Maps, escolha o destino e toque em Iniciar";
        }
        s.radar = radarTxt;
        s.limit = rd != null ? rd.maxspeed : 0;
        s.radarId = pk != null && !pk.external ? pk.r.id : 0;
        s.speedKmh = haveFix ? (int) Math.round(speed * 3.6) : -1;
        out.status(s);
    }

    private void publish() {
        if (!enabled) { out.status(new Navigator.Status()); return; }
        render();
    }

    public void shutdown() {
        h.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }
}
