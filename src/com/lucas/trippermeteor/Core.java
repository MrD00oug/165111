package com.lucas.trippermeteor;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Liga tudo: pod, navegação, voz, ajustes e log. Um só por processo (a tela e o serviço usam o mesmo). */
public class Core implements Pod.Listener, Navigator.Out {

    public interface Ui {
        void onLog(String time, String line, int kind);
        void onPodState(int state);
        void onNav(Navigator.Status s);
        void onPinResult(boolean ok);
    }

    private static Core instance;

    public static synchronized Core get(Context c) {
        if (instance == null) instance = new Core(c.getApplicationContext());
        return instance;
    }

    public final Context ctx;
    public final Pod pod;
    public final Navigator nav;
    public final MapsBridge bridge;
    public final RadarStore radars;
    public final Navigator.Settings set = new Navigator.Settings();
    public final List<String> captures = new ArrayList<>();          // todas, mais nova primeiro
    private final java.util.Map<String, String> lastCap = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> capCount = new java.util.HashMap<>();
    private long podLostAt = 0;
    public final SharedPreferences prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Ui ui;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private ToneGenerator tone;
    public int podState = 0;
    public Navigator.Status lastStatus = new Navigator.Status();
    public final List<String[]> logs = new ArrayList<>();
    private byte[] lastSent, current;

    private Core(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("ajustes", Context.MODE_PRIVATE);
        pod = new Pod(c, this);
        radars = new RadarStore(c);
        loadSettings();
        nav = new Navigator(this, radars, set);
        bridge = new MapsBridge(this, radars, set);
    }

    public void setUi(Ui u) { ui = u; }

    /** Algum modo de navegação ativo (rota própria ou Google Maps)? */
    public boolean navigating() { return nav.isActive() || bridge.isEnabled(); }

    /** Posição do GPS (vem do NavService). */
    public double fixLat, fixLon, fixHeading = -1, fixSpeed;
    public long fixAt = 0;

    public void onLocation(double lat, double lon, double speed, double heading, double acc) {
        // pod sumido há 10 min (moto desligada): encerra a navegação e para de procurar
        if (navigating() && podLostAt > 0 && System.currentTimeMillis() - podLostAt > 10 * 60 * 1000) {
            podLostAt = 0;
            onLog("pod sem conexão há 10 min: navegação encerrada", Pod.LOG_INFO);
            bridge.setEnabled(false);
            if (nav.isActive()) nav.stop();
            pod.persistent = false;
            finished();
            return;
        }
        fixLat = lat; fixLon = lon; fixSpeed = speed; fixAt = System.currentTimeMillis();
        if (speed > 1.5 && heading >= 0) fixHeading = heading;
        nav.onLocation(lat, lon, speed, heading, acc);
        bridge.onLocation(lat, lon, speed, heading);
    }

    /** Marca um radar onde você está agora (no sentido em que está andando). */
    public RadarStore.Radar markRadar() {
        if (System.currentTimeMillis() - fixAt > 15000) return null;
        RadarStore.Radar r = radars.addUser(fixLat, fixLon, fixHeading >= 0 ? fixHeading : Double.NaN, 0);
        onLog(String.format(Locale.US, "radar marcado em %.5f, %.5f%s", fixLat, fixLon,
                fixHeading >= 0 ? String.format(Locale.US, " (sentido %.0f°)", fixHeading) : ""), Pod.LOG_INFO);
        return r;
    }

    /** "Não é radar". */
    public void ignoreRadar(long id) {
        radars.ignore(id);
        if (nav.route() != null) nav.route().attachRadars(radars);
        onLog("radar removido da base (" + id + ")", Pod.LOG_INFO);
    }

    public void startRadarOnly() {
        if (nav.isActive()) nav.stop();
        pod.persistent = true;
        NavService.start(ctx);
        bridge.radarOnly = true;
        bridge.setEnabled(true);
    }

    public void startMapsMode() {
        bridge.radarOnly = false;
        if (nav.isActive()) nav.stop();
        pod.persistent = true;
        NavService.start(ctx);
        bridge.setEnabled(true);
    }

    public void stopMapsMode() {
        bridge.setEnabled(false);
        if (!nav.isActive()) finished();
    }

    /** Notificação crua do Maps (para a Bancada). */
    public void capture(String app, String line) {
        // ignora repetição idêntica (sem contar a hora)
        String body = line.length() > 9 ? line.substring(9) : line;
        if (body.equals(lastCap.get(app))) return;
        lastCap.put(app, body);
        captures.add(0, line);
        // o Maps atualiza toda hora: guarda menos dele pra não empurrar os do Radarbot/Waze pra fora
        int mapsMax = 25, otherMax = 80, maps = 0, other = 0;
        for (int i = 0; i < captures.size(); i++) {
            boolean isMaps = captures.get(i).startsWith("MAPS", 9);
            if (isMaps ? ++maps > mapsMax : ++other > otherMax) { captures.remove(i); i--; }
        }
        capCount.put(app, capCount.getOrDefault(app, 0) + 1);
    }

