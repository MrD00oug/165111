package com.lucas.trippermeteor;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Protocolo do Tripper pod (Super Meteor 650), formato usado pelo app oficial 12.10.
 * Todo pacote tem 20 bytes: 18 de dados + CRC-16/CCITT-FALSE (big-endian).
 */
public final class Proto {
    private Proto() {}

    public static final UUID SERVICE = UUID.fromString("01FF0100-BA5E-F4EE-5CA1-EB1E5E4B1CE0");
    public static final UUID CHAR = UUID.fromString("01FF0101-BA5E-F4EE-5CA1-EB1E5E4B1CE0");

    /** Ícones que o pod já tem gravados (byte 2 do pacote de navegação). */
    public static final Map<Integer, String> ICONS = new LinkedHashMap<>();
    static {
        ICONS.put(0, "Destino");
        ICONS.put(1, "Destino à esquerda");
        ICONS.put(2, "Destino à direita");
        ICONS.put(3, "Entrar à direita (merge)");
        ICONS.put(4, "Entrar à esquerda (merge)");
        ICONS.put(5, "Bifurcação à direita");
        ICONS.put(6, "Bifurcação à esquerda");
        ICONS.put(7, "Saída à direita");
        ICONS.put(8, "Saída à esquerda");
        ICONS.put(9, "Seguir reto");
        ICONS.put(10, "Rotatória (horário)");
        ICONS.put(20, "Virar à esquerda");
        ICONS.put(21, "Virar à direita");
        ICONS.put(22, "Esquerda acentuada");
        ICONS.put(23, "Direita acentuada");
        ICONS.put(24, "Leve à esquerda");
        ICONS.put(25, "Leve à direita");
        ICONS.put(26, "Retorno (horário)");
        ICONS.put(27, "Convergir");
        ICONS.put(28, "Recalculando rota");
        ICONS.put(29, "Acesso à direita");
        ICONS.put(30, "Acesso à esquerda");
        ICONS.put(31, "Acesso leve à direita");
        ICONS.put(32, "Acesso leve à esquerda");
        ICONS.put(33, "Acesso acentuado à direita");
        ICONS.put(34, "Acesso acentuado à esquerda");
        ICONS.put(43, "Manter à esquerda");
        ICONS.put(44, "Manter à direita");
        ICONS.put(45, "Saída leve à direita");
        ICONS.put(46, "Saída leve à esquerda");
        ICONS.put(47, "Saída mantendo à direita");
        ICONS.put(48, "Saída mantendo à esquerda");
        ICONS.put(49, "Rotatória (anti-horário)");
        ICONS.put(60, "Partida");
        ICONS.put(61, "Retorno (anti-horário)");
        ICONS.put(62, "Balsa");
        ICONS.put(63, "Trem");
        ICONS.put(66, "Sem dados móveis");
        ICONS.put(68, "Bateria fraca");
    }

    public static String iconName(int v) {
        String n = ICONS.get(v);
        if (n != null) return n;
        if (v >= 11 && v <= 19) return "Rotatória horário, saída " + (v - 10) + "?";
        if (v >= 50 && v <= 58) return "Rotatória anti-horário, saída " + (v - 49) + "?";
        return "desconhecido";
    }

    // ---------- CRC e frame ----------

    public static int crc16(byte[] d, int len) {
        int c = 0xFFFF;
        for (int i = 0; i < len; i++) {
            c ^= (d[i] & 0xFF) << 8;
            for (int k = 0; k < 8; k++) {
                c = ((c & 0x8000) != 0) ? ((c << 1) ^ 0x1021) & 0xFFFF : (c << 1) & 0xFFFF;
            }
        }
        return c;
    }

    /** Monta um frame de 20 bytes a partir de até 18 bytes de dados. */
    public static byte[] frame(int... bytes) {
        byte[] f = new byte[20];
        for (int i = 0; i < bytes.length && i < 18; i++) f[i] = (byte) bytes[i];
        int c = crc16(f, 18);
        f[18] = (byte) (c >> 8);
        f[19] = (byte) c;
        return f;
    }

    public static byte[] frameRaw(byte[] data) {
        int[] v = new int[Math.min(18, data.length)];
        for (int i = 0; i < v.length; i++) v[i] = data[i] & 0xFF;
        return frame(v);
    }

    // ---------- comandos ----------

    /** 21 00 = retomar (celular já autorizado); 21 01 = pedir PIN. */
    public static byte[] handshake(boolean known) {
        return frame(0x21, known ? 0x00 : 0x01);
    }

    public static boolean isResume(byte[] v) {
        return v != null && v.length >= 20 && v[0] == 0x21 && v[1] == 0x00 && v[18] == 0x40 && v[19] == 0x45;
    }

    /** PIN: 0x20 + até 6 dígitos ASCII. */
    public static byte[] pin(String digits) {
        int[] v = new int[1 + Math.min(6, digits.length())];
        v[0] = 0x20;
        for (int i = 1; i < v.length; i++) v[i] = digits.charAt(i - 1);
        return frame(v);
    }

