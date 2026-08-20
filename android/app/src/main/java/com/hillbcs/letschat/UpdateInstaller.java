package com.hillbcs.letschat;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.webkit.JavascriptInterface;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Downloads a release APK and hands it to the system installer.
 *
 * Exposed to the web layer as {@code window.HillbcsUpdater} so the update prompt
 * can live in the React app, which is served fresh on every launch. Putting the
 * decision there rather than here matters: a mistake in native update logic can
 * only be fixed by shipping an update, which is the very thing that would be
 * broken.
 *
 * <p>There is no silent install. Android requires the user to confirm in the
 * package installer, and on Android 8+ to have granted this app permission to
 * install unknown apps. "Auto update" here means one tap, not zero.
 *
 * <p>The download URL is checked against a host allowlist. The WebView loads a
 * remote origin, so without that check anything able to run script on that origin
 * could ask the app to fetch and offer an arbitrary APK for installation.
 */
public class UpdateInstaller {
    private static final String TAG = "HillbcsUpdater";
    private static final String FILE_NAME = "letschat-update.apk";

    /**
     * GitHub serves release assets from the API host and redirects to its object
     * storage, so both have to be accepted.
     */
    private static final List<String> ALLOWED_HOSTS = Arrays.asList(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    );

    private final Activity activity;
    private long pendingDownloadId = -1L;
    private BroadcastReceiver completionReceiver;

    UpdateInstaller(Activity activity) {
        this.activity = activity;
    }

    /**
     * Fetches the APK at {@code url} and opens the installer when it lands.
     *
     * @return true if the download was accepted; false if the URL was rejected.
     */
    @JavascriptInterface
    public boolean downloadAndInstall(String url) {
        Uri uri = safeUri(url);
        if (uri == null) {
            Log.w(TAG, "Refused update URL: " + url);
            return false;
        }

        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            return false;
        }

        // A fresh copy every time; a partial file from a failed attempt would
        // otherwise be handed to the installer and rejected as corrupt.
        File target = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FILE_NAME);
        if (target.exists() && !target.delete()) {
            Log.w(TAG, "Could not clear previous update download");
        }

        registerCompletionReceiver(manager, target);

        DownloadManager.Request request = new DownloadManager.Request(uri)
            .setTitle("Let's Chat update")
            .setDescription("Downloading the latest version…")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, FILE_NAME)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true);

        pendingDownloadId = manager.enqueue(request);
        return true;
    }

    /** Whether the user still has to allow installing from this app. */
    @JavascriptInterface
    public boolean canInstallPackages() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true;
        }
        return activity.getPackageManager().canRequestPackageInstalls();
    }

    /** Opens the system screen where that permission is granted. */
    @JavascriptInterface
    public void openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        activity.startActivity(
            new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:" + activity.getPackageName()))
        );
    }

    /** The running build, so the web layer can compare against the manifest. */
    @JavascriptInterface
    public int currentBuild() {
        try {
            return (int) activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0)
                .getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    void dispose() {
        if (completionReceiver != null) {
            try {
                activity.unregisterReceiver(completionReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already gone.
            }
            completionReceiver = null;
        }
    }

    private void registerCompletionReceiver(DownloadManager manager, File target) {
        dispose();

        completionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (id != pendingDownloadId) {
                    return;
                }
                if (downloadSucceeded(manager, id) && target.exists()) {
                    launchInstaller(target);
                } else {
                    Log.w(TAG, "Update download did not complete successfully");
                }
                dispose();
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Required from Android 13: the broadcast comes from outside the app.
            activity.registerReceiver(completionReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            activity.registerReceiver(completionReceiver, filter);
        }
    }

    private boolean downloadSucceeded(DownloadManager manager, long id) {
        try (android.database.Cursor cursor =
                 manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                return false;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            return status == DownloadManager.STATUS_SUCCESSFUL;
        } catch (Exception e) {
            return false;
        }
    }

    private void launchInstaller(File apk) {
        try {
            Uri content = FileProvider.getUriForFile(
                activity,
                activity.getPackageName() + ".fileprovider",
                apk
            );
            Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(content, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
        } catch (Exception e) {
            Log.w(TAG, "Could not open the installer", e);
        }
    }

    /** https only, and only from hosts that serve our releases. */
    private Uri safeUri(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        Uri uri = Uri.parse(url.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        String host = uri.getHost();
        return host != null && ALLOWED_HOSTS.contains(host.toLowerCase()) ? uri : null;
    }
}
