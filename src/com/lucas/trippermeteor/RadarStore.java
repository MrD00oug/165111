package com.lucas.trippermeteor;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Radares do OpenStreetMap guardados por id. Baixa em blocos (área) e salva em arquivo,
 * então recálculo de rota ou servidor fora do ar não fazem os radares sumirem.
 */
public class RadarStore {

    public static class Radar {
        public long id;
        public double lat, lon;
        public int maxspeed;            // km/h (0 = desconhecido)
        public double dir = Double.NaN; // sentido em graus (NaN = desconhecido)
        public long warnedAt, beepedAt;
    }

    /** Área já baixada. */
    static class Area { double s, w, n, e; long at; }

    private final File file;
    private final Map<Long, Radar> radars = new HashMap<>();
    private final java.util.Set<Long> ignored = new java.util.HashSet<>();

    /** Alerta vindo de fora (Radarbot): distância e limite no momento em que chegou. */
    public static class External {
        public int dist = -1, limit = 0;
        public String kind = "Radar";
        public long at;
        public Radar radar = new Radar(); // para controlar voz/bipe uma vez por alerta
    }
    public volatile External external;
    private final List<Area> areas = new ArrayList<>();
    private static final long MAX_AGE = 7L * 24 * 3600 * 1000; // rebaixa a área depois de 7 dias

    public RadarStore(Context c) {
        file = new File(c.getFilesDir(), "radares.json");
        load();
    }

    public RadarStore(File f) { file = f; }

    public synchronized int size() { return radars.size(); }

    public synchronized List<Radar> all() { return new ArrayList<>(radars.values()); }

    public synchronized int userCount() {
        int n = 0;
        for (Radar r : radars.values()) if (r.id < 0) n++;
        return n;
    }

    /** Radar marcado por você (id negativo, nunca é apagado pela atualização do mapa). */
    public Radar addUser(double lat, double lon, double dir, int limit) {
        Radar r = new Radar();
        r.id = -System.currentTimeMillis();
        r.lat = lat; r.lon = lon; r.dir = dir; r.maxspeed = limit;
        r.warnedAt = System.currentTimeMillis(); // acabou de passar nele: não avisa agora
        synchronized (this) { radars.put(r.id, r); }
        save();
        return r;
    }

    public void setLimit(long id, int limit) {
        synchronized (this) { Radar r = radars.get(id); if (r != null) r.maxspeed = limit; }
        save();
    }

    /** "Não é radar": some da base e não volta na próxima atualização. */
    public void ignore(long id) {
        synchronized (this) { radars.remove(id); if (id > 0) ignored.add(id); }
        save();
    }

    /** Alerta externo ainda valendo (chegou há menos de "ms")? */
    public External externalFresh(long ms) {
        External e = external;
        return e != null && System.currentTimeMillis() - e.at < ms ? e : null;
    }

    /** Já tem essa área (inteira) baixada e recente? */
    public synchronized boolean covers(double s, double w, double n, double e) {
        long now = System.currentTimeMillis();
        for (Area a : areas) {
            if (now - a.at > MAX_AGE) continue;
            if (a.s <= s && a.w <= w && a.n >= n && a.e >= e) return true;
        }
        return false;
    }

    /** Baixa os radares da área (chamar fora da main thread). */
    public void fetch(double s, double w, double n, double e) throws Exception {
        String q = String.format(Locale.US,
                "[out:json][timeout:40];node[\"highway\"=\"speed_camera\"](%.5f,%.5f,%.5f,%.5f);out body;", s, w, n, e);
        JSONObject o;
        String form = "data=" + java.net.URLEncoder.encode(q, "UTF-8");
        try {
            o = new JSONObject(Router.post("https://overpass-api.de/api/interpreter", form));
        } catch (Exception ex) {
            o = new JSONObject(Router.post("https://overpass.kumi.systems/api/interpreter", form));
        }
        JSONArray els = o.getJSONArray("elements");
        synchronized (this) {
            for (int i = 0; i < els.length(); i++) {
                JSONObject el = els.getJSONObject(i);
                Radar r = new Radar();
                r.id = el.getLong("id");
                r.lat = el.getDouble("lat");
                r.lon = el.getDouble("lon");
                JSONObject t = el.optJSONObject("tags");
                if (t != null) {
                    r.maxspeed = Router.parseSpeed(t.optString("maxspeed", ""));
                    r.dir = parseDir(t.optString("direction", t.optString("camera:direction", "")));
                }
                if (ignored.contains(r.id)) continue;
                Radar old = radars.get(r.id);
                if (old != null) { r.warnedAt = old.warnedAt; r.beepedAt = old.beepedAt; }
                radars.put(r.id, r);
            }
            Area a = new Area();
            a.s = s; a.w = w; a.n = n; a.e = e; a.at = System.currentTimeMillis();
            areas.add(a);
            while (areas.size() > 40) areas.remove(0);
        }
        save();
    }

    /** Garante os radares num raio (km) em volta de um ponto. */
    public void ensureAround(double lat, double lon, double km) throws Exception {
        double dLat = km / 111.0, dLon = km / (111.0 * Math.cos(Math.toRadians(lat)));
        double s = lat - dLat, n = lat + dLat, w = lon - dLon, e = lon + dLon;
        // pede um pouco menor para "covers" e baixa a área maior
        double hl = dLat * 0.6, ho = dLon * 0.6;
        if (covers(lat - hl, lon - ho, lat + hl, lon + ho)) return;
        fetch(s, w, n, e);
    }

