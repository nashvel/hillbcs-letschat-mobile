package com.hillbcs.letschat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.jitsi.meet.sdk.BroadcastIntentHelper;

/**
 * Relays the ongoing-call notification's Hang up button into the Jitsi SDK.
 *
 * <p>Two different buses have to be bridged. A notification action can only carry a
 * {@link android.app.PendingIntent}, which the system delivers, while the SDK listens
 * on {@link LocalBroadcastManager}, which is in-process only. This receiver is the
 * one step between them.
 *
 * <p>Hanging up this way rather than simply cancelling the notification matters:
 * {@code buildHangUpIntent()} leaves the conference properly, so the SDK emits
 * CONFERENCE_TERMINATED and {@link NativeCall} can record the end for the web layer
 * to settle. Cancelling the notification alone would leave the call running and the
 * conversation still showing it as live.
 */
public class CallActionReceiver extends BroadcastReceiver {

    static final String ACTION_HANG_UP = "com.hillbcs.letschat.HANG_UP_CALL";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_HANG_UP.equals(intent.getAction())) {
            return;
        }
        Log.d("HillbcsCall", "hang up requested from the notification");
        LocalBroadcastManager.getInstance(context).sendBroadcast(BroadcastIntentHelper.buildHangUpIntent());
        // Not cancelled here: the SDK's terminate broadcast is what clears it, so the
        // notification disappears when the call is actually over rather than when the
        // request was merely sent.
    }
}
