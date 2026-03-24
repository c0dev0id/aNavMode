package de.codevoid.aNavMode.benchmark;

public final class BenchmarkResult {
    public final BenchmarkConfig config;
    public final float avgFps;
    public final float minFps;   // 1 / longest-frame
    public final int   jankFrames;  // frames > 20 ms
    public final int   totalFrames;

    public BenchmarkResult(BenchmarkConfig config, float avgFps, float minFps,
                           int jankFrames, int totalFrames) {
        this.config      = config;
        this.avgFps      = avgFps;
        this.minFps      = minFps;
        this.jankFrames  = jankFrames;
        this.totalFrames = totalFrames;
    }

    public String reportRow(int rank) {
        float jankPct = totalFrames > 0 ? 100f * jankFrames / totalFrames : 0f;
        BenchmarkConfig c = config;
        return String.format("%-3d %-7d %-6s %-5.1f %-4d %-4d | %-7.1f %-7.1f %-5d %.1f%%",
                rank,
                c.threads,
                c.cacheCapacity == 1f ? "1x" : "2x",
                c.overdrawFactor,
                c.tileSize,
                (int) c.zoomLevel,
                avgFps,
                minFps,
                jankFrames,
                jankPct);
    }
}
