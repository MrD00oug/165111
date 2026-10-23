import com.lucas.trippermeteor.*;

import java.io.File;
import java.util.*;

/** Testes da 0.5: textos reais do Maps do Lucas, grupo de pistas, trava do radar, link do repositório. */
public class LogicTest5 {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok    " : "FALHA ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        // notificações reais (23/10, trabalho)
        MapsParser.Instr i = MapsParser.parse("120 m · Vire à esquerda na R. Tomé Braga", "", "Chegada: 09:40");
        check(i.icon == 20 && i.distM == 120 && i.etaH == 9 && i.etaM == 40, "real: 120 m esquerda, chegada 09:40 -> " + i);
        i = MapsParser.parse("Vire à esquerda na R. Tomé Braga", "", "Chegada: 09:40");
        check(i.icon == 20 && i.distM == -1, "real: perto da curva o Maps tira a distância (o app estima)");
        i = MapsParser.parse("Chegando", "em Trabalho", "");
        check(i.icon == 0 && i.arrived, "real: \"Chegando em Trabalho\" = destino");
        i = MapsParser.parse("160 m · Vire à direita na R. Prof. Duglas Teixeira Monteiro", "", "Chegada: 09:40");
        check(i.icon == 21 && i.distM == 160, "real: 160 m direita");

        // Marginal: 3 radares lado a lado (expressa 90, central 70, local 60) -> vale 60
        RadarStore st = new RadarStore(new File("/tmp/r5.json"));
        java.lang.reflect.Field f = RadarStore.class.getDeclaredField("radars");
        f.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Long, RadarStore.Radar> m = (Map<Long, RadarStore.Radar>) f.get(st);
        double lat0 = -23.5, lon0 = -46.7, mLat = 1 / 111000.0, mLon = 1 / 102000.0;
        long id = 1;
        for (double[] x : new double[][]{{400, 0, 60}, {420, 25, 70}, {440, 50, 90}}) {
            RadarStore.Radar r = new RadarStore.Radar();
            r.id = id++; r.lat = lat0 + x[0] * mLat; r.lon = lon0 + x[1] * mLon; r.maxspeed = (int) x[2];
            m.put(r.id, r);
        }
        RadarAlert.Pick p = RadarAlert.pickBase(st.scan(lat0, lon0, 0, 500, 0), 0);
        check(p != null && p.group.size() == 3 && RadarAlert.limitOf(p) == 60 && p.maxLimit == 90,
                "pistas paralelas: 3 radares viram 1 aviso com o MENOR limite (60, pistas até 90)");
        // trava: GPS oscila 8° e o radar continua na tela
        long locked = p.r.id;
        RadarAlert.Pick p2 = RadarAlert.pickBase(st.scan(lat0 + 100 * mLat, lon0, 8, 500, locked), locked);
        check(p2 != null && p2.r.id == locked, "trava: rumo oscilando 8° não faz o radar sumir");
        RadarAlert.Pick p3 = RadarAlert.pickBase(st.scan(lat0 + 100 * mLat, lon0, 8, 500, 0), 0);
        System.out.println("      (sem trava, com 8° de erro: " + (p3 == null ? "sumiria" : "acharia o " + p3.r.id) + ")");

        // link do repositório
        String[] in = {"MrD00oug/165111", " https://github.com/MrD00oug/165111 ", "github.com/MrD00oug/165111.git", "https://github.com/MrD00oug/165111/"};
        boolean ok = true;
        for (String s : in) ok &= "MrD00oug/165111".equals(Router.cleanRepo(s));
        check(ok, "repositório aceita link inteiro, espaço, .git e barra");

        System.out.println(fails == 0 ? "\nTUDO OK" : "\n" + fails + " FALHA(S)");
        if (fails > 0) System.exit(1);
    }
}
