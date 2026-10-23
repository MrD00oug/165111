package com.lucas.trippermeteor;

import java.util.ArrayList;
import java.util.List;

/** Rota calculada: linha (pontos), distância acumulada, manobras e radares. */
public class Route {

    public static class Step {
        public int icon;          // ícone do pod
        public String type, modifier, name;
        public int exit;          // saída da rotatória (0 = não é rotatória)
        public double lat, lon;
        public double along;      // metros desde o início da rota
        public String speech;     // frase para a voz
    }

    /** Radar encaixado na rota. */
    public static class Radar {
        public RadarStore.Radar r;
        public double along;      // metros desde o início da rota
    }

    public static class Snap {
        public int idx;           // segmento idx..idx+1
        public double along;      // metros desde o início
        public double off;        // distância até a linha
    }

    public final double[] lat, lon, cum;
    public final double length, duration;
    public final List<Step> steps = new ArrayList<>();
    public final List<Radar> radars = new ArrayList<>();
    public double destLat, destLon;
    public String destName;

    public Route(double[] lat, double[] lon, double duration) {
        this.lat = lat;
        this.lon = lon;
        this.cum = new double[lat.length];
        for (int i = 1; i < lat.length; i++) cum[i] = cum[i - 1] + Geo.dist(lat[i - 1], lon[i - 1], lat[i], lon[i]);
        this.length = lat.length > 0 ? cum[lat.length - 1] : 0;
        this.duration = duration;
    }

    /** Encaixa a posição na rota, procurando a partir do segmento "hint" (ou na rota toda se hint < 0). */
    public Snap snap(double pLat, double pLon, int hint) {
        int from = 0, to = lat.length - 2;
        if (hint >= 0) {
            from = Math.max(0, hint - 10);
            to = Math.min(lat.length - 2, hint + 80);
        }
        Snap best = null;
        for (int i = from; i <= to; i++) {
            double[] p = Geo.project(pLat, pLon, lat[i], lon[i], lat[i + 1], lon[i + 1]);
            if (best == null || p[1] < best.off) {
                if (best == null) best = new Snap();
                best.idx = i;
                best.off = p[1];
                best.along = cum[i] + p[0] * (cum[i + 1] - cum[i]);
            }
        }
        if (best == null) { best = new Snap(); best.off = 1e9; }
        return best;
    }

    /** Posição "along" (metros) do ponto mais próximo de (la, lo), procurando do segmento "fromIdx" em diante. */
    public double[] locate(double la, double lo, int fromIdx) {
        int bestI = fromIdx;
        double bestD = 1e18, bestAlong = 0;
        for (int i = Math.max(0, fromIdx); i < lat.length - 1; i++) {
            double[] p = Geo.project(la, lo, lat[i], lon[i], lat[i + 1], lon[i + 1]);
            if (p[1] < bestD - 0.01) {
                bestD = p[1];
                bestI = i;
                bestAlong = cum[i] + p[0] * (cum[i + 1] - cum[i]);
            }
            if (bestD < 2 && cum[i] - bestAlong > 200) break; // já achou e passou longe
        }
        return new double[]{bestAlong, bestD, bestI};
    }

    /** Coloca na rota os radares da base que ficam nela (até 15 m da linha e no mesmo sentido). */
    public void attachRadars(RadarStore store) {
        radars.clear();
        double s = 90, w = 180, n = -90, e = -180;
        for (int i = 0; i < lat.length; i++) {
            s = Math.min(s, lat[i]); n = Math.max(n, lat[i]);
            w = Math.min(w, lon[i]); e = Math.max(e, lon[i]);
        }
        for (RadarStore.Radar r : store.all()) {
            if (r.lat < s - 0.002 || r.lat > n + 0.002 || r.lon < w - 0.002 || r.lon > e + 0.002) continue;
            double[] a = locate(r.lat, r.lon, 0);
            if (a[1] > 15) continue;
            int i = (int) a[2];
            if (i + 1 < lat.length && !Double.isNaN(r.dir)) {
                double brg = Geo.bearing(lat[i], lon[i], lat[i + 1], lon[i + 1]);
                if (RadarStore.angDiff(r.dir, brg) > 55) continue;
            }
            Radar x = new Radar();
            x.r = r;
            x.along = a[0];
            radars.add(x);
        }
        java.util.Collections.sort(radars, (x, y) -> Double.compare(x.along, y.along));
    }

    public double[] bbox() {
        double s = 90, w = 180, n = -90, e = -180;
        for (int i = 0; i < lat.length; i++) {
            s = Math.min(s, lat[i]); n = Math.max(n, lat[i]);
            w = Math.min(w, lon[i]); e = Math.max(e, lon[i]);
        }
        return new double[]{s - 0.005, w - 0.005, n + 0.005, e + 0.005};
    }

    /** Ponto da rota a "along" metros do início (usado na simulação). */
    public double[] pointAt(double along) {
        if (along <= 0) return new double[]{lat[0], lon[0], 0};
        if (along >= length) {
            int n = lat.length - 1;
            return new double[]{lat[n], lon[n], n > 0 ? Geo.bearing(lat[n - 1], lon[n - 1], lat[n], lon[n]) : 0};
        }
        int lo = 0, hi = cum.length - 1;
        while (hi - lo > 1) {
            int m = (lo + hi) >>> 1;
            if (cum[m] <= along) lo = m; else hi = m;
        }
        double seg = cum[hi] - cum[lo];
        double t = seg == 0 ? 0 : (along - cum[lo]) / seg;
        return new double[]{lat[lo] + t * (lat[hi] - lat[lo]), lon[lo] + t * (lon[hi] - lon[lo]),
                Geo.bearing(lat[lo], lon[lo], lat[hi], lon[hi])};
    }
}
