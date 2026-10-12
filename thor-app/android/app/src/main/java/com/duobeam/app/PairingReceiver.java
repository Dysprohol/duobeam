package com.duobeam.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Wireless debugging pairing flow. The code is normally entered in the app UI (settings can be shown on one screen
 * and DuoBeam on the other); a notification with an inline reply field covers the case when the app is hidden.
 */
public class PairingReceiver extends BroadcastReceiver {

    interface Ui {
        void onPairingState(String text, int port, boolean done);
    }

    static final String ACTION_CODE = "com.duobeam.app.PAIR_CODE";
    private static final String CHANNEL = "pairing";
    private static final String KEY_CODE = "code";
    private static final int NOTIF_ID = 2;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static NsdManager.DiscoveryListener discovery;
    private static volatile int pairingPort;
    static volatile Ui ui;

    static int port() {
        return pairingPort;
    }

    static boolean active() {
        return discovery != null;
    }

    private static void uiState(String text, boolean done) {
        Ui u = ui;
        if (u != null) MAIN.post(() -> u.onPairingState(text, pairingPort, done));
    }

    /** Starts mDNS discovery of the pairing dialog and shows the notification. */
    static void begin(Context c) {
        begin(c, true);
    }

    /** announce=false skips the "searching" notification (silent discovery, e.g. on app open). */
    static synchronized void begin(Context c, boolean announce) {
        Context app = c.getApplicationContext();
        L.load(app);
        app.getSystemService(NotificationManager.class)
                .createNotificationChannel(new NotificationChannel(CHANNEL, L.s("pair_channel"), NotificationManager.IMPORTANCE_HIGH));
        pairingPort = 0;
        if (discovery != null) AdbLauncher.stopDiscovery(app, discovery);
        if (announce) notify(app, L.s("pair_searching"), L.s("setup_steps"), false);
        uiState(L.s("pair_searching"), false);
        discovery = AdbLauncher.discoverPairingPort(app, port -> {
            if (port == pairingPort) return;
            pairingPort = port;
            notify(app, L.s("pair_notif_title"), L.s("pair_notif_text"), true);
            uiState(L.s("pair_found"), false);
        });
    }

    static synchronized void end(Context c) {
        if (discovery != null) {
            AdbLauncher.stopDiscovery(c.getApplicationContext(), discovery);
            discovery = null;
        }
    }

    static void clearNotification(Context c) {
        c.getApplicationContext().getSystemService(NotificationManager.class).cancel(NOTIF_ID);
    }

    /** Pairs in the background, then connects and self-grants the permission to toggle wireless debugging. */
    static void pairAsync(Context c, int port, String code) {
        Context app = c.getApplicationContext();
        notify(app, L.s("pairing"), "", false);
        uiState(L.s("pairing"), false);
        new Thread(() -> {
            try {
                if (port <= 0) throw new IllegalStateException(L.s("pair_no_port"));
                AdbLauncher.pair(app, port, code);
                end(app);
                AdbLauncher.connect(app);
                AdbLauncher.selfGrant(app);
                new Settings(app).setSetupInProgress(false);
                String msg = L.s("pair_ok");
                notify(app, msg, AdbLauncher.canToggleWirelessDebugging(app) ? L.s("setup_done") : "", false);
                MAIN.postDelayed(() -> clearNotification(app), 5000); // brief confirmation, then dismiss
                uiState(msg, true);
                StreamClient.get(app).refreshBackend();
            } catch (Exception e) {
                String msg = L.f("pair_fail", e.getMessage());
                notify(app, L.s("pair_notif_title"), msg, true);
                uiState(msg, false);
            }
        }, "DuoBeamPair").start();
    }

    private static void notify(Context c, String title, String text, boolean withInput) {
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("DuoBeam: " + title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text));
        if (withInput) {
            RemoteInput input = new RemoteInput.Builder(KEY_CODE).setLabel(L.s("pair_code")).build();
            PendingIntent pi = PendingIntent.getBroadcast(c, 1, new Intent(c, PairingReceiver.class).setAction(ACTION_CODE),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            b.addAction(new Notification.Action.Builder(null, L.s("pair_notif_action"), pi).addRemoteInput(input).build());
        }
        c.getSystemService(NotificationManager.class).notify(NOTIF_ID, b.build());
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle r = RemoteInput.getResultsFromIntent(intent);
        CharSequence code = r != null ? r.getCharSequence(KEY_CODE) : null;
        L.load(context);
        if (code != null) pairAsync(context, pairingPort, code.toString());
    }
}
