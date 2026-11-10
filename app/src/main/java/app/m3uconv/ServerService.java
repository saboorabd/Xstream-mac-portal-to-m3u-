package app.m3uconv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import java.io.IOException;

/**
 * Foreground service: keeps the local playlist server (127.0.0.1) alive while your IPTV player is open,
 * even when this app is in the background.
 */
public class ServerService extends Service {
    private static final String CHANNEL = "server";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Playlist server",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("M3U Converter")
                .setContentText("Playlist server running on this phone")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        new Thread(() -> {
            try {
                ServerHolder.ensure(ServerService.this);
            } catch (IOException ignored) {
                // MainActivity shows the error if the server cannot start
            }
        }).start();
        return START_STICKY;
    }
}
