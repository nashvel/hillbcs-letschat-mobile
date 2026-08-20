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

import org.json.JSONException;
import org.json.JSONObject;

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
    private static final String FILE_PREFIX = "letschat-update";
    private static final String FILE_NAME = FILE_PREFIX + ".apk";

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

        // Best effort only, and deliberately not depended on: see
        // downloadedFile() for why the install no longer assumes a fixed name.
        // Worth doing anyway, because each of these is tens of megabytes.
        clearPreviousDownloads();

        registerCompletionReceiver(manager);

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

    /**
     * State of the download in progress, as JSON, for the web layer to poll:
     * {@code {"state":"downloading","bytes":4194304,"total":6553600}}.
     *
     * <p>Polled rather than pushed. DownloadManager reports progress by cursor
     * query, so there is no callback to forward, and a pull keeps this in the same
     * shape as the rest of the bridge — a plain synchronous getter with no
     * listener to leak if the web layer navigates away mid-download.
     *
     * <p>{@code total} is -1 until the server declares a length, which is the
     * caller's cue to show an indeterminate bar rather than a misleading 0%.
     *
     * <p>{@code state} is one of idle, pending, downloading, paused, installing,
     * failed. "installing" means the bytes have landed and the system installer
     * has been handed the file; the app is not in control after that point.
     */
    @JavascriptInterface
    public String downloadProgress() {
        if (pendingDownloadId == -1L) {
            return progressJson("idle", 0L, -1L);
        }

        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            return progressJson("idle", 0L, -1L);
        }

        try (android.database.Cursor cursor =
                 manager.query(new DownloadManager.Query().setFilterById(pendingDownloadId))) {
            if (cursor == null || !cursor.moveToFirst()) {
                // The row is gone: cleared from the download list, or the id was
                // never accepted. Nothing to report on.
                return progressJson("idle", 0L, -1L);
            }

            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));

            switch (status) {
                case DownloadManager.STATUS_PENDING:
                    return progressJson("pending", bytes, total);
                case DownloadManager.STATUS_RUNNING:
                    return progressJson("downloading", bytes, total);
                case DownloadManager.STATUS_PAUSED:
                    return progressJson("paused", bytes, total);
                case DownloadManager.STATUS_SUCCESSFUL:
                    // Report a full bar: at 100% the reported byte count can still
                    // trail the total by a chunk, which would otherwise leave the
                    // UI stuck at 98% while the installer is already open.
                    return progressJson("installing", total > 0 ? total : bytes, total);
                default:
                    return progressJson("failed", bytes, total);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read download progress", e);
            return progressJson("failed", 0L, -1L);
        }
    }

    private String progressJson(String state, long bytes, long total) {
        try {
            return new JSONObject().put("state", state).put("bytes", bytes).put("total", total).toString();
        } catch (JSONException e) {
            return "{\"state\":\"failed\",\"bytes\":0,\"total\":-1}";
        }
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

    private void registerCompletionReceiver(DownloadManager manager) {
        dispose();

        completionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (id != pendingDownloadId) {
                    return;
                }
                File apk = downloadedFile(manager, id);
                if (apk != null && apk.exists()) {
                    launchInstaller(apk);
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

    /**
     * Where the download actually landed, or null unless it completed.
     *
     * <p>Read back from DownloadManager rather than assumed to be {@link #FILE_NAME}.
     * DownloadManager will not overwrite an existing destination: it silently
     * writes to {@code letschat-update-1.apk} instead and reports success. Code
     * that then installs the fixed name hands the system installer whatever older
     * APK is still sitting there — so asking for 1.0.8 would install 1.0.7, or
     * fail outright against a half-written file from an earlier attempt. Deleting
     * the old copy first is not enough on its own, because the file belongs to the
     * DownloadManager process and the delete can be refused.
     */
    private File downloadedFile(DownloadManager manager, long id) {
        try (android.database.Cursor cursor =
                 manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                return null;
            }
            String localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
            if (localUri == null) {
                return null;
            }
            Uri uri = Uri.parse(localUri);
            // Downloads aimed at our own external files dir come back as file://.
            // Anything else is not ours to hand to the installer via FileProvider.
            String path = "file".equalsIgnoreCase(uri.getScheme()) ? uri.getPath() : null;
            return path == null ? null : new File(path);
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve the downloaded update", e);
            return null;
        }
    }

    /** Drops earlier update APKs, including any {@code -1} copies left by a collision. */
    private void clearPreviousDownloads() {
        File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            return;
        }
        File[] stale = dir.listFiles((unused, name) -> name.startsWith(FILE_PREFIX) && name.endsWith(".apk"));
        if (stale == null) {
            return;
        }
        for (File file : stale) {
            if (!file.delete()) {
                Log.w(TAG, "Could not remove " + file.getName());
            }
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
