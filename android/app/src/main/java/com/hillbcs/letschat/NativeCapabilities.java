package com.hillbcs.letschat;

import android.app.Activity;
import android.webkit.JavascriptInterface;

import com.google.firebase.FirebaseApp;

/**
 * What this particular APK can actually do, answered for the web layer.
 *
 * The app is a shell around a remotely-served web app, so the two halves can be
 * different ages: a phone may be running a build made before a capability
 * existed, or one assembled without the configuration a capability needs. The web
 * layer therefore has to ask rather than assume.
 */
public class NativeCapabilities {
    private final Activity activity;

    NativeCapabilities(Activity activity) {
        this.activity = activity;
    }

    /**
     * Whether Firebase initialised, and so whether push can be registered.
     *
     * This exists because {@code PushNotifications.register()} does not fail
     * politely without google-services.json: FirebaseMessaging.getInstance()
     * throws IllegalStateException on Capacitor's plugin thread, which is an
     * uncaught native exception and takes the whole process down. It cannot be
     * caught from JavaScript, so it has to be avoided instead — hence this check
     * before registering.
     *
     * <p>google-services.json is applied at build time, so a build made without
     * it can never receive push, and asking every launch is the only way the web
     * layer can tell.
     */
    @JavascriptInterface
    public boolean pushAvailable() {
        try {
            return !FirebaseApp.getApps(activity).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
