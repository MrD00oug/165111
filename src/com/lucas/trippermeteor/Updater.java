package com.lucas.trippermeteor;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Atualização direto do GitHub: lê a última release do repositório, baixa o APK
 * (nome "TripperMeteor-<versão>-b<build>.apk") e instala pelo instalador do Android.
 */
public final class Updater {
    private Updater() {}

    public static class Release {
        public String tag, name, notes, apkUrl;
        public int build;
        public long size;
    }

    public interface Progress { void on(String msg); }

    public static int currentBuild(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    public static String currentName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    /** Última release (chamar fora da main thread). */
    public static Release latest(String repo) throws Exception {
        String body = Router.get("https://api.github.com/repos/" + repo + "/releases/latest", Router.UA);
        JSONObject o = new JSONObject(body);
        Release r = new Release();
        r.tag = o.optString("tag_name", "");
        r.name = o.optString("name", r.tag);
        r.notes = o.optString("body", "");
        JSONArray as = o.optJSONArray("assets");
        if (as != null) for (int i = 0; i < as.length(); i++) {
            JSONObject a = as.getJSONObject(i);
            String n = a.optString("name", "");
            if (n.endsWith(".apk")) {
                r.apkUrl = a.optString("browser_download_url");
                r.size = a.optLong("size", 0);
                Matcher m = Pattern.compile("-b(\\d+)\\.apk$").matcher(n);
                if (m.find()) r.build = Integer.parseInt(m.group(1));
            }
        }
        if (r.apkUrl == null) throw new Exception("a release " + r.tag + " não tem APK");
        return r;
    }

    /** O Android deixa este app instalar atualizações? Se não, abre a tela pra liberar. */
    public static boolean canInstall(Activity a) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return false;
        }
        return true;
    }

    /** Baixa e entrega pro instalador (chamar fora da main thread). */
    public static void install(Context c, Release r, Progress p) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            HttpURLConnection con = (HttpURLConnection) new URL(r.apkUrl).openConnection();
            con.setInstanceFollowRedirects(true);
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setRequestProperty("User-Agent", Router.UA);
            // o GitHub redireciona para outro domínio; segue na mão se precisar
            int code = con.getResponseCode();
            int hops = 0;
            while (code >= 300 && code < 400 && hops++ < 5) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                con = (HttpURLConnection) new URL(new URL(r.apkUrl), loc).openConnection();
                con.setRequestProperty("User-Agent", Router.UA);
                code = con.getResponseCode();
            }
            if (code >= 400) throw new Exception("download falhou (HTTP " + code + ")");
            long total = con.getContentLengthLong() > 0 ? con.getContentLengthLong() : r.size;
            try (InputStream in = con.getInputStream(); OutputStream out = s.openWrite("tripper.apk", 0, total > 0 ? total : -1)) {
                byte[] buf = new byte[65536];
                long done = 0, lastPct = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        long pct = done * 100 / total;
                        if (pct / 10 != lastPct / 10) { lastPct = pct; p.on("Baixando… " + pct + "%"); }
                    }
                }
                s.fsync(out);
            }
            p.on("Instalando…");
            Intent i = new Intent(c, InstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pend = PendingIntent.getBroadcast(c, id, i, flags);
            s.commit(pend.getIntentSender());
        }
    }
}
