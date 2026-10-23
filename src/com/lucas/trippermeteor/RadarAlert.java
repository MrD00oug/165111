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
        return handle(r, dist, speedMs, set, out, kind, r.maxspeed, r.maxspeed);
    }

    public static String handle(Pick p, double speedMs, Navigator.Settings set, Navigator.Out out) {
        String t = handle(p.r, p.dist, speedMs, set, out, p.kind, limitOf(p), p.maxLimit);
        syncGroup(p);
        return t;
    }

    public static String handle(RadarStore.Radar r, int dist, double speedMs, Navigator.Settings set, Navigator.Out out,
                                String kind, int limit, int maxLimit) {
        long now = System.currentTimeMillis();
        String lim = limit <= 0 ? "" : (maxLimit > limit ? " · limite " + limit + " (pistas até " + maxLimit + ")" : " · limite " + limit);
        String txt = kind + (dist >= 0 ? " a " + Math.max(0, dist) + " m" : " à frente") + lim;
        if (now - r.warnedAt > 5 * 60 * 1000) { // não repete o mesmo radar por 5 min
            r.warnedAt = now;
            if (set.beepRadar) out.beep(1);
            if (set.voiceRadar) out.say(kind + (dist >= 0 ? " em " + Navigator.niceDist(Math.max(0, dist)) : " à frente")
                    + (limit > 0 ? ", limite " + limit : ""));
            out.log(txt, Pod.LOG_INFO);
        }
        if (dist <= 120 && now - r.beepedAt > 5 * 60 * 1000) {
            r.beepedAt = now;
            if (set.beepRadar) out.beep(2);
        }
        double kmh = speedMs * 3.6;
        if (limit > 0 && kmh > limit + 2) {
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
        public int minLimit = 0, maxLimit = 0;          // grupo de pistas paralelas
        public java.util.List<RadarStore.Radar> group = new java.util.ArrayList<>();
    }

    /**
     * Escolhe o radar da base:
     * - o travado (já na tela) continua até passar, mesmo com o GPS oscilando;
     * - senão, o mais perto que esteja na pista (até ~20 m pro lado);
     * - radares das pistas paralelas a ±80 m dele entram no grupo: vale o MENOR limite (pra não tomar multa).
     */
    public static Pick pickBase(java.util.List<RadarStore.Ahead> c, long lockedId) {
        RadarStore.Ahead best = null;
        for (RadarStore.Ahead x : c) if (x.r.id == lockedId) { best = x; break; }
        if (best == null) for (RadarStore.Ahead x : c) {
            if (x.cross <= Math.max(20, x.along * 0.08)) { best = x; break; }
        }
        if (best == null) return null;
        Pick p = new Pick();
        p.r = best.r;
        p.dist = (int) Math.round(best.along);
        for (RadarStore.Ahead x : c) {
            if (Math.abs(x.along - best.along) > 80) continue;
            p.group.add(x.r);
            int l = x.r.maxspeed;
            if (l > 0) {
                p.minLimit = p.minLimit == 0 ? l : Math.min(p.minLimit, l);
                p.maxLimit = Math.max(p.maxLimit, l);
            }
        }
        if (p.group.size() > 1) {
            p.kind = "Radares";
            // um aviso só pro grupo inteiro
            long w = 0, b = 0;
            for (RadarStore.Radar r : p.group) { w = Math.max(w, r.warnedAt); b = Math.max(b, r.beepedAt); }
            for (RadarStore.Radar r : p.group) { r.warnedAt = w; r.beepedAt = b; }
        }
        return p;
    }

    /** Depois do aviso, copia o "já avisei" pro grupo todo. */
    public static void syncGroup(Pick p) {
        if (p == null || p.group.size() < 2) return;
        for (RadarStore.Radar r : p.group) { r.warnedAt = p.r.warnedAt; r.beepedAt = p.r.beepedAt; }
    }

    /** Limite que vale pra você (o menor do grupo). */
    public static int limitOf(Pick p) {
        if (p == null) return 0;
        return p.minLimit > 0 ? p.minLimit : p.r.maxspeed;
    }

    /** Alerta do Radarbot ainda valendo, com a distância descontando o que já andou. */
    public static Pick external(RadarStore st, Navigator.Settings set, double speedMs) {
        if (!set.useRadarbot) return null;
        RadarStore.External e = st.externalFresh(6000);
        if (e == null) return null;
        Pick p = new Pick();
        p.r = e.radar;
        p.kind = e.kind;
        p.minLimit = e.limit;
        p.maxLimit = e.limit;
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
