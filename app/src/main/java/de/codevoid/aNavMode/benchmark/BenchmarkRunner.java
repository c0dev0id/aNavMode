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

    private static final long  SETTLE_MS         = 2500;
    private static final long  RUN_MS            = 5000;
    private static final float PAN_RADIUS_PX     = 150f;
    private static final float PAN_PERIOD_MS     = 8000f;
    private static final long  JANK_THRESHOLD_NS = 20_000_000L; // 20 ms

    public interface Listener {
        void onProgress(int current, int total, BenchmarkConfig config);
        void onComplete(List<BenchmarkResult> results);
    }

    private enum State { IDLE, LOADING, SETTLING, RUNNING, STOPPING }

    private final MapView    mapView;
    private final MapManager mapManager;
    private final Handler    mainHandler = new Handler(Looper.getMainLooper());

    private List<BenchmarkConfig> configs;
    private List<BenchmarkResult> results;
    private int      configIndex;
    private Listener listener;

    private LatLong startPosition;

    private int   savedThreads;
    private float savedCacheCapacity;
    private float savedOverdraw;
    private int   savedTileSize;

    private State state = State.IDLE;
    private long  stateStartNs;

    // Per-run frame metrics.
    private long  frameCount;
    private long  prevFrameNs;
    private long  maxFrameNs;
    private int   jankFrames;

    // FPS cap gate.
    private long targetFrameIntervalNs;
    private long lastPanNs;

    public BenchmarkRunner(MapView mapView, MapManager mapManager) {
        this.mapView    = mapView;
        this.mapManager = mapManager;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public boolean isRunning() { return state != State.IDLE; }

    public LatLong getStartPosition() { return startPosition; }

    public void start(List<BenchmarkConfig> matrix, Listener listener) {
        if (state != State.IDLE) return;

        this.listener    = listener;
        this.configs     = matrix;
        this.results     = new ArrayList<>();
        this.configIndex = 0;

        startPosition = mapView.getModel().mapViewPosition.getCenter();

        savedThreads       = Parameters.NUMBER_OF_THREADS;
        savedCacheCapacity = mapManager.getCacheCapacity();
        savedOverdraw      = (float) mapView.getModel().frameBufferModel.getOverdrawFactor();
        savedTileSize      = mapView.getModel().displayModel.getTileSize();

        runNext();
    }

    public void stop() {
        if (state != State.IDLE) state = State.STOPPING;
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

        mapView.getModel().mapViewPosition.setCenter(startPosition);
        mapView.getModel().mapViewPosition.setZoomLevel(config.zoomLevel);

        state = State.LOADING;
        mapManager.reconfigure(
                config.threads, config.cacheCapacity, config.overdrawFactor, config.tileSize,
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

                // FPS cap: skip model updates that arrive too early.
                if (targetFrameIntervalNs > 0 && lastPanNs != 0
                        && (frameTimeNanos - lastPanNs) < targetFrameIntervalNs) {
                    if (elapsedMs < RUN_MS) Choreographer.getInstance().postFrameCallback(this);
                    else recordResult();
                    return;
                }

                // Drive circular pan.
                double angle   = 2 * Math.PI * elapsedMs / PAN_PERIOD_MS;
                float  offsetX = (float) (PAN_RADIUS_PX * Math.cos(angle));
                float  offsetY = (float) (PAN_RADIUS_PX * Math.sin(angle));
                applyPanOffset(offsetX, offsetY);
                lastPanNs = frameTimeNanos;

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
        BenchmarkConfig config = configs.get(configIndex);
        targetFrameIntervalNs = config.targetFps > 0 ? 1_000_000_000L / config.targetFps : 0L;

        state        = State.RUNNING;
        stateStartNs = frameTimeNanos;
        frameCount   = 0;
        prevFrameNs  = 0;
        maxFrameNs   = 0;
        jankFrames   = 0;
        lastPanNs    = 0;
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
        mapManager.reconfigure(savedThreads, savedCacheCapacity, savedOverdraw, savedTileSize,
                new MapManager.LoadCallback() {
                    @Override public void onLoaded() {}
                    @Override public void onError(String r) {}
                });
        listener.onComplete(results);
    }

    // -------------------------------------------------------------------------
    // Matrices
    // -------------------------------------------------------------------------

    /**
     * Round 1: full parameter sweep across all combinations.
     * 3 threads × 2 caches × 2 overdraws × 2 tile sizes × 2 zoom levels = 48 runs
     */
    public static List<BenchmarkConfig> buildRound1Matrix() {
        int[]   threads   = {1, 2, 4};
        float[] caches    = {1f, 2f};
        float[] overdraws = {1.0f, 1.2f};
        int[]   tiles     = {256, 512};
        byte[]  zooms     = {14, 17};

        List<BenchmarkConfig> list = new ArrayList<>();
        for (byte zoom : zooms)
            for (int tile : tiles)
                for (float overdraw : overdraws)
                    for (float cache : caches)
                        for (int thread : threads)
                            list.add(new BenchmarkConfig(thread, cache, overdraw, tile, zoom, 0));
        return list;
    }

    /**
     * Round 2: pushes parameters in the winning direction from Round 1.
     * Drops known weak settings; adds fps cap, larger tiles, more threads, higher overdraw.
     *
     * Round 1 findings:
     *   - cache=2x always won → fixed at 2x
     *   - tileSize=512 beat 256 at zoom 17 → push to 512/768/1024
     *   - threads=4 won at zoom 17, threads=1-2 won at zoom 14
     *   - overdraw=1.2 beat 1.0 → probe higher values
     *   - fps cap: test 30fps to reduce CPU pressure from redundant model updates
     *
     * 38 runs total.
     */
    public static List<BenchmarkConfig> buildRound2Matrix() {
        List<BenchmarkConfig> list = new ArrayList<>();

        // Zoom 17: push tile size and thread count; probe fps cap.
        // threads=1/2 dropped (lost at zoom 17); tileSize=256 dropped.
        int[]   z17threads = {4, 6, 8};
        int[]   z17tiles   = {512, 768, 1024};
        int[]   fpsCaps    = {0, 30};  // 0 = uncapped
        for (int fps : fpsCaps)
            for (int tile : z17tiles)
                for (int thread : z17threads)
                    list.add(new BenchmarkConfig(thread, 2f, 1.2f, tile, (byte) 17, fps));

        // Zoom 14: 1-2 threads won; try larger tiles and fps cap.
        // threads=4+ dropped (ranked 44-48 in round 1).
        int[] z14threads = {1, 2};
        int[] z14tiles   = {256, 512, 768};
        for (int fps : fpsCaps)
            for (int tile : z14tiles)
                for (int thread : z14threads)
                    list.add(new BenchmarkConfig(thread, 2f, 1.2f, tile, (byte) 14, fps));

        // Overdraw probe: fix winning config (t=4, tile=512, cache=2x, z=17), vary overdraw.
        float[] overdraws = {1.2f, 1.5f, 2.0f, 3.0f};
        for (int fps : fpsCaps)
            for (float ovrd : overdraws)
                list.add(new BenchmarkConfig(4, 2f, ovrd, 512, (byte) 17, fps));

        return list;
    }

    // -------------------------------------------------------------------------
    // Report
    // -------------------------------------------------------------------------

    public static String generateReport(List<BenchmarkResult> results, LatLong startPos,
                                        String roundLabel) {
        List<BenchmarkResult> sorted = new ArrayList<>(results);
        Collections.sort(sorted, (a, b) -> Float.compare(b.avgFps, a.avgFps));

        StringBuilder sb = new StringBuilder();
        sb.append("aNavMode Benchmark Report — ").append(roundLabel).append("\n");
        sb.append("Date:   ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date())).append("\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append(String.format(Locale.US, "Origin: %.5f, %.5f\n", startPos.latitude, startPos.longitude));
        sb.append("Run:    ").append(RUN_MS / 1000).append("s per config, ")
          .append(SETTLE_MS / 1000).append("s settle, ")
          .append(sorted.size()).append(" configs\n\n");

        sb.append(BenchmarkConfig.reportHeader()).append("\n");
        for (int i = 0; i < 78; i++) sb.append('-');
        sb.append("\n");

        int rank = 1;
        for (BenchmarkResult r : sorted) sb.append(r.reportRow(rank++)).append("\n");

        if (!sorted.isEmpty()) {
            BenchmarkResult best = sorted.get(0);
            BenchmarkConfig bc   = best.config;
            sb.append("\nBest: threads=").append(bc.threads)
              .append(", cache=").append(bc.cacheCapacity == 1f ? "1x" : "2x")
              .append(", overdraw=").append(bc.overdrawFactor)
              .append(", tileSize=").append(bc.tileSize)
              .append(", zoom=").append((int) bc.zoomLevel)
              .append(", fps=").append(bc.targetFps == 0 ? "max" : bc.targetFps)
              .append(" → ").append(String.format(Locale.US, "%.1f avg FPS", best.avgFps))
              .append("\n");
        }

        return sb.toString();
    }
}
