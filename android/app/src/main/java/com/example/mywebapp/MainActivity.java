package com.example.mywebapp;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebView;
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

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerDownloadListener();
        configureWebViewPopups();
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
