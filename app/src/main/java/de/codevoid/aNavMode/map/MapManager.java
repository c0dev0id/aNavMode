package de.codevoid.aNavMode.map;

import android.content.Context;
import android.os.Environment;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.MapPosition;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.datastore.MapDataStore;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.InternalRenderTheme;

import java.io.File;

public class MapManager {

    private final Context context;
    private final MapView mapView;
    private TileRendererLayer tileLayer;
    private TileCache tileCache;

    public MapManager(Context context, MapView mapView) {
        this.context = context;
        this.mapView = mapView;
        mapView.getMapScaleBar().setVisible(true);
    }

    public interface LoadCallback {
        /** Called from the background thread once the tile layer is live. */
        void onLoaded();
        /** Called from the background thread on any failure. */
        void onError(String reason);
    }

    /**
     * Opens the map file and adds the tile layer in the background.
     * The tile layer is inserted at index 0 (below all other layers).
     * Callback is invoked from the background thread — use runOnUiThread for UI updates.
     */
    public void loadMapAsync(File mapFile, LoadCallback callback) {
        new Thread(() -> {
            if (!mapFile.exists()) {
                callback.onError("file not found");
                return;
            }
            try {
                TileCache cache = AndroidUtil.createTileCache(
                        context,
                        "maincache",
                        mapView.getModel().displayModel.getTileSize(),
                        1f,
                        mapView.getModel().frameBufferModel.getOverdrawFactor()
                );

                MapDataStore mapDataStore = new MapFile(mapFile);
                TileRendererLayer layer = new TileRendererLayer(
                        cache,
                        mapDataStore,
                        mapView.getModel().mapViewPosition,
                        AndroidGraphicFactory.INSTANCE
                );
                layer.setXmlRenderTheme(InternalRenderTheme.DEFAULT);

                // Insert below all other layers; LayerManager is synchronized
                mapView.getLayerManager().getLayers().add(0, layer);

                tileCache = cache;
                tileLayer  = layer;
                callback.onLoaded();
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        }, "map-loader").start();
    }

    /**
     * TODO: Set your target area here.
     *
     * Replace the LatLong values with the center of your map region (decimal degrees).
     * Zoom byte guide: 12 = city overview, 14 = neighbourhood, 16 = street level.
     *
     * Example (Berlin): new LatLong(52.520, 13.405)
     */
    public void setInitialPosition() {
        mapView.getModel().mapViewPosition.setMapPosition(
                new MapPosition(new LatLong(0.0, 0.0), (byte) 12)
        );
    }

    /**
     * Map file at /sdcard/aNavMode/maps/default.map — survives app reinstall.
     * Requires READ_EXTERNAL_STORAGE permission (requested at runtime in MainActivity).
     */
    public File getDefaultMapFile() {
        return new File(Environment.getExternalStorageDirectory(), "aNavMode/maps/default.map");
    }

    /** POI file alongside the map: /sdcard/aNavMode/maps/default.poi */
    public File getDefaultPoiFile() {
        return new File(Environment.getExternalStorageDirectory(), "aNavMode/maps/default.poi");
    }

    public void destroy() {
        if (tileLayer != null) {
            mapView.getLayerManager().getLayers().remove(tileLayer);
            tileLayer.onDestroy();
        }
        if (tileCache != null) {
            tileCache.destroy();
        }
    }
}
