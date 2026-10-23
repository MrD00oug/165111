package com.lucas.trippermeteor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tudo que usa internet: busca de endereço, rota (OSRM), radares (Overpass) e links do Google Maps. Chamar fora da main thread. */
public final class Router {
    private Router() {}

    public static final String UA = "TripperMeteor/0.2 (app pessoal; Android)";
    private static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36";

    public static class Place {
        public String name, detail;
        public double lat, lon;
        public Place(String name, String detail, double lat, double lon) {
            this.name = name; this.detail = detail; this.lat = lat; this.lon = lon;
        }
        @Override public String toString() { return name + (detail == null || detail.isEmpty() ? "" : " · " + detail); }
    }

    // ---------------- HTTP ----------------

    static String get(String url, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9");
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = read(in);
        if (code >= 400) throw new Exception("HTTP " + code + " em " + new URL(url).getHost());
        return body;
    }

    static String post(String url, String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(40000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream o = c.getOutputStream()) { o.write(form.getBytes("UTF-8")); }
        int code = c.getResponseCode();
        String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code >= 400) throw new Exception("HTTP " + code + " em " + new URL(url).getHost());
        return body;
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String dec(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    // ---------------- busca de endereço ----------------

    public static List<Place> geocode(String q, double biasLat, double biasLon) throws Exception {
        List<Place> out = new ArrayList<>();
        try {
            String url = "https://photon.komoot.io/api/?limit=8&q=" + enc(q)
                    + (biasLat != 0 ? String.format(Locale.US, "&lat=%.5f&lon=%.5f", biasLat, biasLon) : "");
            JSONArray fs = new JSONObject(get(url, UA)).getJSONArray("features");
            for (int i = 0; i < fs.length(); i++) {
                JSONObject f = fs.getJSONObject(i);
                JSONArray c = f.getJSONObject("geometry").getJSONArray("coordinates");
                JSONObject p = f.getJSONObject("properties");
                String name = p.optString("name", "");
                String street = p.optString("street", "");
                String num = p.optString("housenumber", "");
                if (name.isEmpty()) name = street + (num.isEmpty() ? "" : ", " + num);
                else if (!street.isEmpty() && !name.equals(street)) name = name + " — " + street + (num.isEmpty() ? "" : ", " + num);
                else if (!num.isEmpty()) name = name + ", " + num;
                String detail = join(p.optString("district", ""), p.optString("city", ""), p.optString("state", ""));
                if (name.isEmpty()) name = detail;
                out.add(new Place(name, detail, c.getDouble(1), c.getDouble(0)));
            }
        } catch (Exception e) {
            // reserva: Nominatim
            String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&countrycodes=br&q=" + enc(q);
            JSONArray a = new JSONArray(get(url, UA));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String dn = o.optString("display_name", "");
                int cut = dn.indexOf(", ");
                out.add(new Place(cut > 0 ? dn.substring(0, cut) : dn, cut > 0 ? dn.substring(cut + 2) : "",
                        o.getDouble("lat"), o.getDouble("lon")));
            }
        }
        return out;
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (p != null && !p.isEmpty()) { if (sb.length() > 0) sb.append(", "); sb.append(p); }
        return sb.toString();
    }

    // ---------------- link compartilhado do Google Maps ----------------

    private static final Pattern P_3D4D = Pattern.compile("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)");
    private static final Pattern P_AT = Pattern.compile("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)");
    private static final Pattern P_Q = Pattern.compile("[?&](?:q|query|destination|daddr|ll|center)=(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)");
    private static final Pattern P_GEO = Pattern.compile("geo:(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)");
    private static final Pattern P_URL = Pattern.compile("https?://\\S+");
    private static final Pattern P_QTEXT = Pattern.compile("[?&](?:q|query|destination|daddr)=([^&]+)");
    private static final Pattern P_PLACE = Pattern.compile("/maps/place/([^/@?]+)");

    /** Tenta achar coordenadas dentro de um texto/URL. */
    public static double[] coordsIn(String s) {
        if (s == null) return null;
        String d = dec(s);
        for (Pattern p : new Pattern[]{P_3D4D, P_GEO, P_Q, P_AT}) {
            Matcher m = p.matcher(d);
            if (m.find()) {
                double la = Double.parseDouble(m.group(1)), lo = Double.parseDouble(m.group(2));
                if (Math.abs(la) <= 90 && Math.abs(lo) <= 180 && !(la == 0 && lo == 0)) return new double[]{la, lo};
            }
        }
        return null;
    }

