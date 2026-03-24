package de.codevoid.aNavMode.map;

import android.content.Context;
import android.os.Environment;
import android.view.View;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.MapPosition;
import org.mapsforge.core.util.Parameters;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.datastore.MultiMapDataStore;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MapManager {

    private final Context context;
    private final MapView mapView;
    private TileRendererLayer tileLayer;
    private TileCache         tileCache;

    private float configCacheCapacity = 2f;

    public MapManager(Context context, MapView mapView) {
        this.context = context;
        this.mapView = mapView;
        mapView.getMapScaleBar().setVisible(true);

        // Apply defaults derived from benchmark results.
        Parameters.NUMBER_OF_THREADS = 4;
        mapView.getModel().displayModel.setFixedTileSize(512);
        mapView.getModel().frameBufferModel.setOverdrawFactor(1.2);
        // Round 3: hardware layer moves tile compositing to GPU → 60fps vs ~13fps SW.
        mapView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    public interface LoadCallback {
        void onLoaded();
        void onError(String reason);
    }

    /**
     * Loads (or reloads) the tile layer.
     *
     * Always includes the bundled world.map as the base layer, then overlays
     * any regional .map files found under /sdcard/aNavMode/maps/ so that
     * downloaded regions show detail while blank areas fall back to the world map.
     *
     * Safe to call again after new maps are downloaded — tears down the old
     * tile layer first.
     */
    public void loadMapAsync(LoadCallback callback) {
        new Thread(() -> {
            // Tear down the old layer if reloading
            if (tileLayer != null) {
                mapView.getLayerManager().getLayers().remove(tileLayer);
                tileLayer.onDestroy();
                tileLayer = null;
            }
            if (tileCache != null) {
                tileCache.destroy();
                tileCache = null;
            }

            try {
                MultiMapDataStore multi =
                        new MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL);

                // Base: bundled world map (always present)
                File world = worldMapFile();
                if (world.exists()) multi.addMapDataStore(new MapFile(world), false, false);

                // Overlay: any downloaded regional maps
                for (File f : findRegionalMaps()) {
                    multi.addMapDataStore(new MapFile(f), false, false);
                }

                TileCache cache = AndroidUtil.createTileCache(
                        context, "maincache",
                        mapView.getModel().displayModel.getTileSize(),
                        configCacheCapacity,
                        mapView.getModel().frameBufferModel.getOverdrawFactor());

                TileRendererLayer layer = new TileRendererLayer(
                        cache, multi,
                        mapView.getModel().mapViewPosition,
                        AndroidGraphicFactory.INSTANCE);
                layer.setXmlRenderTheme(MapsforgeThemes.DEFAULT);

                mapView.getLayerManager().getLayers().add(0, layer);
                tileCache = cache;
                tileLayer = layer;
                callback.onLoaded();
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        }, "map-loader").start();
    }

    /** Returns the internal maps directory (primary location). */
    public File getInternalMapsDir() {
        return new File(context.getFilesDir(), "maps");
    }

    /** Returns the legacy external maps directory (used only for migration). */
    public File getLegacyExternalMapsDir() {
        return new File(Environment.getExternalStorageDirectory(), "aNavMode/maps");
    }

    /**
     * Scans the internal maps directory for downloaded .map files.
     */
    private List<File> findRegionalMaps() {
        List<File> result = new ArrayList<>();
        File mapsDir = getInternalMapsDir();
        if (mapsDir.isDirectory()) scanMaps(mapsDir, result);
        return result;
    }

    public interface MigrationCallback {
        /** Called on the background thread; marshal to UI if needed. */
        void onProgress(String filename, int done, int total);
        void onComplete(int migrated, int skipped);
        void onError(String reason);
    }

    /**
     * Copies .map files from the legacy external location to internal storage.
     * Runs on a background thread; callbacks are invoked from that thread.
     */
    public void migrateFromExternal(MigrationCallback cb) {
        new Thread(() -> {
            File src = getLegacyExternalMapsDir();
            if (!src.isDirectory()) { cb.onComplete(0, 0); return; }

            List<File> found = new ArrayList<>();
            scanMaps(src, found);
            if (found.isEmpty()) { cb.onComplete(0, 0); return; }

            File dst = getInternalMapsDir();
            dst.mkdirs();

            int migrated = 0, skipped = 0;
            for (int i = 0; i < found.size(); i++) {
                File from = found.get(i);
                // Preserve relative path under the maps dir.
                String rel  = from.getAbsolutePath()
                        .substring(src.getAbsolutePath().length() + 1);
                File   to   = new File(dst, rel);
                cb.onProgress(from.getName(), i + 1, found.size());
                if (to.exists()) { skipped++; continue; }
                to.getParentFile().mkdirs();
                try {
                    copyFile(from, to);
                    migrated++;
                } catch (Exception e) {
                    cb.onError("Failed to copy " + from.getName() + ": " + e.getMessage());
                    return;
                }
            }
            cb.onComplete(migrated, skipped);
        }, "map-migrate").start();
    }

    private static void copyFile(File from, File to) throws Exception {
        try (java.io.InputStream  in  = new java.io.FileInputStream(from);
             java.io.OutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private void scanMaps(File dir, List<File> out) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File f : entries) {
            if (f.isDirectory()) scanMaps(f, out);
            else if (f.getName().endsWith(".map")) out.add(f);
        }
    }

    /**
     * Seeds world.map from assets into internal storage on first use.
     */
    private File worldMapFile() {
        File dest = new File(context.getFilesDir(), "world.map");
        if (!dest.exists()) {
            try (InputStream  in  = context.getAssets().open("world.map");
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } catch (Exception e) {
                dest.delete();
            }
        }
        return dest;
    }

    /**
     * Applies new rendering parameters and rebuilds the tile layer.
     * Safe to call from the main thread; the layer rebuild runs on a background thread.
     */
    public void reconfigure(int threads, float cacheCapacity, float overdrawFactor,
                            int tileSize, LoadCallback callback) {
        Parameters.NUMBER_OF_THREADS = threads;
        configCacheCapacity = cacheCapacity;
        mapView.getModel().displayModel.setFixedTileSize(tileSize);
        mapView.getModel().frameBufferModel.setOverdrawFactor(overdrawFactor);
        loadMapAsync(callback);
    }

    public float getCacheCapacity() {
        return configCacheCapacity;
    }

    public void setInitialPosition() {
        mapView.getModel().mapViewPosition.setMapPosition(
                new MapPosition(new LatLong(0.0, 0.0), (byte) 3)
        );
    }

    public void destroy() {
        if (tileLayer != null) {
            mapView.getLayerManager().getLayers().remove(tileLayer);
            tileLayer.onDestroy();
        }
        if (tileCache != null) tileCache.destroy();
    }
}
