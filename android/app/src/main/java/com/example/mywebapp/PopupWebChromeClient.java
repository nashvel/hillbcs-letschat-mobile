package com.example.mywebapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.os.Build;
import android.os.Message;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebChromeClient;
import com.getcapacitor.BridgeWebViewClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shows {@code window.open} / multi-window content in a fullscreen {@link Dialog} so the main
 * Capacitor WebView stays on the original page. When the dialog is dismissed (back, outside tap,
 * or {@code window.close()} via {@link #onCloseWindow}), the user returns to that page.
 */
public class PopupWebChromeClient extends BridgeWebChromeClient {

    private static final List<PopupLayer> POPUPS = Collections.synchronizedList(new ArrayList<>());

    private final Bridge bridge;

    public PopupWebChromeClient(Bridge bridge) {
        super(bridge);
        this.bridge = bridge;
    }

    private static void copyWebSettings(WebView from, WebView to) {
        WebSettings s = to.getSettings();
        WebSettings p = from.getSettings();
        s.setJavaScriptEnabled(p.getJavaScriptEnabled());
        s.setDomStorageEnabled(p.getDomStorageEnabled());
        s.setMediaPlaybackRequiresUserGesture(p.getMediaPlaybackRequiresUserGesture());
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setUserAgentString(p.getUserAgentString());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(p.getMixedContentMode());
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
        if (!(resultMsg.obj instanceof WebView.WebViewTransport)) {
            return false;
        }

        Activity activity = bridge.getActivity();
        if (activity == null || activity.isFinishing()) {
            return false;
        }

        final WebView child = new WebView(activity);
        copyWebSettings(view, child);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(child, true);
        }
        child.setWebViewClient(new BridgeWebViewClient(bridge));
        child.setWebChromeClient(new PopupWebChromeClient(bridge));

        final Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        child.setLayoutParams(
            new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        );
        dialog.setContentView(child);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }

        final PopupLayer layer = new PopupLayer(dialog, child);
        synchronized (POPUPS) {
            POPUPS.add(layer);
        }

        dialog.setOnDismissListener(
            d -> {
                synchronized (POPUPS) {
                    POPUPS.remove(layer);
                }
                try {
                    child.removeAllViews();
                } catch (Exception ignored) {
                }
                try {
                    child.destroy();
                } catch (Exception ignored) {
                }
            }
        );

        dialog.show();

        ((WebView.WebViewTransport) resultMsg.obj).setWebView(child);
        resultMsg.sendToTarget();
        return true;
    }

    @Override
    public void onCloseWindow(WebView window) {
        synchronized (POPUPS) {
            for (int i = POPUPS.size() - 1; i >= 0; i--) {
                PopupLayer layer = POPUPS.get(i);
                if (layer.webView == window) {
                    if (layer.dialog.isShowing()) {
                        layer.dialog.dismiss();
                    }
                    return;
                }
            }
        }
    }

    private static final class PopupLayer {

        final Dialog dialog;
        final WebView webView;

        PopupLayer(Dialog dialog, WebView webView) {
            this.dialog = dialog;
            this.webView = webView;
        }
    }
}
