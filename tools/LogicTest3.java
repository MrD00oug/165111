import com.lucas.trippermeteor.*;

import java.io.File;

/** Testes da 0.3: leitura do Google Maps e radar. */
public class LogicTest3 {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok    " : "FALHA ") + what);
        if (!ok) fails++;
    }

    static void maps(String title, String text, String sub, int icon, int dist) {
        MapsParser.Instr i = MapsParser.parse(title, text, sub);
        check(i.icon == icon && i.distM == dist, "[" + title + " | " + text + "] -> " + i);
    }

    public static void main(String[] a) throws Exception {
        maps("250 m", "Vire à direita na Av. Dr. Gastão Vidigal", "11 min · 6,3 km · Chegada: 21:35", 21, 250);
        maps("1,2 km", "Mantenha-se à esquerda em direção a Marginal Tietê", "", 43, 1200);
        maps("Vire à esquerda", "300 m · R. Cardeal Arcoverde", "", 20, 300);
        maps("Rua Direita", "Siga em frente por 800 m", "", 9, 800);
        maps("Faça um retorno", "150 m", "", 61, 150);
        maps("Na rotatória, pegue a 2ª saída para a Av. Brasil", "400 m", "", 49, 400);
        maps("50 m", "O destino está à esquerda", "", 1, 50);
        maps("Você chegou", "R. Sebastião de Pina, 100", "", 0, -1);
        maps("900 m", "Pegue a saída 18 em direção a Rod. dos Bandeirantes", "", 7, 900);
        maps("200 m", "Curva suave à direita", "", 25, 200);
        maps("600 m", "Entre à direita na Marginal Tietê", "", 3, 600);
        maps("100 m", "Vire acentuadamente à esquerda", "", 22, 100);
        maps("2 km", "Continue na Marginal Tietê", "", 9, 2000);
        maps("80 m", "R. Entre Rios", "", -1, 80);

        MapsParser.Instr e = MapsParser.parse("250 m", "Vire à direita", "1 h 5 min · 63 km · 22:40");
        check(e.etaH == 22 && e.etaM == 40 && e.remainM == 63000 && e.remainMin == 65, "sub-texto: chegada 22:40, 63 km, 65 min -> " + e);
        MapsParser.Instr e2 = MapsParser.parse("250 m", "Vire à direita", "18 min (7,5 km) · chegada às 9:05 PM");
        check(e2.etaH == 21 && e2.etaM == 5 && e2.remainM == 7500, "sub-texto com PM -> " + e2);
        MapsParser.Instr r3 = MapsParser.parse("400 m", "Na rotatória, pegue a terceira saída", "");
        check(r3.icon == 49 && r3.exit == 3, "rotatória por extenso: saída 3");

        // radar à frente sem rota
        RadarStore st = new RadarStore(new File("/tmp/radar-teste.json"));
        java.lang.reflect.Field f = RadarStore.class.getDeclaredField("radars");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Long, RadarStore.Radar> m = (java.util.Map<Long, RadarStore.Radar>) f.get(st);
        // indo pro norte; radar 300 m à frente na mesma pista, outro 300 m à frente mas 40 m pro lado, outro atrás
        RadarStore.Radar r1 = new RadarStore.Radar(); r1.id = 1; r1.lat = -23.5000 + 300 / 111000.0; r1.lon = -46.7; r1.maxspeed = 50; r1.dir = 0;
        RadarStore.Radar r2 = new RadarStore.Radar(); r2.id = 2; r2.lat = -23.5000 + 250 / 111000.0; r2.lon = -46.7 + 40 / 102000.0; r2.maxspeed = 90;
        RadarStore.Radar r3x = new RadarStore.Radar(); r3x.id = 3; r3x.lat = -23.5000 - 200 / 111000.0; r3x.lon = -46.7;
        RadarStore.Radar r4 = new RadarStore.Radar(); r4.id = 4; r4.lat = -23.5000 + 150 / 111000.0; r4.lon = -46.7; r4.dir = 180; // sentido contrário
        m.put(1L, r1); m.put(2L, r2); m.put(3L, r3x); m.put(4L, r4);
        Object[] ah = st.ahead(-23.5, -46.7, 0, 500);
        check(ah != null && ((RadarStore.Radar) ah[0]).id == 1 && Math.abs((Double) ah[1] - 300) < 5,
                "radar à frente: pega o da pista (300 m), ignora o do lado, o de trás e o do outro sentido");
        check(st.ahead(-23.5, -46.7, 180, 500) != null && ((RadarStore.Radar) st.ahead(-23.5, -46.7, 180, 500)[0]).id == 3,
                "indo pro sul pega o radar de trás");

        check(RadarStore.parseDir("NE") == 45 && RadarStore.parseDir("SO") == 225 && RadarStore.parseDir("90") == 90
                && Double.isNaN(RadarStore.parseDir("forward")), "sentido do radar (NE, SO, 90, forward)");

        // quem aparece no pod
        check(RadarAlert.radarWins(100, 400, 500), "radar a 100 m e curva a 400 m: radar");
        check(!RadarAlert.radarWins(300, 100, 500), "radar a 300 m e curva a 100 m: curva");
        check(RadarAlert.radarWins(80, 120, 500), "radar a 80 m e curva a 120 m: radar (está antes)");
        check(!RadarAlert.radarWins(120, 40, 500), "curva a 40 m antes do radar: curva");
        check(RadarAlert.radarWins(-20, 300, 500), "radar 20 m pra trás ainda aparece (não some em cima)");
        check(!RadarAlert.radarWins(-40, 300, 500), "radar 40 m pra trás sai da tela");
        check(RadarAlert.radarWins(400, 900, 500), "radar a 400 m com curva longe: radar");

        System.out.println(fails == 0 ? "\nTUDO OK" : "\n" + fails + " FALHA(S)");
        if (fails > 0) System.exit(1);
    }
}