    public String captureSummary() {
        return "Maps: " + capCount.getOrDefault("MAPS", 0) + "  ·  Radarbot: " + capCount.getOrDefault("RADARBOT", 0)
                + "  ·  Waze: " + capCount.getOrDefault("WAZE", 0);
    }

    // ---------------- ajustes ----------------

    public void loadSettings() {
        Navigator.Settings s = set;
        s.bottom = prefs.getInt("bottom", Proto.BOTTOM_ARRIVAL);
        s.voiceTurns = prefs.getBoolean("voiceTurns", false);
        s.voiceRadar = prefs.getBoolean("voiceRadar", true);
        s.beepRadar = prefs.getBoolean("beepRadar", true);
        s.exitIcons = prefs.getBoolean("exitIcons", false);
        s.radarDist = prefs.getInt("radarDist", 500);
        s.radarIcon = prefs.getInt("radarIcon", 28);
        s.night = prefs.getInt("night", 0);
        s.useRadarbot = prefs.getBoolean("useRadarbot", true);
    }

    public void saveSettings() {
        Navigator.Settings s = set;
        prefs.edit().putInt("bottom", s.bottom).putBoolean("voiceTurns", s.voiceTurns).putBoolean("voiceRadar", s.voiceRadar)
                .putBoolean("beepRadar", s.beepRadar).putBoolean("exitIcons", s.exitIcons).putInt("radarDist", s.radarDist)
                .putInt("radarIcon", s.radarIcon).putInt("night", s.night).putBoolean("useRadarbot", s.useRadarbot).apply();
    }

    // ---------------- destinos recentes ----------------

    public List<Router.Place> recents() {
        List<Router.Place> out = new ArrayList<>();
        for (String line : prefs.getString("recents", "").split("\n")) {
            String[] p = line.split("\t");
            if (p.length >= 4) try {
                out.add(new Router.Place(p[0], p[1], Double.parseDouble(p[2]), Double.parseDouble(p[3])));
            } catch (Exception ignored) {}
        }
        return out;
    }

    public void addRecent(Router.Place p) {
        List<Router.Place> r = recents();
        StringBuilder sb = new StringBuilder();
        sb.append(clean(p.name)).append('\t').append(clean(p.detail)).append('\t').append(p.lat).append('\t').append(p.lon).append('\n');
        int n = 1;
        for (Router.Place x : r) {
            if (n >= 6) break;
            if (Geo.dist(x.lat, x.lon, p.lat, p.lon) < 30) continue;
            sb.append(clean(x.name)).append('\t').append(clean(x.detail)).append('\t').append(x.lat).append('\t').append(x.lon).append('\n');
            n++;
        }
        prefs.edit().putString("recents", sb.toString()).apply();
    }

    private static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' '); }

    // ---------------- Pod.Listener ----------------

    @Override
    public void onLog(String line, int kind) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        logs.add(0, new String[]{t, line, String.valueOf(kind)});
        while (logs.size() > 400) logs.remove(logs.size() - 1);
        if (ui != null) ui.onLog(t, line, kind);
    }

    @Override
    public void onState(int s) {
        int prev = podState;
        podState = s;
        if (s == 3) podLostAt = 0;
        else if (prev == 3 && podLostAt == 0) podLostAt = System.currentTimeMillis(); // só conta se estava conectado
        lastSent = null;
        if (s == 3 && current != null) pod.setKeepalive(current);
        if (ui != null) ui.onPodState(s);
    }

    @Override
    public void onPinResult(boolean ok) {
        if (ui != null) ui.onPinResult(ok);
    }

    // ---------------- Navigator.Out ----------------

    @Override
    public void podFrame(byte[] f) {
        current = f;
        pod.setKeepalive(f);
        if (!pod.isConnected()) return;
        if (lastSent != null && Arrays.equals(lastSent, f)) return; // igual: o keepalive já repete
        // só registra no log quando muda o ícone
        String label = (lastSent == null || lastSent[2] != f[2]) ? String.format(Locale.US, "nav ícone %d", f[2] & 0xFF) : null;
        pod.send(f, label);
        lastSent = f;
    }

    @Override
    public void say(String text) {
        if (tts == null) {
            tts = new TextToSpeech(ctx, st -> {
                ttsReady = st == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    tts.setLanguage(new Locale("pt", "BR"));
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                    tts.speak(text, TextToSpeech.QUEUE_ADD, new Bundle(), "t" + System.nanoTime());
                }
            });
            return;
        }
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_ADD, new Bundle(), "t" + System.nanoTime());
    }

    @Override
    public void beep(int times) {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
            for (int i = 0; i < times; i++) {
                h.postDelayed(() -> { try { tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 180); } catch (Exception ignored) {} }, i * 320L);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void status(Navigator.Status s) {
        lastStatus = s;
        if (navigating()) NavService.update(ctx, s);
        if (ui != null) ui.onNav(s);
    }

    @Override
    public void log(String s, int kind) { onLog(s, kind); }

    @Override
    public void finished() {
        if (bridge.isEnabled() || nav.isActive()) return;
        pod.persistent = false;
        NavService.stop(ctx);
    }
}
