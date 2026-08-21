package com.hillbcs.letschat;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.util.Log;
import android.webkit.JavascriptInterface;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.getcapacitor.Bridge;

import org.jitsi.meet.sdk.BroadcastEvent;
import org.jitsi.meet.sdk.JitsiMeetActivity;
import org.jitsi.meet.sdk.JitsiMeetConferenceOptions;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

/**
 * Runs calls through the native Jitsi Meet SDK instead of the WebView.
 *
 * The reason is screen sharing. Android's WebView does not implement
 * {@code getDisplayMedia()}, so the web Jitsi client cannot capture the screen on
 * this platform however the permissions are set. The SDK uses MediaProjection,
 * which can — and it also gets proper audio routing, a foreground service that
 * survives backgrounding, and picture-in-picture, none of which the framed web
 * client manages well.
 *
 * Exposed to the web layer as {@code window.HillbcsCall}. The web side stays the
 * source of truth for call lifecycle: it already registers presence heartbeats and
 * posts the leave/end writeback, so this class only opens the conference and
 * reports when it finished.
 */
public class NativeCall {
    private static final String TAG = "HillbcsCall";

    /** Only rooms on hosts we serve are launchable, since JS supplies the target. */
    private static final List<String> ALLOWED_HOSTS = Arrays.asList(
        "meet.hillbcs.com",
        "chats.hillbcs.com"
    );

    private final Activity activity;
    private final Bridge bridge;
    private BroadcastReceiver conferenceReceiver;

    NativeCall(Activity activity, Bridge bridge) {
        this.activity = activity;
        this.bridge = bridge;
    }

    /**
     * Whether the web layer should route calls here rather than opening the shell.
     * Lets an older wrapper keep using the WebView path without the app having to
     * know which build it is running in.
     */
    @JavascriptInterface
    public boolean available() {
        return true;
    }

    /**
     * Joins {@code room} on {@code serverUrl}.
     *
     * @param videoMuted starts with the camera off. Deliberately not
     *     {@code setAudioOnly}: audio-only is a conference-wide mode that drops
     *     video altogether, and with it the screen-share button — the one feature
     *     this class exists to provide. Muting the camera leaves the conference
     *     video-capable, so a voice call can still share a screen.
     * @param jwt may be empty; passed through when the deployment requires auth
     * @return false when the target was rejected or malformed
     */
    @JavascriptInterface
    public boolean join(String serverUrl, String room, String displayName, String email, boolean videoMuted, String jwt) {
        URL server = safeServerUrl(serverUrl);
        String cleanRoom = room == null ? "" : room.replaceAll("[^a-zA-Z0-9_-]", "");
        if (server == null || cleanRoom.isEmpty()) {
            Log.w(TAG, "Refused call target: " + serverUrl + " / " + room);
            return false;
        }

        JitsiMeetConferenceOptions.Builder options = new JitsiMeetConferenceOptions.Builder()
            .setServerURL(server)
            .setRoom(cleanRoom)
            .setVideoMuted(videoMuted)
            // The point of this class. Off by default in some SDK builds, so it is
            // set explicitly rather than assumed.
            .setFeatureFlag("android.screensharing.enabled", true)
            .setFeatureFlag("pip.enabled", true)
            // The surrounding app owns invites and calendars; surfacing Jitsi's own
            // would offer users a second, unrelated way to share a room.
            .setFeatureFlag("invite.enabled", false)
            .setFeatureFlag("calendar.enabled", false)
            .setFeatureFlag("meeting-password.enabled", false);

        if (jwt != null && !jwt.trim().isEmpty()) {
            options.setToken(jwt.trim());
        }
        if (displayName != null && !displayName.trim().isEmpty()) {
            options.setUserInfo(userInfo(displayName.trim(), email));
        }

        registerConferenceReceiver();

        // launch() touches the view hierarchy, and @JavascriptInterface methods
        // arrive on a WebView worker thread.
        activity.runOnUiThread(() -> {
            try {
                JitsiMeetActivity.launch(activity, options.build());
            } catch (Exception e) {
                Log.e(TAG, "Could not launch the native call", e);
            }
        });
        return true;
    }

    private org.jitsi.meet.sdk.JitsiMeetUserInfo userInfo(String displayName, String email) {
        org.jitsi.meet.sdk.JitsiMeetUserInfo info = new org.jitsi.meet.sdk.JitsiMeetUserInfo();
        info.setDisplayName(displayName);
        if (email != null && !email.trim().isEmpty()) {
            info.setEmail(email.trim());
        }
        return info;
    }

    /**
     * Tells the web layer when the conference ends, so it can settle the call the
     * same way it does when a popup closes. Without this the server would keep
     * believing the call is live until presence went cold.
     */
    private void registerConferenceReceiver() {
        if (conferenceReceiver != null) {
            return;
        }

        conferenceReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                BroadcastEvent event = new BroadcastEvent(intent);
                if (event.getType() == BroadcastEvent.Type.CONFERENCE_TERMINATED) {
                    notifyWeb("hillbcs-native-call-ended");
                } else if (event.getType() == BroadcastEvent.Type.CONFERENCE_JOINED) {
                    notifyWeb("hillbcs-native-call-joined");
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(BroadcastEvent.Type.CONFERENCE_JOINED.getAction());
        filter.addAction(BroadcastEvent.Type.CONFERENCE_TERMINATED.getAction());
        LocalBroadcastManager.getInstance(activity).registerReceiver(conferenceReceiver, filter);
    }

    private void notifyWeb(String eventName) {
        if (bridge == null) {
            return;
        }
        // Dispatched as a DOM event so the web layer needs no plugin plumbing to
        // listen for it.
        final String js = "window.dispatchEvent(new CustomEvent('" + eventName + "'))";
        activity.runOnUiThread(() -> {
            try {
                bridge.getWebView().evaluateJavascript(js, null);
            } catch (Exception e) {
                Log.w(TAG, "Could not notify the web layer of " + eventName, e);
            }
        });
    }

    void dispose() {
        if (conferenceReceiver != null) {
            try {
                LocalBroadcastManager.getInstance(activity).unregisterReceiver(conferenceReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already gone.
            }
            conferenceReceiver = null;
        }
    }

    /** https only, and only hosts that belong to this deployment. */
    private URL safeServerUrl(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            Uri uri = Uri.parse(raw);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null) {
                return null;
            }
            if (!ALLOWED_HOSTS.contains(host.toLowerCase())) {
                return null;
            }
            return new URL(uri.getScheme() + "://" + host);
        } catch (MalformedURLException e) {
            return null;
        }
    }
}
