package de.codevoid.aNavMode.benchmark;

public final class BenchmarkConfig {
    public final int     threads;
    public final float   cacheCapacity;
    public final float   overdrawFactor;
    public final int     tileSize;
    public final byte    zoomLevel;
    public final int     targetFps;      // 0 = uncapped (full vsync rate)
    public final boolean hardwareLayer;  // true = LAYER_TYPE_HARDWARE

    public BenchmarkConfig(int threads, float cacheCapacity, float overdrawFactor,
                           int tileSize, byte zoomLevel, int targetFps, boolean hardwareLayer) {
        this.threads        = threads;
        this.cacheCapacity  = cacheCapacity;
        this.overdrawFactor = overdrawFactor;
        this.tileSize       = tileSize;
        this.zoomLevel      = zoomLevel;
        this.targetFps      = targetFps;
        this.hardwareLayer  = hardwareLayer;
    }

    /** Convenience constructor for Round 1/2 configs (no hardware-layer variation). */
    public BenchmarkConfig(int threads, float cacheCapacity, float overdrawFactor,
                           int tileSize, byte zoomLevel, int targetFps) {
        this(threads, cacheCapacity, overdrawFactor, tileSize, zoomLevel, targetFps, false);
    }

    public String label() {
        return "t=" + threads
             + " cache=" + (cacheCapacity == 1f ? "1x" : "2x")
             + " ovrd=" + overdrawFactor
             + " tile=" + tileSize
             + " z=" + zoomLevel
             + " fps=" + (targetFps == 0 ? "max" : targetFps)
             + (hardwareLayer ? " hw=yes" : "");
    }

    public static String reportHeader() {
        return String.format("%-3s %-7s %-6s %-5s %-4s %-4s %-5s %-3s | %-7s %-7s %-5s %s",
                "#", "threads", "cache", "ovrd", "tile", "zoom", "fps", "hw",
                "avgFPS", "minFPS", "jank", "jank%");
    }
}
