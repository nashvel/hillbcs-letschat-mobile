package com.hillbcs.letschat;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * Creates the notification channels that pushes from the server name.
 *
 * From Android 8 (API 26) a notification whose channel does not exist is
 * discarded silently — no error, no log from the app, nothing in the tray. The
 * server always sets {@code android.notification.channel_id}, so the channels
 * have to exist before the first push arrives.
 *
 * <p>The web layer also creates them, via {@code PushNotifications.createChannel}
 * in nativePush.ts, but only once the user has signed in and granted permission.
 * That leaves a window where a push is deliverable and its channel is not yet
 * defined: a notification arriving on first launch, after app data is cleared, or
 * while the app has never been opened since install. Creating them here, at
 * process start, removes the window. Doing it in both places is deliberate
 * belt-and-braces, and harmless because channel creation is idempotent.
 *
 * <p>Note what the system will and will not let us change afterwards: importance
 * and sound are honoured on first creation only. Once a channel exists, calling
 * this again cannot lower or raise its importance — that is the user's to
 * control from Android settings, and re-creating with a different importance is
 * ignored rather than applied. Names and descriptions do update.
 */
final class PushChannels {
    private static final String TAG = "HillbcsShell";

    private PushChannels() {
    }

    /**
     * Declares both channels. Safe to call on every launch and on any API level.
     */
    static void ensure(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Pre-26 has no channels; importance comes from the notification.
            return;
        }

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        try {
            manager.createNotificationChannel(
                channel(
                    context.getString(R.string.push_channel_messages),
                    context.getString(R.string.push_channel_messages_name),
                    context.getString(R.string.push_channel_messages_description)
                )
            );
            manager.createNotificationChannel(
                channel(
                    context.getString(R.string.push_channel_calls),
                    context.getString(R.string.push_channel_calls_name),
                    context.getString(R.string.push_channel_calls_description)
                )
            );
        } catch (Exception e) {
            // A failure here costs notifications, not stability, so it must not
            // take the launch down with it.
            Log.w(TAG, "Could not create push notification channels", e);
        }
    }

    private static NotificationChannel channel(String id, String name, String description) {
        // IMPORTANCE_HIGH so messages and calls surface as a heads-up banner and
        // make a sound. Matches the importance: 5 the web layer passes.
        NotificationChannel channel = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(description);
        channel.enableVibration(true);
        // Shown on the lock screen: a chat app whose notifications are hidden
        // there is not much use, and the user can still override per channel.
        channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        return channel;
    }
}
