import com.lucas.trippermeteor.*;

import java.io.File;

/** Testes da 0.4: alerta do Radarbot e radares marcados. */
public class LogicTest4 {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok    " : "FALHA ") + what);
        if (!ok) fails++;
    }

    static void alert(String t, String x, String s, Integer dist, Integer limit) {
        Object[] r = MapsParser.radarAlert(t, x, s);
        boolean ok = dist == null ? r == null : (r != null && r[0].equals(dist) && r[1].equals(limit));
        check(ok, "[" + t + " | " + x + " | " + s + "] -> " + (r == null ? "nada" : r[2] + " " + r[0] + " m, limite " + r[1]));
    }

    public static void main(String[] a) throws Exception {
        alert("Radar fixo", "350 m · 60 km/h", "", 350, 60);
        alert("Radarbot", "Radar de velocidade a 200 m", "Limite 50 km/h", 200, 50);
        alert("Câmera de semáforo", "120m", "", 120, 0);
        alert("Radarbot", "Ativo em segundo plano", "", null, null);
        alert("Radarbot", "45 km/h", "", null, null);              // velocidade atual, não é alerta
        alert("Radarbot", "Nenhum radar por perto", "", null, null);
        alert("Velocidade média", "1,2 km · 80 km/h", "", 1200, 80);
        alert("Radar à frente", "Limite 40 km/h", "", -1, 40);

        File f = new File("/tmp/radar-teste4.json");
        f.delete();
        RadarStore st = new RadarStore(f);
        RadarStore.Radar mine = st.addUser(-23.5, -46.7, 90, 0);
        st.setLimit(mine.id, 60);
        check(st.userCount() == 1 && mine.id < 0, "radar marcado entra na base com id negativo");
        RadarStore st2 = new RadarStore(f);
        java.lang.reflect.Method load = RadarStore.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(st2);
        check(st2.userCount() == 1 && st2.all().get(0).maxspeed == 60 && st2.all().get(0).dir == 90, "radar marcado sobrevive ao fechar o app (limite 60, sentido 90°)");
        st2.ignore(mine.id);
        check(st2.size() == 0, "\"não é radar\" remove da base");

        System.out.println(fails == 0 ? "\nTUDO OK" : "\n" + fails + " FALHA(S)");
        if (fails > 0) System.exit(1);
    }
}
