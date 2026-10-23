package com.lucas.trippermeteor;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê o texto da notificação de navegação do Google Maps (em português) e tira
 * a manobra, a distância até ela, a hora de chegada e quanto falta.
 */
public final class MapsParser {
    private MapsParser() {}

    public static class Instr {
        public int icon = -1;          // ícone do pod (-1 = não reconheci)
        public int distM = -1;         // metros até a manobra (-1 = não veio)
        public int exit = 0;           // saída da rotatória
        public String text = "";       // instrução (ex.: "Vire à direita na R. X")
        public String street = "";
        public int etaH = -1, etaM = -1;
        public int remainM = -1, remainMin = -1;
        public boolean arrived;
        @Override public String toString() {
            return "ícone " + icon + " · " + distM + " m · saída " + exit + " · chegada " + etaH + ":" + etaM
                    + " · falta " + remainM + " m/" + remainMin + " min · " + text;
        }
    }

    private static final Pattern DIST = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(km|m)(?![a-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2}):(\\d{2})(?:\\s*([ap])\\.?\\s*m\\.?)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern MINS = Pattern.compile("(?:(\\d+)\\s*h(?:ora)?s?\\s*)?(\\d+)\\s*min", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXITN = Pattern.compile("(\\d+)\\s*[aªº°o]?\\s*sa[ií]da", Pattern.CASE_INSENSITIVE);

    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replace(' ', ' ');
    }

    public static int toMeters(String num, String unit) {
        double v = Double.parseDouble(num.replace(',', '.'));
        return (int) Math.round(unit.equalsIgnoreCase("km") ? v * 1000 : v);
    }

    /**
     * @param title  título da notificação (geralmente a distância ou a instrução)
     * @param text   texto (instrução ou rua)
     * @param sub    sub-texto (geralmente "11 min · 6,3 km · 21:35")
     */
    public static Instr parse(String title, String text, String sub) {
        Instr r = new Instr();
        String t = title == null ? "" : title.trim(), x = text == null ? "" : text.trim(), s = sub == null ? "" : sub.trim();

        // distância até a manobra: primeiro no título, depois no texto
        Matcher m = DIST.matcher(t);
        if (m.find()) r.distM = toMeters(m.group(1), m.group(2));
        else { m = DIST.matcher(x); if (m.find()) r.distM = toMeters(m.group(1), m.group(2)); }

        // instrução = parte com letras (tira a distância)
        String instr = stripDist(t);
        String other = stripDist(x);
        if (instr.length() < 3 || !hasWord(instr)) { instr = other; other = ""; }
        r.text = instr;
        r.street = streetOf(instr.isEmpty() ? other : instr, other);

        String all = norm(t + " " + x);
        // as palavras-chave vêm do começo da instrução (antes do nome da rua), pra "Rua Direita" não virar seta
        r.icon = icon(norm(head(instr)));
        if (r.icon < 0) r.icon = icon(norm(head(other)));
        Matcher em = EXITN.matcher(all);
        if (em.find()) r.exit = Integer.parseInt(em.group(1));
        else r.exit = ordinalExit(all);
        if (r.icon == 0 || r.icon == 1 || r.icon == 2) r.arrived = all.contains("chegou") || all.contains("chegando");

        // sub-texto: chegada, tempo e distância que faltam
        String ss = norm(s.isEmpty() ? "" : s);
        Matcher c = CLOCK.matcher(ss);
        if (c.find()) {
            int h = Integer.parseInt(c.group(1)), mi = Integer.parseInt(c.group(2));
            if (c.group(3) != null) {
                boolean pm = c.group(3).equalsIgnoreCase("p");
                if (pm && h < 12) h += 12;
                if (!pm && h == 12) h = 0;
            }
            r.etaH = h; r.etaM = mi;
        }
        Matcher mm = MINS.matcher(ss);
        if (mm.find()) r.remainMin = (mm.group(1) == null ? 0 : Integer.parseInt(mm.group(1)) * 60) + Integer.parseInt(mm.group(2));
        Matcher dm = DIST.matcher(ss);
        if (dm.find()) r.remainM = toMeters(dm.group(1), dm.group(2));
        return r;
    }

    private static final Pattern LIMIT = Pattern.compile("(\\d{2,3})\\s*km\\s*/?\\s*h", Pattern.CASE_INSENSITIVE);
    private static final Pattern METERS = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(km|m)(?![a-z/])", Pattern.CASE_INSENSITIVE);