    /** Hora: byte1 = hora (6 bits) | formato << 6 (0 = 24h); byte2 = minutos. */
    public static byte[] time() {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
        return frame(0x50, (h & 63), m & 0xFF);
    }

    public static final byte[] PING_FW = frame(0x03);

    /** Comando 0x40 com sub-tela (o app oficial usa 1..5: ligação, alertas…). */
    public static byte[] ctrl(int sub) { return frame(0x40, sub & 0xFF); }

    // ---------- navegação (igual ao app 12.10) ----------

    /** Arredondamento do app oficial: até 100 m de 10 em 10, acima de 50 em 50 (para cima). */
    public static int roundOfficial(int m) {
        if (m < 0) return 0;
        if (m <= 100) return ((m + 9) / 10) * 10;
        return ((m + 49) / 50) * 50;
    }

    /** Distância -> {valor, unidade}. Unidade 1 = metros, 2 = centenas de metros (mostra km com 1 casa). */
    public static int[] encodeDist(int m) {
        if (m > 999) return new int[]{m / 100, 2};
        return new int[]{m, 1};
    }

    /**
     * Nível de "enchimento" da seta (byte 6, nibble alto), como o app oficial calcula:
     * >500 m = 5, 101..500 = 6, 81..100 = 0, 61..80 = 1, 41..60 = 2, 21..40 = 3, 0..20 = 4.
     */
    public static int progress(int m) {
        if (m <= 20) return 4;
        if (m <= 40) return 3;
        if (m <= 60) return 2;
        if (m <= 80) return 1;
        if (m <= 100) return 0;
        if (m <= 500) return 6;
        return 5;
    }

    public static final int BOTTOM_NONE = 0, BOTTOM_ARRIVAL = 1, BOTTOM_TOTAL = 2;

    /**
     * Pacote de navegação.
     * @param icon      ícone principal (byte 2)
     * @param distM     distância até a manobra, já arredondada como você quiser
     * @param progress  0..6 (ver progress()); -1 = calcular pela distância
     * @param nextIcon  próxima manobra, -1 = nenhuma
     * @param alert     nibble baixo do byte 10 (o app usa 1 normal, 2 = dentro do raio de alerta)
     * @param bottom    BOTTOM_NONE / BOTTOM_ARRIVAL (hora de chegada) / BOTTOM_TOTAL (distância total)
     * @param etaMin    minutos até chegar (para BOTTOM_ARRIVAL)
     * @param totalM    distância total restante (para BOTTOM_TOTAL)
     * @param night     modo noite
     */
    public static byte[] nav(int icon, int distM, int progress, int nextIcon, int alert,
                             int bottom, int etaMin, int totalM, boolean night) {
        int[] d = encodeDist(distM);
        int pr = progress < 0 ? progress(distM) : progress;
        int[] p = new int[18];
        p[0] = 0x10;
        p[1] = 0x11;
        p[2] = icon & 0xFF;
        p[3] = (d[0] >> 8) & 0xFF;
        p[4] = d[0] & 0xFF;
        p[5] = d[1];
        p[6] = ((pr & 15) << 4) | (night ? 1 : 0);
        p[7] = nextIcon < 0 ? 0xFF : nextIcon & 0xFF;
        p[8] = p[3];
        p[9] = p[4];
        p[10] = ((d[1] & 15) << 4) | (alert & 15);
        if (bottom == BOTTOM_ARRIVAL) {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.MINUTE, Math.max(0, etaMin));
            p[11] = c.get(Calendar.HOUR_OF_DAY) & 63; // formato 24h (bits altos = 0)
            p[12] = c.get(Calendar.MINUTE);
            p[13] = 0;
        } else if (bottom == BOTTOM_TOTAL) {
            int[] t = encodeDist(totalM);
            p[11] = (t[0] >> 8) & 0xFF;
            p[12] = t[0] & 0xFF;
            p[13] = t[1];
        }
        return frame(p);
    }

    /** Tela de espera que o app oficial manda quando não está navegando. */
    public static byte[] idle(int icon, boolean night) {
        return frame(0x10, 0x11, icon & 0xFF, 0, 0, 1, (night ? 1 : 0), 0xFF, 0, 0, 0, 0, 0, 0);
    }

    public static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }

    public static byte[] parseHex(String s) {
        String t = s.replaceAll("(?i)0x", " ").replaceAll("[^0-9A-Fa-f]", " ").trim();
        if (t.isEmpty()) return new byte[0];
        String[] parts;
        if (t.contains(" ")) parts = t.split("\\s+");
        else {
            parts = new String[(t.length() + 1) / 2];
            for (int i = 0; i < parts.length; i++) parts[i] = t.substring(i * 2, Math.min(t.length(), i * 2 + 2));
        }
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = (byte) Integer.parseInt(parts[i], 16);
        return out;
    }
}