    public static double parseDir(String s) {
        if (s == null) return Double.NaN;
        s = s.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return Double.NaN;
        String[] card = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        for (int i = 0; i < card.length; i++) if (card[i].equals(s)) return i * 22.5;
        String pt = s.replace("L", "E").replace("O", "W"); // L = leste, O = oeste
        for (int i = 0; i < card.length; i++) if (card[i].equals(pt)) return i * 22.5;
        try {
            String first = s.split("[;\\-]")[0].trim(); // "90;270" ou "45-90": usa o primeiro
            double v = Double.parseDouble(first);
            return ((v % 360) + 360) % 360;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** Diferença entre dois rumos (0..180). */
    public static double angDiff(double a, double b) {
        double d = Math.abs(((a - b) % 360 + 360) % 360);
        return d > 180 ? 360 - d : d;
    }

    /** Candidato à frente: radar, metros à frente e metros para o lado. */
    public static class Ahead {
        public Radar r;
        public double along, cross;
    }

    /**
     * Radares à frente sem rota (modo Google Maps / só radar), pela posição e rumo.
     * O radar "travado" (o que já está na tela) tem tolerância maior, pra não piscar.
     * Também devolve os radares das pistas paralelas (até 60 m pro lado), pra achar o menor limite.
     */
    public synchronized List<Ahead> scan(double lat, double lon, double heading, int maxDist, long lockedId) {
        List<Ahead> out = new ArrayList<>();
        if (heading < 0) return out;
        for (Radar r : radars.values()) {
            double d = Geo.dist(lat, lon, r.lat, r.lon);
            if (d > maxDist + 80) continue;
            double brg = Geo.bearing(lat, lon, r.lat, r.lon);
            double diff = Math.toRadians(((brg - heading) % 360 + 540) % 360 - 180);
            double along = d * Math.cos(diff), cross = Math.abs(d * Math.sin(diff));
            if (along < -30 || along > maxDist) continue;
            if (!Double.isNaN(r.dir) && angDiff(r.dir, heading) > 55) continue; // outro sentido
            boolean locked = r.id == lockedId;
            double tol = locked ? Math.max(45, along * 0.15) : Math.max(60, along * 0.12);
            if (cross > tol) continue;
            Ahead x = new Ahead();
            x.r = r; x.along = along; x.cross = cross;
            out.add(x);
        }
        java.util.Collections.sort(out, (x, y) -> Double.compare(x.along, y.along));
        return out;
    }

    /** Compatível com os testes antigos: o radar da pista (até ~18 m pro lado) mais perto. */
    public synchronized Object[] ahead(double lat, double lon, double heading, int maxDist) {
        for (Ahead x : scan(lat, lon, heading, maxDist, 0)) {
            if (x.cross <= Math.max(18, x.along * 0.07)) return new Object[]{x.r, x.along};
        }
        return null;
    }

    // ---------------- arquivo ----------------

    private synchronized void load() {
        try {
            if (!file.exists()) return;
            byte[] b = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            }
            JSONObject o = new JSONObject(new String(b, "UTF-8"));
            JSONArray rs = o.getJSONArray("r");
            for (int i = 0; i < rs.length(); i++) {
                JSONArray x = rs.getJSONArray(i);
                Radar r = new Radar();
                r.id = x.getLong(0); r.lat = x.getDouble(1); r.lon = x.getDouble(2);
                r.maxspeed = x.getInt(3);
                r.dir = x.isNull(4) ? Double.NaN : x.getDouble(4);
                radars.put(r.id, r);
            }
            JSONArray ig = o.optJSONArray("i");
            if (ig != null) for (int i = 0; i < ig.length(); i++) ignored.add(ig.getLong(i));
            JSONArray as = o.getJSONArray("a");
            for (int i = 0; i < as.length(); i++) {
                JSONArray x = as.getJSONArray(i);
                Area a = new Area();
                a.s = x.getDouble(0); a.w = x.getDouble(1); a.n = x.getDouble(2); a.e = x.getDouble(3); a.at = x.getLong(4);
                areas.add(a);
            }
        } catch (Exception ignored) {}
    }

    private void save() {
        try {
            JSONObject o = new JSONObject();
            JSONArray rs = new JSONArray(), as = new JSONArray();
            synchronized (this) {
                for (Radar r : radars.values()) {
                    JSONArray x = new JSONArray();
                    x.put(r.id); x.put(r.lat); x.put(r.lon); x.put(r.maxspeed);
                    if (Double.isNaN(r.dir)) x.put(JSONObject.NULL); else x.put(r.dir);
                    rs.put(x);
                }
                for (Area a : areas) {
                    JSONArray x = new JSONArray();
                    x.put(a.s); x.put(a.w); x.put(a.n); x.put(a.e); x.put(a.at);
                    as.put(x);
                }
            }
            JSONArray ig = new JSONArray();
            synchronized (this) { for (Long id : ignored) ig.put(id); }
            o.put("r", rs);
            o.put("a", as);
            o.put("i", ig);
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(o.toString().getBytes("UTF-8")); }
        } catch (Exception ignored) {}
    }
}
