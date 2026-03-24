package de.codevoid.aNavMode.map;

import android.content.Context;
import android.view.View;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.MapPosition;
import org.mapsforge.core.util.Parameters;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.datastore.MultiMapDataStore;
import org.mapsforge.map.layer.cache.FileSystemTileCache;
import org.mapsforge.map.layer.cache.InMemoryTileCache;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.cache.TwoLevelTileCache;
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

    // In-memory tile count: ratio × visible tiles, used by benchmark sweeps.
    private float configCacheCapacity = 4f;
    // Disk cache: absolute tile count. 4000 tiles × ~512KB (512px RGB_565) ≈ 2GB.
    private static final int DISK_CACHE_TILES = 4000;

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
     * any regional .map files found under getFilesDir()/maps/ so that
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

                // In-memory layer: covers the current viewport + overdraw region.
                int tileSize  = mapView.getModel().displayModel.getTileSize();
                double overdraw = mapView.getModel().frameBufferModel.getOverdrawFactor();
                android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
                int cols = (int) Math.ceil(dm.widthPixels  * overdraw / tileSize) + 1;
                int rows = (int) Math.ceil(dm.heightPixels * overdraw / tileSize) + 1;
                int memCapacity = (int) Math.ceil(configCacheCapacity * cols * rows);
                InMemoryTileCache memCache = new InMemoryTileCache(memCapacity);

                // Disk layer: large persistent cache on internal storage (never auto-evicted).
                // Skips CPU rasterization on revisits; ~512KB/tile × 4000 ≈ 2GB.
                File cacheDir = new File(context.getFilesDir(), "tilecache");
                cacheDir.mkdirs();
                FileSystemTileCache diskCache = new FileSystemTileCache(
                        DISK_CACHE_TILES, cacheDir, AndroidGraphicFactory.INSTANCE, true);

                TileCache cache = new TwoLevelTileCache(memCache, diskCache);

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

    private List<File> findRegionalMaps() {
        List<File> result = new ArrayList<>();
        File mapsDir = new File(context.getFilesDir(), "maps");
        if (mapsDir.isDirectory()) scanMaps(mapsDir, result);
        return result;
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
