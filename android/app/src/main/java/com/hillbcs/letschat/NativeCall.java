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

    /** The room this shell launched, so an end can be attributed to it. */
    private volatile String currentRoom = "";

    /**
     * A conference that ended without the web layer hearing about it, kept until
     * {@link #consumeEndedRoom()} drains it.
     *
     * The DOM event alone is not enough. It is fire-and-forget into a page that
     * may not be listening: Android can destroy MainActivity while the Jitsi
     * activity is in front — it is a second, memory-hungry activity — and the
     * WebView then reloads the app from the network with no memory of the call.
     * The event lands on nobody, the leave is never posted, and the conversation
     * keeps offering "Join" and "End for everyone" for a call that is over.
     */
    private volatile String endedRoom = "";

    NativeCall(Activity activity, Bridge bridge) {
        this.activity = activity;
        this.bridge = bridge;
        // Registered up front rather than from join(). A recreated MainActivity has
        // no record of having launched anything, but the conference it launched is
        // still running and will still broadcast when it ends.
        registerConferenceReceiver();
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
     * The room of a conference that has ended, or "" — and clears it.
     *
     * The safety net behind the {@code hillbcs-native-call-ended} event: a web
     * layer that reloaded during the call, and so never received the event, calls
     * this on startup and can still settle the call. Named for the room so the
     * caller only settles the call it was actually tracking.
     */
    @JavascriptInterface
    public String consumeEndedRoom() {
        String room = endedRoom;
        endedRoom = "";
        return room;
    }

    /**
     * Joins {@code room} on {@code serverUrl}.
     *
     * @param videoMuted starts with the camera off. Deliberately not
     *     {@code setAudioOnly}: audio-only is a conference-wide mode that drops
     *     video altogether, and with it the screen-share button — the one feature
     *     this class exists to provide. Muting the camera leaves the conference
     *     video-capable, so a voice call can still share a screen.
     * @param audioMuted starts with the microphone off
     * @param avatarUrl shown to other participants; ignored when unparseable
     * @param jwt may be empty; passed through when the deployment requires auth
     * @return false when the target was rejected or malformed
     */
    /**
     * Six-argument form kept for the web app deployed before avatars and the mic
     * choice were passed through.
     *
     * WebView resolves {@code @JavascriptInterface} methods by arity, so without
     * this an updated shell would find no matching {@code join} on an older
     * deployment, silently fall back to the WebView call surface, and lose screen
     * sharing — the one thing the native path is for.
     */
    @JavascriptInterface
    public boolean join(String serverUrl, String room, String displayName, String email, boolean videoMuted, String jwt) {
        return join(serverUrl, room, displayName, email, videoMuted, jwt, "", false);
    }

    @JavascriptInterface
    public boolean join(
        String serverUrl,
        String room,
        String displayName,
        String email,
        boolean videoMuted,
        String jwt,
        String avatarUrl,
        boolean audioMuted
    ) {
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
            // Mirrors the app's own pre-call controls: whatever the user chose
            // there is how the conference opens, rather than the SDK's defaults
            // overriding a decision they already made.
            .setAudioMuted(audioMuted)
            // The point of this class. Off by default in some SDK builds, so it is
            // set explicitly rather than assumed.
            .setFeatureFlag("android.screensharing.enabled", true)
            .setFeatureFlag("pip.enabled", true)
            // The surrounding app owns invites and calendars; surfacing Jitsi's own
            // would offer users a second, unrelated way to share a room.
            .setFeatureFlag("invite.enabled", false)
            .setFeatureFlag("calendar.enabled", false)
            .setFeatureFlag("meeting-password.enabled", false)
            /*
             * No prejoin screen. The user is already signed in and has just tapped
             * call in a conversation, so asking them to type a name and confirm is
             * a second, redundant gate — and the name it asks for is one this app
             * already knows and passes below.
             *
             * Set as a flag and as a config override because the two are separate
             * switches: the flag is the SDK's, while prejoinConfig.enabled is the
             * deployment's, and the server's value would otherwise reinstate it.
             */
            .setFeatureFlag("prejoinpage.enabled", false)
            .setConfigOverride("prejoinConfig.enabled", false);

        if (jwt != null && !jwt.trim().isEmpty()) {
            options.setToken(jwt.trim());
        }
        // Always set, even with a blank name: user info is what stops the SDK
        // prompting for an identity, and the avatar rides along with it.
        options.setUserInfo(userInfo(displayName, email, avatarUrl));

        currentRoom = cleanRoom;
        // Cleared so a stale end from an earlier call cannot settle this one.
        endedRoom = "";
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

    private org.jitsi.meet.sdk.JitsiMeetUserInfo userInfo(String displayName, String email, String avatarUrl) {
        org.jitsi.meet.sdk.JitsiMeetUserInfo info = new org.jitsi.meet.sdk.JitsiMeetUserInfo();
        if (displayName != null && !displayName.trim().isEmpty()) {
            info.setDisplayName(displayName.trim());
        }
        if (email != null && !email.trim().isEmpty()) {
            info.setEmail(email.trim());
        }
        // The app's own avatar, so participants are recognisable rather than
        // reduced to Jitsi's generated initials. Skipped rather than fatal when
        // unparseable: a bad URL must not stop the call from connecting.
        if (avatarUrl != null && !avatarUrl.trim().isEmpty()) {
            try {
                info.setAvatar(new URL(avatarUrl.trim()));
            } catch (MalformedURLException e) {
                Log.w(TAG, "Ignoring unusable avatar URL");
            }
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
                BroadcastEvent.Type type = event.getType();
                /*
                 * READY_TO_CLOSE as well as CONFERENCE_TERMINATED. Hanging up
                 * emits the first; the second does not arrive at all if the
                 * conference was left before it was ever fully joined, which
                 * would otherwise leave the call unsettled.
                 */
                if (type == BroadcastEvent.Type.CONFERENCE_TERMINATED || type == BroadcastEvent.Type.READY_TO_CLOSE) {
                    endedRoom = currentRoom;
                    notifyWeb("hillbcs-native-call-ended");
                } else if (type == BroadcastEvent.Type.CONFERENCE_JOINED) {
                    notifyWeb("hillbcs-native-call-joined");
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(BroadcastEvent.Type.CONFERENCE_JOINED.getAction());
        filter.addAction(BroadcastEvent.Type.CONFERENCE_TERMINATED.getAction());
        filter.addAction(BroadcastEvent.Type.READY_TO_CLOSE.getAction());
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
