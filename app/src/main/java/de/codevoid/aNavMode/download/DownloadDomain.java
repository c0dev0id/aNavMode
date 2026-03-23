package de.codevoid.aNavMode.download;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the map/segment download queue.
 *
 * Runs a sequential FIFO download loop on its own background thread.
 * Publishes immutable State snapshots to subscribers from the worker thread —
 * listeners must marshal to the UI thread themselves.
 *
 * Download steps per region:
 *   1. Refresh catalog (fresh index.json).
 *   2. HEAD every file to detect captive portals and gather sizes.
 *   3. Download each file sequentially; resume via partial-file naming.
 *   4. Download needed routing tiles (shared pool, skip if already current).
 *   5. Each file is atomically renamed into place on completion.
 *
 * All public mutation methods are safe to call from any thread.
 */
public class DownloadDomain {

    public static final String MIRROR_BASE = "https://u565435-sub2.your-storagebox.de";

    private static final String MIRROR_AUTH = buildAuth();

    private static String buildAuth() {
        String user = de.codevoid.aNavMode.BuildConfig.MIRROR_USER;
        String key  = de.codevoid.aNavMode.BuildConfig.MIRROR_KEY;
        if (user.isEmpty() || key.isEmpty()) return null;
        return "Basic " + java.util.Base64.getEncoder()
                .encodeToString((user + ":" + key).getBytes());
    }

    private static final String TAG            = "DownloadDomain";
    private static final String PREFS_NAME     = "download";
    private static final String PREF_MOBILE    = "allow_mobile_data";
    private static final int    CONNECT_MS     = 10_000;
    private static final int    READ_MS        = 60_000;
    private static final int    BUF            = 32_768;
    private static final long   SPEED_WINDOW_MS = 3_000;

    // -------------------------------------------------------------------------
    // Data model
    // -------------------------------------------------------------------------

    public enum RegionStatus { QUEUED, ACTIVE }

    public static final class RegionDownload {
        public final String       regionId;
        public final String       regionName;
        public final RegionStatus status;
        public final long         bytesTotal;
        public final long         bytesDownloaded;
        /** Filename currently in flight; null when queued. */
        public final String       currentFile;

        RegionDownload(String regionId, String regionName, RegionStatus status,
                       long bytesTotal, long bytesDownloaded, String currentFile) {
            this.regionId        = regionId;
            this.regionName      = regionName;
            this.status          = status;
            this.bytesTotal      = bytesTotal;
            this.bytesDownloaded = bytesDownloaded;
            this.currentFile     = currentFile;
        }
    }

    public static final class State {
        /** Active region first, then queued in FIFO order. */
        public final List<RegionDownload> queue;
        public final long                 speedBytesPerSec;

        public static final State EMPTY =
                new State(Collections.emptyList(), 0);

        State(List<RegionDownload> queue, long speedBytesPerSec) {
            this.queue            = Collections.unmodifiableList(queue);
            this.speedBytesPerSec = speedBytesPerSec;
        }
    }

    public interface Listener {
        /**
         * Called from the download worker thread.
         * Marshal to the UI thread for any UI updates.
         */
        void onStateChanged(State state);
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private static volatile DownloadDomain instance;

    private final Context                        context;
    private final DownloadCatalog                catalogHelper;
    private final ExecutorService                executor;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SharedPreferences              prefs;

    // Worker-thread state — only accessed on executor thread
    private final List<String>      queueIds   = new ArrayList<>();
    private boolean                 processing = false;
    private volatile DownloadCatalog.Catalog catalog;

    // Active-download progress — written on worker, read anywhere
    private volatile String activeRegionId       = null;
    private volatile long   activeBytesTotal     = 0;
    private volatile long   activeBytesDownloaded = 0;
    private volatile String activeFile           = null;

    // Speed tracking — worker thread only
    private long          speedWindowStartMs = 0;
    private long          speedWindowBytes   = 0;
    private volatile long speedBytesPerSec   = 0;

    // Network state — written from ConnectivityManager callback, read on worker
    private volatile boolean networkAvailable = true;
    private volatile boolean onMobileData     = false;

