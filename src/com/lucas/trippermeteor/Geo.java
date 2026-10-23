package com.lucas.trippermeteor;

/** Contas de geografia: polyline, distâncias e projeção num segmento. */
public final class Geo {
    private Geo() {}

    public static final double R = 6371008.8;

    public static double dist(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Rumo de 1 para 2, em graus 0..360. */
    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** Decodifica polyline (precisão 5 ou 6). Retorna {lats[], lons[]}. */
    public static double[][] decode(String s, int precision) {
        double f = Math.pow(10, precision);
        int n = 0;
        double[] la = new double[Math.max(16, s.length() / 2)], lo = new double[la.length];
        int i = 0, lat = 0, lon = 0;
        while (i < s.length()) {
            int[] r = next(s, i); i = r[1]; lat += r[0];
            r = next(s, i); i = r[1]; lon += r[0];
            if (n == la.length) {
                la = java.util.Arrays.copyOf(la, n * 2);
                lo = java.util.Arrays.copyOf(lo, n * 2);
            }
            la[n] = lat / f;
            lo[n] = lon / f;
            n++;
        }
        return new double[][]{java.util.Arrays.copyOf(la, n), java.util.Arrays.copyOf(lo, n)};
    }

    private static int[] next(String s, int i) {
        int result = 0, shift = 0, b;
        do {
            b = s.charAt(i++) - 63;
            result |= (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20 && i < s.length());
        int v = (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
        return new int[]{v, i};
    }

    /**
     * Projeta P no segmento A-B (aproximação plana local, ótima para distâncias curtas).
     * Retorna {t (0..1), distância P->segmento em metros}.
     */
    public static double[] project(double pLat, double pLon, double aLat, double aLon, double bLat, double bLon) {
        double k = Math.cos(Math.toRadians(pLat)) * Math.PI / 180 * R;
        double kLat = Math.PI / 180 * R;
        double ax = (aLon - pLon) * k, ay = (aLat - pLat) * kLat;
        double bx = (bLon - pLon) * k, by = (bLat - pLat) * kLat;
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : -(ax * dx + ay * dy) / len2;
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        double qx = ax + t * dx, qy = ay + t * dy;
        return new double[]{t, Math.sqrt(qx * qx + qy * qy)};
    }
}
