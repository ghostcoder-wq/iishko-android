package com.iishko.selfhost;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class ServerService extends Service {
    private static final String CHANNEL = "iishko-server";
    private static final int NOTIF_ID = 1001;

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        try {
            startForeground(NOTIF_ID, buildNotification());
        } catch (Throwable t) {
            ServerRunner.log(this, "startForeground failed: " + t);
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IISHKO:server");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Throwable t) {
            ServerRunner.log(this, "wakeLock failed: " + t);
        }
        ServerRunner.start(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ServerRunner.start(this);
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        scheduleRestart();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
        scheduleRestart();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            Class<?> channelClass = Class.forName("android.app.NotificationChannel");
            Constructor<?> ctor = channelClass.getConstructor(String.class, CharSequence.class, int.class);
            Object channel = ctor.newInstance(CHANNEL, "IISHKO server", 2);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Method create = NotificationManager.class.getMethod("createNotificationChannel", channelClass);
            create.invoke(nm, channel);
        } catch (Throwable ignored) {}
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Constructor<Notification.Builder> ctor =
                        Notification.Builder.class.getConstructor(Context.class, String.class);
                b = ctor.newInstance(this, CHANNEL);
            } catch (Throwable t) {
                b = new Notification.Builder(this);
            }
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("IISHKO работает")
                .setContentText("Локальный сервер активен")
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    private void scheduleRestart() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            Intent i = new Intent(this, RestartReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(this, 100, i, flags);
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pi);
        } catch (Throwable ignored) {}
    }
}