    /**
     * Alerta de radar vindo da notificação de outro app (Radarbot, Waze).
     * Retorna {distância em m ou -1, limite ou 0, tipo} ou null se não parecer alerta de radar.
     */
    public static Object[] radarAlert(String title, String text, String sub) {
        String all = (title == null ? "" : title) + " · " + (text == null ? "" : text) + " · " + (sub == null ? "" : sub);
        String n = norm(all).replace("radarbot", " ");
        boolean kw = n.contains("radar") || n.contains("camera") || n.contains("semaforo") || n.contains("velocidade media")
                || n.contains("fiscaliza") || n.contains("lombada") || n.contains("speed cam") || n.contains("controle de velocidade");
        if (!kw) return null;
        if (n.contains("nenhum radar") || n.contains("sem radar") || n.contains("buscando") || n.contains("ativo em segundo plano")) return null;
        int limit = 0;
        Matcher lm = LIMIT.matcher(all);
        if (lm.find()) limit = Integer.parseInt(lm.group(1));
        String noLimit = LIMIT.matcher(all).replaceAll(" ");
        int dist = -1;
        Matcher dm = METERS.matcher(noLimit);
        if (dm.find()) dist = toMeters(dm.group(1), dm.group(2));
        if (dist < 0 && limit == 0) return null; // só a palavra "radar" (ex.: nome do app) não basta
        String kind = n.contains("semaforo") ? "Radar de semáforo" : n.contains("media") ? "Velocidade média"
                : n.contains("movel") ? "Radar móvel" : "Radar";
        return new Object[]{dist, limit, kind};
    }

    private static boolean hasWord(String s) { return s.matches(".*\\p{L}{3,}.*"); }

    private static String stripDist(String s) {
        return DIST.matcher(s).replaceAll("").replaceAll("^[\\s·,\\-–]+|[\\s·,\\-–]+$", "").trim();
    }

    private static String streetOf(String instr, String other) {
        String n = instr;
        for (String k : new String[]{" na ", " no ", " em direção a ", " em direcao a ", " para ", " pela ", " pelo ", " sentido "}) {
            int i = n.toLowerCase(Locale.ROOT).indexOf(k);
            if (i >= 0) return n.substring(i + k.length()).trim();
        }
        return other == null ? "" : other;
    }

    private static String head(String s) {
        if (s == null) return "";
        String n = norm(s);
        int cut = n.length();
        for (String k : new String[]{" na ", " no ", " em direcao", " pela ", " pelo ", " para a ", " para o ", " sentido ", " rumo "}) {
            int i = n.indexOf(k);
            if (i >= 0 && i < cut) cut = i;
        }
        // "na rotatoria" e "na bifurcacao" fazem parte da manobra
        if (n.startsWith("na rotatoria") || n.startsWith("na bifurcacao") || n.startsWith("no retorno")) return n;
        return n.substring(0, cut);
    }

    private static int ordinalExit(String n) {
        String[] ord = {"primeira", "segunda", "terceira", "quarta", "quinta", "sexta", "setima", "oitava"};
        for (int i = 0; i < ord.length; i++) if (n.contains(ord[i] + " saida")) return i + 1;
        return 0;
    }

    /** Texto normalizado (sem acento, minúsculo) -> ícone do pod. Brasil: mão direita. */
    public static int icon(String n) {
        // "a direita"/"a esquerda" (de "à direita"): evita confundir com nome de rua, tipo "Rua Direita"
        boolean L = n.matches(".*\\ba esquerda.*"), R = n.matches(".*\\ba direita.*");
        if (n.contains("chegou") || n.contains("chegando") || n.contains("destino") || n.contains("chegada ao")) return L ? 1 : R ? 2 : 0;
        if (n.contains("retorno")) return 61;
        if (n.contains("rotatoria") || n.contains("rotunda") || n.contains("balao")) return 49;
        if (n.contains("balsa")) return 62;
        if (n.contains("bifurca")) return L ? 6 : R ? 5 : 9;
        if (n.contains("saida") && !n.contains("saida de")) {
            if (n.contains("ligeir") || n.contains("suave") || n.contains("levemente")) return L ? 46 : 45;
            return L ? 8 : R ? 7 : 7;
        }
        if (n.contains("acesso") || n.contains("rampa")) return L ? 30 : R ? 29 : 9;
        if (n.contains("mantenha") || n.contains("permaneca") || n.contains("fique")) return L ? 43 : R ? 44 : 9;
        if (n.contains("acentuad") || n.contains("fechad")) return L ? 22 : R ? 23 : 9;
        if (n.contains("ligeir") || n.contains("suave") || n.contains("levemente") || n.contains("curva")) return L ? 24 : R ? 25 : 9;
        if (n.trim().startsWith("entre ") || n.contains("convirja") || n.contains("incorpore")) return L ? 4 : R ? 3 : 27;
        if (L) return 20;
        if (R) return 21;
        if (n.contains("em frente") || n.contains("siga") || n.contains("continue") || n.contains("prossiga")) return 9;
        return -1;
    }
}
