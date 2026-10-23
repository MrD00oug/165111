package com.lucas.trippermeteor;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Lê a notificação de navegação do Google Maps. Quando o texto não diz a direção,
 * usa o desenho do ícone (aprende sozinho: cada vez que o texto diz, guarda o desenho).
 */
public class MapsListener extends NotificationListenerService {

    public static final String MAPS = "com.google.android.apps.maps";
    public static final String WAZE = "com.waze";

    static boolean isRadarApp(String pkg) { return pkg.startsWith("com.vialsoft.radarbot"); }
    static boolean watched(String pkg) { return MAPS.equals(pkg) || WAZE.equals(pkg) || isRadarApp(pkg); }
    private boolean navOn = false;
    private String radarKey;

    public static boolean isAllowed(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(new ComponentName(c, MapsListener.class).flattenToString());
    }

    @Override
    public void onListenerConnected() {
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) for (StatusBarNotification sbn : all) onNotificationPosted(sbn);
        } catch (Exception ignored) {}
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !watched(sbn.getPackageName())) return;
        String pkg = sbn.getPackageName();
        Notification n = sbn.getNotification();
        Bundle ex = n.extras;
        String title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
        String big = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String sub = str(ex.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String info = str(ex.getCharSequence(Notification.EXTRA_INFO_TEXT));
        String summary = str(ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
        if (text.isEmpty() && !big.isEmpty()) text = big;
        String meta = (sub + " " + info + " " + summary).trim();
        boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        boolean navCat = Notification.CATEGORY_NAVIGATION.equals(n.category);

        Core core = Core.get(this);
        String app = MAPS.equals(pkg) ? "MAPS" : WAZE.equals(pkg) ? "WAZE" : "RADARBOT";

        // alerta de radar de outro app (Radarbot; Waze se vier no texto)
        if (!MAPS.equals(pkg)) {
            Object[] ra = MapsParser.radarAlert(title, text, meta);
            core.capture(String.format(Locale.US, "%s %s | id=%d cat=%s ongoing=%s | título=[%s] texto=[%s] big=[%s] sub=[%s] info=[%s] resumo=[%s] | -> %s",
                    new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()), app, sbn.getId(), n.category, ongoing, title, text,
                    big.equals(text) ? "=" : big, sub, info, summary,
                    ra == null ? "não é alerta" : ("alerta " + ra[2] + " " + ra[0] + " m, limite " + ra[1])));
            if (ra != null && core.set.useRadarbot) {
                RadarStore.External old = core.radars.external;
                RadarStore.External e = new RadarStore.External();
                e.dist = (Integer) ra[0];
                e.limit = (Integer) ra[1];
                e.kind = (String) ra[2];
                e.at = System.currentTimeMillis();
                // mesmo alerta continuando? mantém o controle de voz/bipe
                if (old != null && e.at - old.at < 20000) e.radar = old.radar;
                e.radar.maxspeed = e.limit;
                core.radars.external = e;
                radarKey = sbn.getKey();
            } else if (ra == null && sbn.getKey().equals(radarKey)) {
                radarKey = null;
                core.radars.external = null; // a mesma notificação voltou ao normal: alerta acabou
            }
            return;
        }

        MapsParser.Instr i = MapsParser.parse(title, text, meta);
        String hash = iconHash(n);
        String cap = String.format(Locale.US, "%s MAPS | cat=%s ongoing=%s | título=[%s] texto=[%s] big=[%s] sub=[%s] info=[%s] resumo=[%s] | ícone=%s | -> %s",
                new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()), n.category, ongoing, title, text,
                big.equals(text) ? "=" : big, sub, info, summary, hash, i);
        core.capture(cap);

        boolean isNav = navCat || (ongoing && i.distM >= 0);
        if (!isNav) return;

        // ícone: texto primeiro; se o texto não disser, usa o desenho aprendido
        if (hash != null) {
            SharedPreferences p = getSharedPreferences("icones_maps", MODE_PRIVATE);
            if (i.icon >= 0) p.edit().putInt(hash, i.icon).apply();
            else {
                int learned = nearest(p, hash);
                if (learned >= 0) i.icon = learned;
            }
        }
        navOn = true;
        core.bridge.onMaps(i);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        if (radarKey != null && radarKey.equals(sbn.getKey())) {
            radarKey = null;
            Core.get(this).radars.external = null; // alerta do Radarbot acabou
            return;
        }
        if (!MAPS.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (navOn && (Notification.CATEGORY_NAVIGATION.equals(n.category) || (n.flags & Notification.FLAG_ONGOING_EVENT) != 0)) {
            navOn = false;
            Core.get(this).bridge.onMapsEnded();
        }
    }

    private static String str(CharSequence c) { return c == null ? "" : c.toString().trim(); }

    // ---------------- desenho do ícone -> hash de 64 bits ----------------

    private String iconHash(Notification n) {
        try {
            Bitmap bmp = null;
            Object o = n.extras.get(Notification.EXTRA_LARGE_ICON);
            if (o instanceof Bitmap) bmp = (Bitmap) o;
            Icon ic = null;
            if (bmp == null) ic = n.getLargeIcon();
            if (bmp == null && ic != null) bmp = draw(ic.loadDrawable(this));
            if (bmp == null) return null;
            Bitmap s = Bitmap.createScaledBitmap(bmp, 8, 8, true);
            int[] lum = new int[64];
            long sum = 0;
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                int c = s.getPixel(x, y);
                int a = (c >>> 24) & 0xFF;
                int l = ((((c >> 16) & 0xFF) * 30 + ((c >> 8) & 0xFF) * 59 + (c & 0xFF) * 11) / 100) * a / 255;
                lum[y * 8 + x] = l;
                sum += l;
            }
            long avg = sum / 64, bits = 0;
            for (int k = 0; k < 64; k++) if (lum[k] > avg) bits |= 1L << k;
            return String.format("%016x", bits);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap draw(Drawable d) {
        if (d == null) return null;
        int w = Math.max(1, d.getIntrinsicWidth()), hgt = Math.max(1, d.getIntrinsicHeight());
        Bitmap b = Bitmap.createBitmap(Math.min(w, 256), Math.min(hgt, 256), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, b.getWidth(), b.getHeight());
        d.draw(c);
        return b;
    }

    private static int nearest(SharedPreferences p, String hash) {
        long h = Long.parseUnsignedLong(hash, 16);
        int best = -1, bestD = 9; // até 8 bits diferentes
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            try {
                int d = Long.bitCount(h ^ Long.parseUnsignedLong(e.getKey(), 16));
                if (d < bestD) { bestD = d; best = (Integer) e.getValue(); }
            } catch (Exception ignored) {}
        }
        return best;
    }
}
