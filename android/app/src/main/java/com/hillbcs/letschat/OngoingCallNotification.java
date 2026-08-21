package com.hillbcs.letschat;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.jitsi.meet.sdk.JitsiMeetActivity;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The ongoing-call notification, in the shape Android promotes to a status bar chip.
 *
 * <p>This exists because the Jitsi SDK's own notification cannot be that shape.
 * {@code OngoingNotification} builds a plain notification — its title and text come
 * from static string resources, it never calls {@code setLargeIcon}, and it applies
 * no style — so it cannot carry a conversation name, an avatar, or the call
 * treatment the system reserves for {@link NotificationCompat.CallStyle}. Its
 * builder takes only {@code (isMuted, context, tapBackActivity)}, so there is no
 * per-call value to hand it either.
 *
 * <p>Rather than fight it, this posts a second notification that is CallStyle, and
 * the SDK's is pushed out of the way by pre-creating its channel quiet: see
 * {@link #createChannels(Context)}. The result is one prominent call entry, with the
 * conversation's name and picture, that the platform surfaces in the status bar and
 * expands to Hang up / return controls.
 *
 * <p>Hanging up goes through {@code BroadcastIntentHelper.buildHangUpIntent()} via
 * {@link CallActionReceiver}, so the conference is left the same way the in-call
 * button leaves it — including the CONFERENCE_TERMINATED broadcast the app relies on
 * to write its "ended a call" message.
 */
final class OngoingCallNotification {

    private static final String TAG = "HillbcsCall";

    /** Ours, distinct from the SDK's randomised id, so the two never collide. */
    private static final int NOTIFICATION_ID = 20261;

    private static final String CHANNEL_ID = "hillbcs-ongoing-call";

    /**
     * The SDK's channel id, a compile-time constant in {@code OngoingNotification}.
     *
     * Hardcoded rather than read reflectively because it is exactly that: a constant
     * inlined at compile time, so there is no field left to read.
     */
    private static final String SDK_CHANNEL_ID = "JitsiOngoingConferenceChannel";

    private static final int AVATAR_TIMEOUT_MS = 5000;

    /** Kept so the chronometer does not restart when the avatar arrives and re-posts. */
    private static volatile long startedAt = 0L;

    private OngoingCallNotification() {}

    /**
     * Creates our channel, and claims the SDK's at minimum importance.
     *
     * <p>Claiming it first is the whole trick. An app may lower a channel's
     * importance but never raise it, so whoever creates the channel first sets its
     * ceiling. Creating {@code JitsiOngoingConferenceChannel} as IMPORTANCE_MIN
     * before the SDK gets to it leaves the SDK's notification present — it has to
     * be, it is a foreground service — but silent and collapsed, rather than
     * competing with the call entry below.
     *
     * <p>Must therefore run before any call starts, which is why MainActivity calls
     * it during onCreate.
     */
    static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        NotificationChannel ongoing = new NotificationChannel(
            CHANNEL_ID,
            "Ongoing calls",
            NotificationManager.IMPORTANCE_HIGH
        );
        ongoing.setDescription("Shows the call you are currently in.");
        // An ongoing call should be prominent, not noisy: the ring happens
        // elsewhere, this is only the "you are in a call" surface.
        ongoing.setSound(null, null);
        ongoing.enableVibration(false);
        ongoing.setShowBadge(false);
        manager.createNotificationChannel(ongoing);

        NotificationChannel sdk = new NotificationChannel(
            SDK_CHANNEL_ID,
            "Call service",
            NotificationManager.IMPORTANCE_MIN
        );
        sdk.setDescription("Keeps a call running in the background.");
        sdk.setSound(null, null);
        sdk.enableVibration(false);
        sdk.setShowBadge(false);
        manager.createNotificationChannel(sdk);
    }

    /**
     * Shows the call, named for the conversation.
     *
     * @param title conversation or group name; the call is named for the
     *     conversation rather than a participant, matching where the user started it
     * @param avatarUrl conversation picture, fetched off the main thread and applied
     *     in a second post when it lands. Text first, picture later, because a
     *     notification that waits on the network is a notification that shows up
     *     after the user has already wondered where it is.
     */
    static void show(Context context, String title, String avatarUrl, boolean video) {
        final String name = title == null || title.trim().isEmpty() ? "Let's Chat" : title.trim();
        startedAt = System.currentTimeMillis();
        post(context, name, null, video);

        if (avatarUrl == null || avatarUrl.trim().isEmpty()) {
            return;
        }
        final String url = avatarUrl.trim();
        new Thread(() -> {
            Bitmap avatar = downloadBitmap(url);
            if (avatar != null) {
                post(context, name, avatar, video);
            }
        }, "hillbcs-call-avatar").start();
    }

    static void hide(Context context) {
        startedAt = 0L;
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
        } catch (Exception e) {
            Log.w(TAG, "Could not clear the ongoing call notification", e);
        }
    }

    private static void post(Context context, String name, Bitmap avatar, boolean video) {
        Person.Builder person = new Person.Builder().setName(name).setImportant(true);
        if (avatar != null) {
            // Adaptive so the platform masks it to a circle, the way it treats every
            // other person in a notification.
            person.setIcon(IconCompat.createWithAdaptiveBitmap(avatar));
        }

        PendingIntent hangUp = PendingIntent.getBroadcast(
            context,
            0,
            new Intent(context, CallActionReceiver.class).setAction(CallActionReceiver.ACTION_HANG_UP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // A bare intent to the conference activity, which is how the SDK's own
        // notification returns to the call. JitsiMeetActivity is singleTask, so this
        // brings the running instance forward rather than starting a second one.
        PendingIntent tapBack = PendingIntent.getActivity(
            context,
            1,
            new Intent(context, JitsiMeetActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person.build(), hangUp))
            .setContentIntent(tapBack)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            // Ongoing and non-dismissible: the call outlives the shade, and a user
            // who swipes this away has not left the call.
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // The running duration, which is the part people actually read.
            .setUsesChronometer(true)
            .setWhen(startedAt == 0L ? System.currentTimeMillis() : startedAt)
            .setColorized(true)
            .setColor(ContextCompat.getColor(context, R.color.brandBlue))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Read aloud on older versions, where CallStyle has no special treatment
            // and the layout collapses to title plus text.
            .setContentText(video ? "Video call in progress" : "Voice call in progress");

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS was refused. The call itself is unaffected; only
            // this surface is missing, and the SDK's own notification remains.
            Log.w(TAG, "Not allowed to post the ongoing call notification", e);
        }
    }

    private static Bitmap downloadBitmap(String url) {
        HttpURLConnection connection = null;
        try {
            URL parsed = new URL(url);
            if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
                return null;
            }
            connection = (HttpURLConnection) parsed.openConnection();
            connection.setConnectTimeout(AVATAR_TIMEOUT_MS);
            connection.setReadTimeout(AVATAR_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            try (InputStream stream = connection.getInputStream()) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                // A notification icon is tens of dp; decoding a full-size upload
                // would spend megabytes to be scaled straight back down.
                options.inSampleSize = 2;
                return BitmapFactory.decodeStream(stream, null, options);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not load the conversation avatar");
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
