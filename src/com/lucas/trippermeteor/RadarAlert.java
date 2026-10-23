package com.lucas.trippermeteor;

/** Avisos de radar (voz, bipe e excesso de velocidade), iguais nos dois modos de navegação. */
public final class RadarAlert {
    private RadarAlert() {}

    private static long lastOverBeep = 0;

    /**
     * Chamado a cada posição com o radar à frente.
     * @return texto para a tela, ex.: "Radar a 320 m · limite 60"
     */
    public static String handle(RadarStore.Radar r, int dist, double speedMs, Navigator.Settings set, Navigator.Out out) {
        return handle(r, dist, speedMs, set, out, "Radar");
    }

    public static String handle(RadarStore.Radar r, int dist, double speedMs, Navigator.Settings set, Navigator.Out out, String kind) {
        long now = System.currentTimeMillis();
        String txt = kind + (dist >= 0 ? " a " + Math.max(0, dist) + " m" : " à frente") + (r.maxspeed > 0 ? " · limite " + r.maxspeed : "");
        if (now - r.warnedAt > 5 * 60 * 1000) { // não repete o mesmo radar por 5 min
            r.warnedAt = now;
            if (set.beepRadar) out.beep(1);
            if (set.voiceRadar) out.say(kind + (dist >= 0 ? " em " + Navigator.niceDist(Math.max(0, dist)) : " à frente")
                    + (r.maxspeed > 0 ? ", limite " + r.maxspeed : ""));
            out.log(txt, Pod.LOG_INFO);
        }
        if (dist <= 120 && now - r.beepedAt > 5 * 60 * 1000) {
            r.beepedAt = now;
            if (set.beepRadar) out.beep(2);
        }
        double kmh = speedMs * 3.6;
        if (r.maxspeed > 0 && kmh > r.maxspeed + 2) {
            txt += " · ACIMA (" + Math.round(kmh) + ")";
            if (set.beepRadar && now - lastOverBeep > 2000) { lastOverBeep = now; out.beep(3); }
        }
        return txt;
    }

    /** Radar escolhido para a tela (da base ou de outro app). */
    public static class Pick {
        public RadarStore.Radar r;
        public int dist;
        public String kind = "Radar";
        public boolean external;
    }

    /** Alerta do Radarbot ainda valendo, com a distância descontando o que já andou. */
    public static Pick external(RadarStore st, Navigator.Settings set, double speedMs) {
        if (!set.useRadarbot) return null;
        RadarStore.External e = st.externalFresh(6000);
        if (e == null) return null;
        Pick p = new Pick();
        p.r = e.radar;
        p.kind = e.kind;
        p.external = true;
        double age = (System.currentTimeMillis() - e.at) / 1000.0;
        p.dist = e.dist < 0 ? -1 : (int) Math.max(0, Math.round(e.dist - speedMs * Math.min(age, 6)));
        return p;
    }

    /**
     * Quem aparece no pod: o radar ou a curva?
     * Radar ganha quando está perto (até 150 m) e antes da curva, ou quando a curva ainda está longe.
     * Continua aparecendo até 30 m depois de passar, pra não sumir em cima do radar.
     */
    public static boolean radarWins(int radarDist, int turnDist, int warnDist) {
        if (radarDist < -30 || radarDist > warnDist) return false;
        if (radarDist <= 150) return turnDist < 0 || radarDist <= turnDist || turnDist > 60;
        return turnDist < 0 || turnDist > 150;
    }
}
