import com.lucas.trippermeteor.*;

import org.json.JSONObject;

/** Testes de lógica que rodam no PC (sem Android). */
public class LogicTest {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok    " : "FALHA ") + what);
        if (!ok) fails++;
    }

    /** Reimplementação literal do luc.d(...) do app oficial, para comparar. */
    static byte[] official(int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8, int i9, int i10, int i11, int i12, int i13, int i14, int i15, int i16) {
        int[] b = new int[18];
        b[0] = 16;
        b[1] = ((i & 15) << 4) | (i2 & 15);
        b[2] = i3 & 255;
        b[3] = (i4 >> 8) & 255; b[4] = i4 & 255;
        b[5] = i6 & 255;
        b[6] = ((i5 & 15) << 4) | (i16 & 15);
        b[7] = i7 & 255;
        b[8] = (i8 >> 8) & 255; b[9] = i8 & 255;
        b[10] = ((i9 & 15) << 4) | (i10 & 15);
        b[13] = i12 & 255;
        if (i12 == 0) { b[11] = ((i13 & 3) << 6) | (i14 & 63); b[12] = i15 & 255; }
        else { b[11] = (i11 >> 8) & 255; b[12] = i11 & 255; }
        return Proto.frame(b);
    }

    public static void main(String[] a) throws Exception {
        // CRC dos pacotes fixos conhecidos
        check(Proto.hex(Proto.handshake(true)).endsWith("40 45"), "CRC retomar = 40 45");
        check(Proto.hex(Proto.handshake(false)).endsWith("50 A7"), "CRC pedir PIN = 50 A7");

        // pacote de navegação igual ao do app oficial (sem ETA)
        byte[] mine = Proto.nav(21, 200, -1, -1, 1, Proto.BOTTOM_NONE, 0, 0, false);
        // oficial: d(1,1,icon, dist, progress, unit, next, dist, unit, alert, total, totalUnit, fmt, h, m, night)
        byte[] off = official(1, 1, 21, 200, 6, 1, -1, 200, 1, 1, 0, 0, 0, 0, 0, 0);
        check(java.util.Arrays.equals(mine, off), "nav 200 m igual ao oficial: " + Proto.hex(mine));

        byte[] mine2 = Proto.nav(20, 1500, -1, 21, 2, Proto.BOTTOM_TOTAL, 0, 12300, true);
        byte[] off2 = official(1, 1, 20, 15, 5, 2, 21, 15, 2, 2, 123, 2, 0, 0, 0, 1);
        check(java.util.Arrays.equals(mine2, off2), "nav 1,5 km + total 12,3 km + noite igual ao oficial: " + Proto.hex(mine2));

        check(Proto.progress(15) == 4 && Proto.progress(35) == 3 && Proto.progress(55) == 2 && Proto.progress(75) == 1
                && Proto.progress(95) == 0 && Proto.progress(300) == 6 && Proto.progress(900) == 5, "enchimento da seta igual ao oficial");

        // polyline (exemplo clássico do Google, precisão 5)
        double[][] g = Geo.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5);
        check(g[0].length == 3 && Math.abs(g[0][0] - 38.5) < 1e-9 && Math.abs(g[1][2] + 126.453) < 1e-9, "decodifica polyline");

        // links do Google Maps
        double[] c1 = Router.coordsIn("https://www.google.com/maps/place/Padaria/@-23.5,-46.6,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d-23.4876543!4d-46.7123456");
        check(c1 != null && Math.abs(c1[0] + 23.4876543) < 1e-9 && Math.abs(c1[1] + 46.7123456) < 1e-9, "link com !3d!4d (local exato)");
        double[] c2 = Router.coordsIn("https://maps.google.com/?q=-23.501,-46.702");
        check(c2 != null && Math.abs(c2[0] + 23.501) < 1e-9, "link com ?q=lat,lon");
        double[] c3 = Router.coordsIn("geo:-23.5,-46.6?q=Casa");
        check(c3 != null && Math.abs(c3[1] + 46.6) < 1e-9, "link geo:");
        check(Router.coordsIn("https://maps.app.goo.gl/AbCdEf123") == null, "link curto sem coordenada (vai seguir o redirecionamento)");

        // rota sintética no formato do OSRM: reto pro norte 500 m, vira à direita, rotatória 2ª saída, chega
        String poly = encode(new double[][]{{-23.5000, -46.7000}, {-23.4955, -46.7000}, {-23.4955, -46.6950}, {-23.4955, -46.6900}});
        String json = "{\"geometry\":\"" + poly.replace("\\", "\\\\") + "\",\"duration\":180,\"distance\":1500,\"legs\":[{\"steps\":["
                + "{\"name\":\"Rua A\",\"mode\":\"driving\",\"maneuver\":{\"type\":\"depart\",\"location\":[-46.7,-23.5]}},"
                + "{\"name\":\"Rua A\",\"mode\":\"driving\",\"maneuver\":{\"type\":\"new name\",\"modifier\":\"straight\",\"location\":[-46.7,-23.4975]}},"
                + "{\"name\":\"Av. B\",\"mode\":\"driving\",\"maneuver\":{\"type\":\"turn\",\"modifier\":\"right\",\"location\":[-46.7,-23.4955]}},"
                + "{\"name\":\"\",\"mode\":\"driving\",\"maneuver\":{\"type\":\"roundabout\",\"modifier\":\"right\",\"exit\":2,\"location\":[-46.695,-23.4955]}},"
                + "{\"name\":\"\",\"mode\":\"driving\",\"maneuver\":{\"type\":\"arrive\",\"modifier\":\"left\",\"location\":[-46.69,-23.4955]}}"
                + "]}]}";
        java.lang.reflect.Method parse = Router.class.getDeclaredMethod("parse", JSONObject.class, boolean.class);
        parse.setAccessible(true);
        Route r = (Route) parse.invoke(null, new JSONObject(json), false);
        check(r.steps.size() == 4, "ignora 'new name' (sobram 4 manobras): " + r.steps.size());
        check(r.steps.get(1).icon == 21 && Math.abs(r.steps.get(1).along - 500) < 5, "virar à direita = ícone 21 a ~500 m (" + Math.round(r.steps.get(1).along) + " m)");
        check(r.steps.get(2).icon == 49 && r.steps.get(2).exit == 2, "rotatória = ícone 49, saída 2");
        Route r2 = (Route) parse.invoke(null, new JSONObject(json), true);
        check(r2.steps.get(2).icon == 51, "rotatória com número = 49 + 2 = 51");
        check(r.steps.get(3).icon == 1, "destino à esquerda = 1");
        System.out.println("      voz: " + r.steps.get(2).speech + " | " + r.steps.get(1).speech);

        // encaixe na rota
        Route.Snap s = r.snap(-23.4980, -46.7002, -1);
        check(s.off < 30 && Math.abs(s.along - 222) < 10, String.format("encaixe na rota (along %.0f m, fora %.0f m)", s.along, s.off));
        double[] p = r.pointAt(700);
        check(Math.abs(p[0] + 23.4955) < 1e-4 && Math.abs(p[2] - 90) < 2, String.format("ponto a 700 m indo pro leste (%.5f, %.5f, rumo %.0f)", p[0], p[1], p[2]));

        check(Router.parseSpeed("60") == 60 && Router.parseSpeed("40 mph") == 64 && Router.parseSpeed("") == 0, "lê limite de velocidade");

        System.out.println(fails == 0 ? "\nTUDO OK" : "\n" + fails + " FALHA(S)");
        if (fails > 0) System.exit(1);
    }

    static String encode(double[][] pts) {
        StringBuilder sb = new StringBuilder();
        long plat = 0, plon = 0;
        for (double[] q : pts) {
            long la = Math.round(q[0] * 1e6), lo = Math.round(q[1] * 1e6);
            enc(la - plat, sb); enc(lo - plon, sb);
            plat = la; plon = lo;
        }
        return sb.toString();
    }

    static void enc(long v, StringBuilder sb) {
        v = v < 0 ? ~(v << 1) : (v << 1);
        while (v >= 0x20) { sb.append((char) ((0x20 | (v & 0x1f)) + 63)); v >>= 5; }
        sb.append((char) (v + 63));
    }
}
