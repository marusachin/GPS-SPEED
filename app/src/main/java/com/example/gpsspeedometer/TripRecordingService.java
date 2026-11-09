package com.example.gpsspeedometer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.app.PendingIntent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Keeps the user-started GPS trip session alive while the screen is off. */
public class TripRecordingService extends Service {
    private static final String CHANNEL_ID = "trip_recording";
    private static final int NOTIFICATION_ID = 28;
    public static final String ACTION_UPDATE = "com.example.gpsspeedometer.UPDATE_TRIP_NOTIFICATION";

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = buildNotification("Trip recording is active");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_UPDATE.equals(intent.getAction())) {
            double km = intent.getDoubleExtra("distance_km", 0);
            double speed = intent.getDoubleExtra("speed_kmh", 0);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(
                String.format(java.util.Locale.getDefault(), "Trip %.2f km • %.0f km/h", km, speed)));
        }
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Trip recording", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while GPS trip recording continues in the background.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String detail) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        return builder.setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("GPS Speedometer")
            .setContentText(detail)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build();
    }

    @Override public void onDestroy() {
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