    /** Texto do que foi compartilhado (ex.: "Padaria X\nhttps://maps.app.goo.gl/abc") -> destino. */
    public static Place resolveShare(String text, double biasLat, double biasLon) throws Exception {
        if (text == null) return null;
        String name = text;
        Matcher um = P_URL.matcher(text);
        String url = um.find() ? um.group() : null;
        if (url != null) name = text.replace(url, "");
        name = firstLine(name);

        double[] c = coordsIn(text);
        String placeText = null;
        if (c == null && url != null) {
            String cur = url;
            for (int i = 0; i < 8 && cur != null; i++) {
                c = coordsIn(cur);
                if (c != null) break;
                if (placeText == null) placeText = textFromUrl(cur);
                String nxt = redirect(cur);
                if (nxt == null) break;
                if (nxt.contains("consent.google")) {
                    Matcher cm = Pattern.compile("[?&]continue=([^&]+)").matcher(nxt);
                    if (cm.find()) nxt = dec(cm.group(1));
                }
                cur = nxt;
            }
            if (c == null && cur != null) {
                c = coordsIn(cur);
                if (placeText == null) placeText = textFromUrl(cur);
            }
        }
        if (name.isEmpty() && placeText != null) name = placeText;
        if (c != null) return new Place(name.isEmpty() ? "Local compartilhado" : name, "", c[0], c[1]);

        String q = placeText != null ? placeText : name;
        if (q == null || q.isEmpty()) return null;
        List<Place> r = geocode(q, biasLat, biasLon);
        return r.isEmpty() ? null : r.get(0);
    }

    private static String textFromUrl(String u) {
        Matcher m = P_PLACE.matcher(u);
        if (m.find()) return dec(m.group(1)).replace('+', ' ');
        m = P_QTEXT.matcher(u);
        if (m.find()) {
            String t = dec(m.group(1)).replace('+', ' ');
            if (coordsIn("q=" + t) == null) return t;
        }
        return null;
    }

