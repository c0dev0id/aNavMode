package de.codevoid.aNavMode.benchmark;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.core.util.Parameters;
import org.mapsforge.map.android.view.MapView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.codevoid.aNavMode.map.MapManager;

public class BenchmarkRunner implements Choreographer.FrameCallback {

    private static final long  SETTLE_MS       = 2500;
    private static final long  RUN_MS          = 5000;
    private static final float PAN_RADIUS_PX   = 150f;
    private static final float PAN_PERIOD_MS   = 8000f;
    private static final long  JANK_THRESHOLD_NS = 20_000_000L; // 20 ms

    public interface Listener {
        /** Called on the main thread before each run starts. */
        void onProgress(int current, int total, BenchmarkConfig config);
        /** Called on the main thread when all runs (or a graceful stop) finish. */
        void onComplete(List<BenchmarkResult> results);
    }

    private enum State { IDLE, LOADING, SETTLING, RUNNING, STOPPING }

    private final MapView    mapView;
    private final MapManager mapManager;
    private final Handler    mainHandler = new Handler(Looper.getMainLooper());

    private List<BenchmarkConfig> configs;
    private List<BenchmarkResult> results;
    private int     configIndex;
    private Listener listener;

    // Position captured when benchmark starts; all runs begin here.
    private LatLong startPosition;

    // Settings before the benchmark, restored on completion.
    private int   savedThreads;
    private float savedCacheCapacity;
    private float savedOverdraw;
    private int   savedTileSize;

    private State state = State.IDLE;
    private long  stateStartNs;

    // Per-run frame metrics.
    private long  frameCount;
    private long  prevFrameNs;
    private long  maxFrameNs;  // slowest frame = drives minFps
    private int   jankFrames;

