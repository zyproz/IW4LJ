package dev.zyproz.legacy.steam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Legacy Mobile: keeps the app running while SteamActivity signs in and downloads. Without it, Android pauses the
 * app as soon as the player switches to the Steam app to approve the sign-in (or leaves during the download), and
 * Steam closes the connection. It does no work itself: it only shows a notification and holds the CPU and Wi-Fi
 * awake until SteamActivity stops it (done, failed or closed).
 */
public class SteamKeepAlive extends Service {
    private static final String CHANNEL = "legacy_steam";
    private static final int NOTIFICATION = 7940;

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    static void start(Context context, String text) {
        try {
            context.startForegroundService(new Intent(context, SteamKeepAlive.class).putExtra("text", text));
        } catch (Throwable ignored) {
            // Not fatal: sign-in still works if the player stays in the app.
        }
    }

    static void stop(Context context) {
        try {
            context.stopService(new Intent(context, SteamKeepAlive.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Steam", NotificationManager.IMPORTANCE_LOW));
        String text = intent != null ? intent.getStringExtra("text") : null;
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("Legacy Mobile - Steam")
                .setContentText(text != null ? text : "Steam sign-in and download in progress")
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        if (wake == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legacy:steam");
            wake.setReferenceCounted(false);
            wake.acquire(6L * 60 * 60 * 1000);
        }
        if (wifi == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legacy:steam");
                wifi.setReferenceCounted(false);
                wifi.acquire();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) {
            wake.release();
        }
        if (wifi != null && wifi.isHeld()) {
            wifi.release();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