    private ConnectivityManager.NetworkCallback networkCallback;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public DownloadDomain(Context context, DownloadCatalog.Catalog initialCatalog) {
        this.context       = context.getApplicationContext();
        this.catalog       = initialCatalog;
        this.catalogHelper = new DownloadCatalog(context);
        this.prefs         = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.executor      = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "download-worker"));
        instance = this;
        registerNetworkCallback();
    }

    public static DownloadDomain getInstance() { return instance; }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void addListener(Listener l)    { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public boolean isMobileDataAllowed() {
        return prefs.getBoolean(PREF_MOBILE, false);
    }

    public void setMobileDataAllowed(boolean allowed) {
        prefs.edit().putBoolean(PREF_MOBILE, allowed).apply();
    }

    public enum Availability { NOT_DOWNLOADED, UPDATE_AVAILABLE, CURRENT }

    /**
     * Checks whether the region's files are present and up-to-date on disk.
     * Safe to call from any thread.
     */
    public Availability getAvailability(DownloadCatalog.Region region) {
        DownloadCatalog.Catalog snap = catalog;
        boolean allPresent  = true;
        boolean anyOutdated = false;
        for (DownloadCatalog.CatalogFile f : region.files) {
            File local = localFile(f.path);
            if (!local.exists()) { allPresent = false; break; }
            if (needsDownload(local, f.modified)) anyOutdated = true;
        }
        if (allPresent) {
            for (String coord : region.tiles) {
                DownloadCatalog.Tile tile = snap.tiles.get(coord);
                if (tile == null) continue;
                File local = localFile(tile.path);
                if (!local.exists()) { allPresent = false; break; }
                if (needsDownload(local, tile.modified)) anyOutdated = true;
            }
        }
        if (!allPresent)  return Availability.NOT_DOWNLOADED;
        if (anyOutdated)  return Availability.UPDATE_AVAILABLE;
        return Availability.CURRENT;
    }

    /** Adds a region to the download queue (no-op if already queued). */
    public void enqueue(String regionId) {
        executor.execute(() -> {
            if (queueIds.contains(regionId)) return;
            queueIds.add(regionId);
            publishState();
            if (!processing) {
                processing = true;
                startService();
                processQueue();
                processing = false;
                if (queueIds.isEmpty()) stopService();
            }
        });
    }

    public void destroy() {
        instance = null;
        executor.shutdownNow();
        unregisterNetworkCallback();
    }

    // -------------------------------------------------------------------------
    // Download loop — runs on worker thread
    // -------------------------------------------------------------------------

    private void processQueue() {
        while (!queueIds.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) break;

            String regionId = queueIds.get(0);
            activeRegionId = regionId;
            publishState();

            try {
                downloadRegion(regionId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                activeRegionId = null;
                publishState();
                return;
            } catch (Exception e) {
                Log.e(TAG, "download failed for " + regionId + ": " + e.getMessage());
            }

            activeRegionId        = null;
            activeBytesTotal      = 0;
            activeBytesDownloaded = 0;
            activeFile            = null;
            speedBytesPerSec      = 0;
            queueIds.remove(0);
            publishState();
        }
    }

    private void downloadRegion(String regionId) throws Exception {
        Log.d(TAG, "downloadRegion start: " + regionId);
        if (!isNetworkOk()) throw new IOException("no suitable network");
        Log.d(TAG, "network ok, mobile=" + onMobileData);

        // Refresh catalog before starting
        try {
            catalog = catalogHelper.refresh(MIRROR_BASE, MIRROR_AUTH);
        } catch (Exception e) {
            Log.w(TAG, "catalog refresh failed, using cached: " + e.getMessage());
        }

        DownloadCatalog.Region region = findRegion(regionId);
        if (region == null) throw new IllegalArgumentException("unknown region: " + regionId);
        Log.d(TAG, "region found: " + region.name + ", files=" + region.files.size() + ", tiles=" + region.tiles.size());

        // ---- Phase 1: HEAD all files to compute total size ----
        List<String[]> downloads = new ArrayList<>(); // [url, localPath, mirrorPath]
        long total = 0;

        for (DownloadCatalog.CatalogFile f : region.files) {
            File local = localFile(f.path);
            if (!needsDownload(local, f.modified)) { Log.d(TAG, "skip (current): " + f.path); continue; }
            String url = MIRROR_BASE + "/" + f.path;
            Log.d(TAG, "HEAD " + url);
            long size = headFile(url); // throws on captive portal; -1 = unknown size
            Log.d(TAG, "HEAD ok, size=" + size);
            if (size > 0) total += size;
            downloads.add(new String[]{url, local.getAbsolutePath(), f.path});
        }

        for (String tileCoord : region.tiles) {
            DownloadCatalog.Tile tile = catalog.tiles.get(tileCoord);
            if (tile == null) continue;
            File local = localFile(tile.path);
            if (!needsDownload(local, tile.modified)) continue;
            String url = MIRROR_BASE + "/" + tile.path;
            long size = headFile(url); // throws on captive portal; -1 = unknown size
            if (size > 0) total += size;
            downloads.add(new String[]{url, local.getAbsolutePath(), tile.path});
        }

        activeBytesTotal = total;
        speedWindowStartMs = System.currentTimeMillis();
        speedWindowBytes   = 0;
        publishState();

        // ---- Phase 2: Download each file ----
        for (String[] d : downloads) {
            if (!isNetworkOk()) throw new IOException("network lost");
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();

            activeFile = new File(d[1]).getName();
            publishState();

            File dest = new File(d[1]);
            dest.getParentFile().mkdirs();
            downloadFile(d[0], dest);
        }
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    /**
     * Issues a HEAD request. Returns Content-Length, or -1 if the server did
     * not send one (chunked / unknown size). Throws IOException if the response
     * looks like a captive portal (HTML content type).
     */
    private long headFile(String urlStr) throws IOException {
        HttpURLConnection conn = open(urlStr);
        conn.setRequestMethod("HEAD");
        conn.connect();
        int status = conn.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new IOException("HEAD " + status + " for " + urlStr);
        }
        String ct   = conn.getContentType();
        long   size = conn.getContentLengthLong();
        conn.disconnect();
        if (ct != null && ct.contains("text/html"))
            throw new IOException("captive portal detected at " + urlStr);
        return size; // may be -1 if no Content-Length header
    }

    /**
     * Downloads urlStr to dest, resuming from a partial temp file if present.
     * Partial file is named {@code dest.getName() + ".download." + remoteSize}.
     * Atomically renames temp to dest on success.
     */
    private void downloadFile(String urlStr, File dest) throws IOException, InterruptedException {
        // Find an existing partial file for this destination
        String prefix = dest.getName() + ".download.";
        File[] partials = dest.getParentFile().listFiles(
                (dir, name) -> name.startsWith(prefix));

        long remoteSize = -1;
        File temp       = null;
        long partialBytes = 0;

        // Use the first partial that matches a valid size
        if (partials != null) {
            for (File p : partials) {
                String suffix = p.getName().substring(prefix.length());
                try {
                    long encodedSize = Long.parseLong(suffix);
                    // HEAD to confirm remote hasn't changed (throws on captive portal)
                    remoteSize = headFile(urlStr);
                    if (encodedSize == remoteSize) {
                        temp         = p;
                        partialBytes = p.length();
                    } else {
                        p.delete(); // stale partial — different remote size
                    }
                    break;
                } catch (NumberFormatException ignored) {
                    p.delete();
                }
            }
        }

        if (temp == null) {
            if (remoteSize < 0) remoteSize = headFile(urlStr); // throws on captive portal
            // If Content-Length is unknown (-1), skip resume — download fresh with a plain temp name
            temp = remoteSize > 0
                    ? new File(dest.getParent(), prefix + remoteSize)
                    : new File(dest.getParent(), dest.getName() + ".tmp");
        }

        // Download (or resume)
        HttpURLConnection conn = open(urlStr);
        if (partialBytes > 0) {
            conn.setRequestProperty("Range", "bytes=" + partialBytes + "-");
        }
        conn.connect();
        int status = conn.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
            conn.disconnect();
            throw new IOException("GET " + status + " for " + urlStr);
        }

        try (InputStream in   = conn.getInputStream();
             OutputStream out = new FileOutputStream(temp, partialBytes > 0)) {
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (!isNetworkOk()) throw new IOException("network lost");
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                out.write(buf, 0, n);
                activeBytesDownloaded += n;
                trackSpeed(n);
            }
        } finally {
            conn.disconnect();
        }

        // Atomic rename
        if (!temp.renameTo(dest)) {
            copyAndDelete(temp, dest);
        }
    }

    private HttpURLConnection open(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_MS);
        conn.setReadTimeout(READ_MS);
        if (MIRROR_AUTH != null) conn.setRequestProperty("Authorization", MIRROR_AUTH);
        return conn;
    }

    // -------------------------------------------------------------------------
    // Path helpers
    // -------------------------------------------------------------------------

    private File localFile(String mirrorPath) {
        // segments4/* → brouter/segments4/*; everything else maps directly
        String localPath = mirrorPath.startsWith("segments4/")
                ? "brouter/" + mirrorPath
                : mirrorPath;
        return new File(
                new File(Environment.getExternalStorageDirectory(), "aNavMode"),
                localPath);
    }

    private boolean needsDownload(File local, String catalogModified) {
        if (!local.exists()) return true;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            long remoteMs = sdf.parse(catalogModified).getTime();
            return local.lastModified() < remoteMs;
        } catch (ParseException e) {
            return true;
        }
    }

    // -------------------------------------------------------------------------
    // Speed tracking
    // -------------------------------------------------------------------------

    private void trackSpeed(long bytes) {
        speedWindowBytes += bytes;
        long now = System.currentTimeMillis();
        long elapsed = now - speedWindowStartMs;
        if (elapsed >= SPEED_WINDOW_MS) {
            speedBytesPerSec  = speedWindowBytes * 1000 / elapsed;
            speedWindowBytes  = 0;
            speedWindowStartMs = now;
            publishState();
        }
    }

    // -------------------------------------------------------------------------
    // State publishing
    // -------------------------------------------------------------------------

    private void publishState() {
        List<RegionDownload> list = new ArrayList<>(queueIds.size());
        for (String id : queueIds) {
            boolean active = id.equals(activeRegionId);
            list.add(new RegionDownload(
                    id,
                    regionName(id),
                    active ? RegionStatus.ACTIVE : RegionStatus.QUEUED,
                    active ? activeBytesTotal      : 0,
                    active ? activeBytesDownloaded : 0,
                    active ? activeFile            : null));
        }
        State s = new State(list, speedBytesPerSec);
        for (Listener l : listeners) l.onStateChanged(s);
    }

    private DownloadCatalog.Region findRegion(String id) {
        for (DownloadCatalog.Region r : catalog.regions)
            if (r.id.equals(id)) return r;
        return null;
    }

    private String regionName(String id) {
        DownloadCatalog.Region r = findRegion(id);
        return r != null ? r.name : id;
    }

    // -------------------------------------------------------------------------
    // Network
    // -------------------------------------------------------------------------

    private boolean isNetworkOk() {
        if (!networkAvailable) return false;
        if (onMobileData && !isMobileDataAllowed()) return false;
        return true;
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network net) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                onMobileData = caps != null
                        && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                networkAvailable = true;
                // Restart the download loop if queue is non-empty and idle
                executor.execute(() -> {
                    if (!queueIds.isEmpty() && !processing) {
                        processing = true;
                        startService();
                        processQueue();
                        processing = false;
                        if (queueIds.isEmpty()) stopService();
                    }
                });
            }
            @Override public void onLost(Network net) {
                networkAvailable = false;
            }
        };

        NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        cm.registerNetworkCallback(req, networkCallback);
    }

    private void unregisterNetworkCallback() {
        if (networkCallback == null) return;
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            try { cm.unregisterNetworkCallback(networkCallback); }
            catch (IllegalArgumentException ignored) {}
        }
        networkCallback = null;
    }

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    private void startService() {
        context.startForegroundService(
                new Intent(context, DownloadService.class));
    }

    private void stopService() {
        context.stopService(new Intent(context, DownloadService.class));
    }

    // -------------------------------------------------------------------------
    // IO helpers
    // -------------------------------------------------------------------------

    private static void copyAndDelete(File src, File dst) throws IOException {
        try (FileInputStream  in  = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        src.delete();
    }
}
