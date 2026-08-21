package com.hillbcs.letschat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Capacitor-native entry point for the Jitsi SDK call surface.
 *
 * The older direct WebView object, window.HillbcsCall, is kept for compatibility,
 * but Capacitor plugins are registered before the app document loads. That makes
 * this the reliable path for the remote React app running inside the wrapper.
 */
@CapacitorPlugin(name = "HillbcsCall")
public class HillbcsCallPlugin extends Plugin {
    private NativeCall nativeCall;

    @Override
    public void load() {
        if (getActivity() instanceof MainActivity mainActivity) {
            nativeCall = mainActivity.getOrCreateNativeCall(getBridge());
        } else {
            nativeCall = new NativeCall(getActivity(), getBridge());
        }
    }

    @PluginMethod
    public void available(PluginCall call) {
        JSObject result = new JSObject();
        result.put("available", true);
        call.resolve(result);
    }

    @PluginMethod
    public void consumeEndedRoom(PluginCall call) {
        JSObject result = new JSObject();
        result.put("room", nativeCall == null ? "" : nativeCall.consumeEndedRoom());
        call.resolve(result);
    }

    @PluginMethod
    public void join(PluginCall call) {
        if (nativeCall == null) {
            call.reject("Native call bridge is not loaded");
            return;
        }

        boolean launched = nativeCall.join(
            call.getString("serverUrl", ""),
            call.getString("room", ""),
            call.getString("displayName", ""),
            call.getString("email", ""),
            Boolean.TRUE.equals(call.getBoolean("videoMuted", false)),
            call.getString("jwt", ""),
            call.getString("avatarUrl", ""),
            Boolean.TRUE.equals(call.getBoolean("audioMuted", false)),
            call.getString("subject", ""),
            call.getString("conversationAvatarUrl", "")
        );

        JSObject result = new JSObject();
        result.put("launched", launched);
        call.resolve(result);
    }

    @Override
    protected void handleOnDestroy() {
        if (getActivity() instanceof MainActivity) {
            nativeCall = null;
            return;
        }
        if (nativeCall != null) {
            nativeCall.dispose();
            nativeCall = null;
        }
    }
}