    public BenchmarkRunner(MapView mapView, MapManager mapManager) {
        this.mapView    = mapView;
        this.mapManager = mapManager;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public boolean isRunning() {
        return state != State.IDLE;
    }

    /** Returns the position used as the centre of all benchmark runs. */
    public LatLong getStartPosition() {
        return startPosition;
    }

    /** Start the full benchmark from the current map position. */
    public void start(Listener listener) {
        if (state != State.IDLE) return;

        this.listener    = listener;
        this.configs     = buildMatrix();
        this.results     = new ArrayList<>();
        this.configIndex = 0;

        startPosition = mapView.getModel().mapViewPosition.getCenter();

        savedThreads       = Parameters.NUMBER_OF_THREADS;
        savedCacheCapacity = mapManager.getCacheCapacity();
        savedOverdraw      = (float) mapView.getModel().frameBufferModel.getOverdrawFactor();
        savedTileSize      = mapView.getModel().displayModel.getTileSize();

        runNext();
    }

    /** Request a graceful stop after the current run completes. */
    public void stop() {
        if (state != State.IDLE) state = State.STOPPING;
    }

    public int totalRuns() {
        return configs != null ? configs.size() : countMatrix();
    }

    // -------------------------------------------------------------------------
    // State machine
    // -------------------------------------------------------------------------

    private void runNext() {
        if (state == State.STOPPING || configIndex >= configs.size()) {
            finish();
            return;
        }

        BenchmarkConfig config = configs.get(configIndex);
        listener.onProgress(configIndex + 1, configs.size(), config);

        // Snap to start position + zoom before reloading tiles.
        mapView.getModel().mapViewPosition.setCenter(startPosition);
        mapView.getModel().mapViewPosition.setZoomLevel(config.zoomLevel);

        state = State.LOADING;
        mapManager.reconfigure(
                config.threads,
                config.cacheCapacity,
                config.overdrawFactor,
                config.tileSize,
                new MapManager.LoadCallback() {
                    @Override public void onLoaded() { mainHandler.post(() -> beginSettling()); }
                    @Override public void onError(String r) { mainHandler.post(() -> beginSettling()); }
                });
    }

    private void beginSettling() {
        if (state == State.STOPPING) { finish(); return; }
        state        = State.SETTLING;
        stateStartNs = System.nanoTime();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        switch (state) {
            case SETTLING: {
                long elapsedMs = (frameTimeNanos - stateStartNs) / 1_000_000L;
                if (elapsedMs >= SETTLE_MS) {
                    beginRunning(frameTimeNanos);
                } else {
                    Choreographer.getInstance().postFrameCallback(this);
                }
                break;
            }
            case RUNNING: {
                long elapsedMs = (frameTimeNanos - stateStartNs) / 1_000_000L;

                // Drive circular pan around the fixed start point.
                double angle   = 2 * Math.PI * elapsedMs / PAN_PERIOD_MS;
                float  offsetX = (float) (PAN_RADIUS_PX * Math.cos(angle));
                float  offsetY = (float) (PAN_RADIUS_PX * Math.sin(angle));
                applyPanOffset(offsetX, offsetY);

                // Record inter-frame gap.
                if (prevFrameNs != 0) {
                    long dtNs = frameTimeNanos - prevFrameNs;
                    if (dtNs > JANK_THRESHOLD_NS) jankFrames++;
                    if (dtNs > maxFrameNs) maxFrameNs = dtNs;
                }
                prevFrameNs = frameTimeNanos;
                frameCount++;

                if (elapsedMs >= RUN_MS) {
                    recordResult();
                } else {
                    Choreographer.getInstance().postFrameCallback(this);
                }
                break;
            }
            default:
                break;
        }
    }

    private void beginRunning(long frameTimeNanos) {
        state        = State.RUNNING;
        stateStartNs = frameTimeNanos;
        frameCount   = 0;
        prevFrameNs  = 0;
        maxFrameNs   = 0;
        jankFrames   = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void applyPanOffset(float screenDx, float screenDy) {
        org.mapsforge.map.model.MapViewPosition pos = mapView.getModel().mapViewPosition;
        byte zoom     = pos.getZoomLevel();
        int  tileSize = mapView.getModel().displayModel.getTileSize();
        long mapSize  = MercatorProjection.getMapSize(zoom, tileSize);

        double px = MercatorProjection.longitudeToPixelX(startPosition.longitude, mapSize) + screenDx;
        double py = MercatorProjection.latitudeToPixelY(startPosition.latitude,   mapSize) + screenDy;
        pos.setCenter(new LatLong(
                MercatorProjection.pixelYToLatitude(py,  mapSize),
                MercatorProjection.pixelXToLongitude(px, mapSize)));
    }

    private void recordResult() {
        BenchmarkConfig config = configs.get(configIndex);
        float avgFps = frameCount / (RUN_MS / 1000f);
        float minFps = maxFrameNs > 0 ? 1_000_000_000f / maxFrameNs : 0f;
        results.add(new BenchmarkResult(config, avgFps, minFps, jankFrames, (int) frameCount));
        configIndex++;
        runNext();
    }

    private void finish() {
        state = State.IDLE;
        // Restore the settings that were active before the benchmark.
        mapManager.reconfigure(savedThreads, savedCacheCapacity, savedOverdraw, savedTileSize,
                new MapManager.LoadCallback() {
                    @Override public void onLoaded() {}
                    @Override public void onError(String r) {}
                });
        listener.onComplete(results);
    }

    // -------------------------------------------------------------------------
    // Matrix
    // -------------------------------------------------------------------------

    private static final int[]   THREADS   = {1, 2, 4};
    private static final float[] CACHES    = {1f, 2f};
    private static final float[] OVERDRAWS = {1.0f, 1.2f};
    private static final int[]   TILESIZES = {256, 512};
    private static final byte[]  ZOOMS     = {14, 17};

    private static List<BenchmarkConfig> buildMatrix() {
        List<BenchmarkConfig> list = new ArrayList<>();
        for (byte zoom : ZOOMS)
            for (int tileSize : TILESIZES)
                for (float overdraw : OVERDRAWS)
                    for (float cache : CACHES)
                        for (int threads : THREADS)
                            list.add(new BenchmarkConfig(threads, cache, overdraw, tileSize, zoom));
        return list;
    }

    private static int countMatrix() {
        return THREADS.length * CACHES.length * OVERDRAWS.length * TILESIZES.length * ZOOMS.length;
    }

    // -------------------------------------------------------------------------
    // Report
    // -------------------------------------------------------------------------

    public static String generateReport(List<BenchmarkResult> results, LatLong startPos) {
        List<BenchmarkResult> sorted = new ArrayList<>(results);
        Collections.sort(sorted, (a, b) -> Float.compare(b.avgFps, a.avgFps));

        StringBuilder sb = new StringBuilder();
        sb.append("aNavMode Benchmark Report\n");
        sb.append("Date:   ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date())).append("\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append(String.format(Locale.US, "Origin: %.5f, %.5f\n", startPos.latitude, startPos.longitude));
        sb.append("Run:    ").append(RUN_MS / 1000).append("s per config, ")
          .append(SETTLE_MS / 1000).append("s settle, ")
          .append(sorted.size()).append(" / ").append(countMatrix()).append(" configs\n");
        sb.append("\n");
        sb.append(BenchmarkConfig.reportHeader()).append("\n");
        for (int i = 0; i < 72; i++) sb.append('-');
        sb.append("\n");

        int rank = 1;
        for (BenchmarkResult r : sorted) {
            sb.append(r.reportRow(rank++)).append("\n");
        }

        if (!sorted.isEmpty()) {
            BenchmarkResult best = sorted.get(0);
            BenchmarkConfig bc   = best.config;
            sb.append("\nBest: threads=").append(bc.threads)
              .append(", cache=").append(bc.cacheCapacity == 1f ? "1x" : "2x")
              .append(", overdraw=").append(bc.overdrawFactor)
              .append(", tileSize=").append(bc.tileSize)
              .append(", zoom=").append((int) bc.zoomLevel)
              .append(" → ").append(String.format(Locale.US, "%.1f avg FPS", best.avgFps))
              .append("\n");
        }

        return sb.toString();
    }
}