    private static String firstLine(String s) {
        for (String l : s.split("\\r?\\n")) {
            String t = l.trim();
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    private static String redirect(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", BROWSER_UA);
            int code = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (code >= 300 && code < 400 && loc != null) return new URL(new URL(url), loc).toString();
        } catch (Exception ignored) {}
        return null;
    }

    // ---------------- rota ----------------

    private static final String[] OSRM = {
            "https://routing.openstreetmap.de/routed-car/route/v1/driving/",
            "https://router.project-osrm.org/route/v1/driving/"
    };

    public static Route route(double fLat, double fLon, double tLat, double tLon, double heading, boolean exitIcons) throws Exception {
        String coords = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", fLon, fLat, tLon, tLat);
        String q = "?overview=full&geometries=polyline6&steps=true&alternatives=false&continue_straight=true";
        if (heading >= 0) q += String.format(Locale.US, "&bearings=%d,60;", Math.round(heading) % 360);
        Exception last = null;
        for (String base : OSRM) {
            try {
                JSONObject o = new JSONObject(get(base + coords + q, UA));
                if (!"Ok".equals(o.optString("code"))) throw new Exception("rota: " + o.optString("message", o.optString("code")));
                return parse(o.getJSONArray("routes").getJSONObject(0), exitIcons);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last;
    }

    public static Route parse(JSONObject r, boolean exitIcons) throws Exception {
        double[][] g = Geo.decode(r.getString("geometry"), 6);
        Route route = new Route(g[0], g[1], r.optDouble("duration", 0));
        JSONArray steps = r.getJSONArray("legs").getJSONObject(0).getJSONArray("steps");
        int hint = 0;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject s = steps.getJSONObject(i);
            JSONObject m = s.getJSONObject("maneuver");
            String type = m.optString("type", "turn");
            String mod = m.optString("modifier", "");
            String mode = s.optString("mode", "driving");
            if (!announce(type, mod) && !"ferry".equals(mode)) continue;
            JSONArray loc = m.getJSONArray("location");
            Route.Step st = new Route.Step();
            st.type = type;
            st.modifier = mod;
            st.exit = m.optInt("exit", 0);
            st.name = s.optString("name", "");
            if (st.name.isEmpty()) st.name = s.optString("ref", "");
            st.lon = loc.getDouble(0);
            st.lat = loc.getDouble(1);
            double[] a = route.locate(st.lat, st.lon, hint);
            st.along = a[0];
            hint = (int) a[2];
            st.icon = "ferry".equals(mode) ? 62 : icon(type, mod, st.exit, exitIcons);
            st.speech = speech(type, mod, st.exit, st.name);
            route.steps.add(st);
        }
        return route;
    }

    static boolean announce(String type, String mod) {
        switch (type) {
            case "new name": case "notification": case "exit roundabout": case "exit rotary": case "use lane":
                return false;
            case "continue": case "turn":
                return !(mod.isEmpty() || "straight".equals(mod));
            default:
                return true;
        }
    }

    static int turnIcon(String mod) {
        switch (mod) {
            case "uturn": return 61;
            case "sharp right": return 23;
            case "right": return 21;
            case "slight right": return 25;
            case "slight left": return 24;
            case "left": return 20;
            case "sharp left": return 22;
            default: return 9;
        }
    }

    /** Manobra do OSRM -> ícone do pod (mão inglesa não; Brasil = mão direita, rotatória anti-horária). */
    static int icon(String type, String mod, int exit, boolean exitIcons) {
        boolean L = mod.contains("left"), Rt = mod.contains("right");
        switch (type) {
            case "depart": return 60;
            case "arrive": return L ? 1 : Rt ? 2 : 0;
            case "roundabout": case "rotary":
                return (exitIcons && exit >= 1 && exit <= 9) ? 49 + exit : 49;
            case "merge": return L ? 4 : Rt ? 3 : 27;
            case "on ramp":
                switch (mod) {
                    case "sharp left": return 34;
                    case "sharp right": return 33;
                    case "slight left": return 32;
                    case "slight right": return 31;
                    case "left": return 30;
                    case "right": return 29;
                    default: return 9;
                }
            case "off ramp":
                switch (mod) {
                    case "slight left": return 46;
                    case "slight right": return 45;
                    default: return L ? 8 : Rt ? 7 : 9;
                }
            case "fork": return L ? 6 : Rt ? 5 : 9;
            case "continue":
                if ("slight left".equals(mod)) return 43;
                if ("slight right".equals(mod)) return 44;
                return turnIcon(mod);
            default: return turnIcon(mod);
        }
    }

    static String speech(String type, String mod, int exit, String name) {
        String dir;
        switch (mod) {
            case "uturn": dir = "faça o retorno"; break;
            case "sharp right": dir = "vire acentuado à direita"; break;
            case "right": dir = "vire à direita"; break;
            case "slight right": dir = "mantenha-se à direita"; break;
            case "slight left": dir = "mantenha-se à esquerda"; break;
            case "left": dir = "vire à esquerda"; break;
            case "sharp left": dir = "vire acentuado à esquerda"; break;
            default: dir = "siga em frente";
        }
        String s;
        switch (type) {
            case "depart": return "Rota iniciada";
            case "arrive": return mod.contains("left") ? "Destino à esquerda" : mod.contains("right") ? "Destino à direita" : "Você chegou";
            case "roundabout": case "rotary":
                s = exit > 0 ? "na rotatória, pegue a " + exit + "ª saída" : "entre na rotatória"; break;
            case "merge": s = "entre na via"; break;
            case "on ramp": s = "pegue o acesso" + (mod.contains("left") ? " à esquerda" : mod.contains("right") ? " à direita" : ""); break;
            case "off ramp": s = "pegue a saída" + (mod.contains("left") ? " à esquerda" : mod.contains("right") ? " à direita" : ""); break;
            case "fork": s = "na bifurcação, " + (mod.contains("left") ? "fique à esquerda" : "fique à direita"); break;
            default: s = dir;
        }
        if (name != null && !name.isEmpty() && !"arrive".equals(type)) s += ", " + name;
        return s;
    }

    public static int parseSpeed(String s) {
        Matcher m = Pattern.compile("(\\d+)").matcher(s);
        if (!m.find()) return 0;
        int v = Integer.parseInt(m.group(1));
        if (s.contains("mph")) v = (int) Math.round(v * 1.609);
        return v;
    }

    /** Aceita "usuario/projeto", o link inteiro, com espaço, ".git" ou barra no fim. */
    public static String cleanRepo(String s) {
        String r = s == null ? "" : s.trim();
        r = r.replaceAll("(?i)^(https?://)?(www\\.)?github\\.com/", "");
        r = r.replaceAll("(?i)\\.git$", "").replaceAll("/+$", "");
        String[] p = r.split("/");
        return p.length >= 2 ? p[0].trim() + "/" + p[1].trim() : r;
    }

}
