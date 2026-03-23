package de.codevoid.aNavMode.debug;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

import de.codevoid.aNavMode.BuildConfig;

public class UpdateChecker {

    private static final String API_URL =
            "https://api.github.com/repos/c0dev0id/aNavMode/releases/tags/nightly";

    public static class Release {
        public final String name;
        public final String downloadUrl;
        public final long publishedMs;

        Release(String name, String downloadUrl, long publishedMs) {
            this.name = name;
            this.downloadUrl = downloadUrl;
            this.publishedMs = publishedMs;
        }

        public boolean isNewerThanThisBuild() {
            return publishedMs > BuildConfig.BUILD_TIME;
        }
    }

    public interface CheckCallback {
        /** Called on main thread. release is null on network error. */
        void onResult(Release release, String error);
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onComplete(File apk);
        void onError(String error);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void check(CheckCallback callback) {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);

                if (conn.getResponseCode() != 200) {
                    MAIN.post(() -> callback.onResult(null, "HTTP " + conn.getResponseCode()));
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }

                JSONObject json = new JSONObject(sb.toString());
                String name = json.getString("name");
                String publishedAt = json.getString("published_at");

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                long publishedMs = sdf.parse(publishedAt).getTime();

                JSONArray assets = json.getJSONArray("assets");
                String downloadUrl = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if (asset.getString("name").endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url");
                        break;
                    }
                }

                if (downloadUrl == null) {
                    MAIN.post(() -> callback.onResult(null, "No APK asset found"));
                    return;
                }

                Release release = new Release(name, downloadUrl, publishedMs);
                MAIN.post(() -> callback.onResult(release, null));

            } catch (Exception e) {
                MAIN.post(() -> callback.onResult(null, e.getMessage()));
            }
        }).start();
    }

    public static void download(Context context, Release release, DownloadCallback callback) {
        new Thread(() -> {
            try {
                File dir = new File(context.getCacheDir(), "update");
                dir.mkdirs();
                File apk = new File(dir, "aNavMode-nightly.apk");

                HttpURLConnection conn = (HttpURLConnection) new URL(release.downloadUrl).openConnection();
                conn.setConnectTimeout(15_000);
                conn.connect();

                int total = conn.getContentLength();
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[32768];
                    int downloaded = 0, n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        downloaded += n;
                        if (total > 0) {
                            int pct = (int) (downloaded * 100L / total);
                            MAIN.post(() -> callback.onProgress(pct));
                        }
                    }
                }

                MAIN.post(() -> callback.onComplete(apk));

            } catch (Exception e) {
                MAIN.post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }

    public static void install(Context context, File apk) {
        Uri uri = FileProvider.getUriForFile(
                context, context.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
