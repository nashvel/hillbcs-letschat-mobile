package com.hillbcs.letschat;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.FrameLayout;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;

/**
 * Registers a {@link android.webkit.DownloadListener} so file downloads from the WebView
 * (e.g. Content-Disposition: attachment) are handed to the system DownloadManager.
 * <p>
 * Popups ({@code window.open}, multi-window) open in a fullscreen dialog so the main WebView
 * stays on the original page; closing the dialog returns there ({@link PopupWebChromeClient}).
 * <p>
 * Microphone, camera, location, and file picker flows are handled at runtime by Capacitor’s
 * {@link com.getcapacitor.BridgeWebChromeClient} as long as matching {@code uses-permission}
 * entries exist in the manifest.
 * <p>
 * Cookies: {@link CookieManager#setAcceptCookie}, third-party cookies where supported, and
 * {@link CookieManager#flush} on pause/stop help keep server sessions when the site relies on
 * HTTP cookies (see user-facing docs for limits of WebView vs browser).
 */
public class MainActivity extends BridgeActivity {

    private static final String TAG = "HillbcsShell";

    private UpdateInstaller updateInstaller;
    private NativeCall nativeCall;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(HillbcsCallPlugin.class);
        super.onCreate(savedInstanceState);
        registerDownloadListener();
        configureWebViewPopups();
        paintStatusBarStrip();
        exposeUpdater();
        ensureJavascriptInterfacesOnFirstPage(savedInstanceState);
    }

    /**
     * Publishes {@link UpdateInstaller} to the web layer as
     * {@code window.HillbcsUpdater}.
     *
     * Only reachable from the origin this WebView is configured to load, and every
     * install still goes through the system installer's confirmation, so the worst
     * a compromised page can do is show the user a prompt.
     */
    private void exposeUpdater() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            return;
        }
        WebView webView = bridge.getWebView();
        if (webView == null) {
            return;
        }
        updateInstaller = new UpdateInstaller(this);
        webView.addJavascriptInterface(updateInstaller, "HillbcsUpdater");
        // Capability probes, so the web layer can avoid calling into a plugin this
        // build cannot support. Named to match the Laravel messenger's shell.
        webView.addJavascriptInterface(new NativeCapabilities(this), "HillbcsNative");
        // Native Jitsi, so screen sharing is possible at all on Android.
        webView.addJavascriptInterface(getOrCreateNativeCall(bridge), "HillbcsCall");
    }

    NativeCall getOrCreateNativeCall(Bridge bridge) {
        if (nativeCall == null) {
            nativeCall = new NativeCall(this, bridge);
        }
        return nativeCall;
    }

    /**
     * Capacitor's Bridge constructor starts loading the app before MainActivity can
     * attach our direct JavaScript interfaces. Android only guarantees interfaces
     * on pages loaded after addJavascriptInterface(), so a cold start could miss
     * window.HillbcsCall and React would open the web call shell instead of the SDK.
     */
    private void ensureJavascriptInterfacesOnFirstPage(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            return;
        }
        Bridge bridge = getBridge();
        if (bridge == null || bridge.getWebView() == null) {
            return;
        }
        bridge.getWebView().post(() -> {
            try {
                bridge.getWebView().reload();
            } catch (Exception e) {
                Log.w(TAG, "Could not reload after exposing JavaScript interfaces", e);
            }
        });
    }

    @Override
    public void onDestroy() {
        if (updateInstaller != null) {
            updateInstaller.dispose();
            updateInstaller = null;
        }
        if (nativeCall != null) {
            nativeCall.dispose();
            nativeCall = null;
        }
        super.onDestroy();
    }

    /**
     * Paints the status bar area with the brand colour.
     *
     * From Android 15 (API 35), an app targeting 35 or later is forced edge-to-edge
     * and the status bar is permanently transparent: {@code android:statusBarColor}
     * and {@code Window.setStatusBarColor()} are ignored, and so is Capacitor's
     * {@code StatusBar.setBackgroundColor}, which calls the latter. The bar shows
     * whatever is behind it, which here is the WebView — hence a white strip with
     * light icons on it, effectively invisible.
     * <p>
     * A view laid over that strip is the remaining way to colour it. It hides
     * nothing, because {@code android.adjustMarginsForEdgeToEdge} in
     * {@code capacitor.config.ts} margins the WebView below the status bar, so
     * the region this covers is outside the WebView entirely.
     * <p>
     * The height comes from the live inset rather than a dimension resource so it
     * survives rotation, a change of display cutout, and devices whose bar is not
     * the usual 24dp.
     */
    private void paintStatusBarStrip() {
        ViewGroup content = findViewById(android.R.id.content);
        if (content == null) {
            return;
        }

        final View strip = new View(this);
        strip.setBackgroundColor(ContextCompat.getColor(this, R.color.brandBlue));
        // Starts collapsed; the inset listener below gives it its real height.
        strip.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0));
        // Added last so it draws above the WebView.
        content.addView(strip);

        ViewCompat.setOnApplyWindowInsetsListener(
            content,
            (view, insets) -> {
                int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
                ViewGroup.LayoutParams params = strip.getLayoutParams();
                if (params.height != top) {
                    params.height = top;
                    strip.setLayoutParams(params);
                    // Insets are dispatched on every layout pass, so this is kept
                    // to changes only: it is here to make a bar that renders the
                    // wrong height diagnosable from logcat without a rebuild.
                    Log.d(TAG, "status bar strip resized to " + top + "px");
                }
                /*
                 * Passed through rather than consumed. The WebView is a child of
                 * this view and installs its own listener to read the same insets
                 * for its margins; consuming them here would starve it and put
                 * web content back under the bar.
                 */
                return insets;
            }
        );
    }

    @Override
    public void onPause() {
        flushCookies();
        super.onPause();
    }

    @Override
    public void onStop() {
        flushCookies();
        super.onStop();
    }

    /** Persists auth cookies to disk so logins can survive app restarts (when the site uses cookies). */
    private void flushCookies() {
        try {
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {
        }
    }

    /** Same-tab behavior for {@code window.open} / target blank windows. */
    private void configureWebViewPopups() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            return;
        }
        WebView webView = bridge.getWebView();
        if (webView == null) {
            return;
        }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, true);
        }
        webView.getSettings().setSupportMultipleWindows(true);
        webView.setWebChromeClient(new PopupWebChromeClient(bridge));
    }

    private void registerDownloadListener() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            return;
        }
        WebView webView = bridge.getWebView();
        if (webView == null) {
            return;
        }

        webView.setDownloadListener(
            (url, userAgent, contentDisposition, mimeType, contentLength) -> {
                try {
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimeType);
                    request.setTitle(fileName);
                    request.setDescription("Downloading…");
                    // Avoid requiring POST_NOTIFICATIONS (Android 13+) for a background download prompt
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                    request.setAllowedOverMetered(true);
                    request.setAllowedOverRoaming(true);

                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null) {
                        request.addRequestHeader("Cookie", cookies);
                    }
                    if (userAgent != null) {
                        request.addRequestHeader("User-Agent", userAgent);
                    }

                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(request);
                    }
                } catch (Exception ignored) {
                    // DownloadManager may reject invalid URLs or restricted paths
                }
            }
        );
    }
}
