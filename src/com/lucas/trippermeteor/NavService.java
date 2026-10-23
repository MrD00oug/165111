package com.lucas.trippermeteor;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/** Serviço em primeiro plano: mantém GPS + Bluetooth rodando com a tela apagada durante a navegação. */
public class NavService extends Service implements LocationListener {

    private static final String CH = "nav";
    private static final int ID = 7;
    private static final String ACTION_STOP = "parar";
    private static NavService running;

    private LocationManager lm;
    private PowerManager.WakeLock wl;

    public static void start(Context c) {
        Intent i = new Intent(c, NavService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, NavService.class));
    }

    public static boolean isRunning() { return running != null; }

    public static void update(Context c, Navigator.Status s) {
        NavService svc = running;
        if (svc == null || s == null) return;
        String title = s.title == null || s.title.isEmpty() ? "Navegando" : s.title;
        if (s.dist > 0) title += " · " + Navigator.niceDist(s.dist);
        String text = s.radar != null && !s.radar.isEmpty() ? s.radar : (s.bottom == null ? "" : s.bottom);
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        nm.notify(ID, svc.build(title, text));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Navegação", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = build("Tripper Meteor", "Navegação ativa");
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } catch (Exception e) {
                try {
                    startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                } catch (Exception e2) {
                    Core.get(this).onLog("não consegui rodar em segundo plano: " + e2.getMessage(), Pod.LOG_ERR);
                }
            }
        } else startForeground(ID, n);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TripperMeteor:nav");
        wl.acquire(6 * 60 * 60 * 1000L);

        startGps();
        Core.get(this).pod.persistent = true;
    }

    @SuppressLint("MissingPermission")
    private void startGps() {
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null && System.currentTimeMillis() - last.getTime() < 120000) onLocationChanged(last);
        } catch (Exception e) {
            Core.get(this).onLog("GPS indisponível: " + e.getMessage(), Pod.LOG_ERR);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Core c = Core.get(this);
            c.bridge.setEnabled(false);
            c.nav.stop();
            c.finished();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    Notification build(String title, String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pStop = PendingIntent.getService(this, 1, new Intent(this, NavService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pOpen)
                .addAction(new Notification.Action.Builder(null, "Parar navegação", pStop).build());
        return b.build();
    }

    @Override
    public void onLocationChanged(Location l) {
        Core.get(this).onLocation(l.getLatitude(), l.getLongitude(), l.hasSpeed() ? l.getSpeed() : 0,
                l.hasBearing() ? l.getBearing() : -1, l.hasAccuracy() ? l.getAccuracy() : 50);
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {
        Core.get(this).onLog("GPS desligado: ligue a Localização", Pod.LOG_ERR);
    }

    @Override
    public void onDestroy() {
        running = null;
        try { if (lm != null) lm.removeUpdates(this); } catch (Exception ignored) {}
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) {}
        Core.get(this).pod.persistent = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
