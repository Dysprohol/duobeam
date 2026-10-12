package com.duobeam.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Foreground service that lives while a stream is active: status notification (Stop, next layout, side bar)
 * and the optional overlay side bar. The stream itself runs in the shell-uid sender process; this service only controls it.
 */
public class ControlService extends Service implements StreamClient.Listener {

    private static final String CHANNEL = "stream";
    private static final int NOTIF_ID = 1;
    static final String ACTION_STOP = "com.duobeam.app.STOP";
    static final String ACTION_NEXT = "com.duobeam.app.NEXT_LAYOUT";
    static final String ACTION_BOOT = "com.duobeam.app.BOOT";
    static final String ACTION_BAR = "com.duobeam.app.SIDE_BAR";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private StreamClient client;
    private SideBar sideBar;
    private boolean sawStreaming;
    private final long createdAt = System.currentTimeMillis();

    static void ensureRunning(Context c) {
        c.startForegroundService(new Intent(c, ControlService.class));
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            boolean streaming = client.isStreaming();
            if (streaming) sawStreaming = true;
            if (sawStreaming && !streaming) { // stream ended (e.g. stopped from the app)
                stopSelf();
                return;
            }
            if (!sawStreaming && System.currentTimeMillis() - createdAt > 180_000) { // stream never started (e.g. boot start with no receiver)
                stopSelf();
                return;
            }
            updateNotification();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        client = StreamClient.get(this);
        client.addListener(this);
        sideBar = new SideBar(this, client);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, L.s("notif_channel"), NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIF_ID, buildNotification());
        handler.postDelayed(tick, 1500);
        sideBar.update();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(a)) {
            client.stop();
        } else if (ACTION_NEXT.equals(a)) {
            client.nextLayout();
        } else if (ACTION_BAR.equals(a)) {
            if (Settings.canDrawOverlays(this)) {
                client.setOverlay(!client.settings().overlay());
            } else { // no overlay permission: open the app so it can request it
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } else if (ACTION_BOOT.equals(a)) {
            // After boot: wait for Wi-Fi (required by wireless debugging), then start the sender.
            final long requested = System.currentTimeMillis();
            new Thread(() -> {
                android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
                for (int i = 0; i < 120; i++) {
                    android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
                    if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) break;
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
                }
                // Skip if already streaming or if Stop was pressed while waiting.
                handler.post(() -> {
                    if (!client.isStreaming() && client.lastStopAt() < requested) client.start();
                });
            }, "DuoBeamBoot").start();
        }
        sideBar.update();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        client.removeListener(this);
        sideBar.remove();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onChanged() {
        updateNotification();
        sideBar.update();
    }

    private PendingIntent action(String a) {
        return PendingIntent.getService(this, a.hashCode(), new Intent(this, ControlService.class).setAction(a), PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        String tvl = client.tvLine();
        String s = client.humanStatus() + (tvl.isEmpty() ? "" : " | " + tvl);
        boolean two = com.duobeam.app.Settings.twoScreens(this);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(two ? "DuoBeam - " + client.settings().layoutName() : "DuoBeam")
                .setContentText(s)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, L.s("stop"), action(ACTION_STOP)).build());
        if (two) b.addAction(new Notification.Action.Builder(null, L.s("notif_next"), action(ACTION_NEXT)).build());
        return b.addAction(new Notification.Action.Builder(null, L.s("notif_bar"), action(ACTION_BAR)).build()).build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
    }

}
