package com.duobeam.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Optional start on boot: hands off to ControlService, which enables wireless debugging and starts streaming once the receiver is reachable. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!new Settings(context).bootStart()) return;
        context.startForegroundService(new Intent(context, ControlService.class).setAction(ControlService.ACTION_BOOT));
    }
}
