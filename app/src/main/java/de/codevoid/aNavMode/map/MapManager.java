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

    /**
     * Load an offline mapsforge .map file and add it as the base tile layer.
     * @return false if the file does not exist yet
     */
    public boolean loadMap(File mapFile) {
        if (!mapFile.exists()) return false;

        try {
            tileCache = AndroidUtil.createTileCache(
                    context,
                    "maincache",
                    mapView.getModel().displayModel.getTileSize(),
                    1f,
                    mapView.getModel().frameBufferModel.getOverdrawFactor()
            );

            MapDataStore mapDataStore = new MapFile(mapFile);
            tileLayer = new TileRendererLayer(
                    tileCache,
                    mapDataStore,
                    mapView.getModel().mapViewPosition,
                    AndroidGraphicFactory.INSTANCE
            );
            tileLayer.setXmlRenderTheme(InternalRenderTheme.DEFAULT);
            mapView.getLayerManager().getLayers().add(tileLayer);
            return true;
        } catch (Exception e) {
            return false;
        }
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
