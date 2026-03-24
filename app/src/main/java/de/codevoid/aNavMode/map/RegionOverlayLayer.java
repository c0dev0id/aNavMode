package de.codevoid.aNavMode.map;

import android.graphics.Color;

import org.mapsforge.core.graphics.Canvas;
import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.Point;
import org.mapsforge.core.model.Rotation;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.layer.Layer;

import java.util.Collections;
import java.util.List;

import de.codevoid.aNavMode.download.DownloadCatalog;

/**
 * Draws region boundary polygons as a faint overlay.
 *
 * Visible at zoom ≤ 9 (country/region level); fades out as zoom increases.
 * The region the crosshair is currently inside is drawn with a brighter stroke.
 *
 * Insert this layer above the tile layer and below WaypointLayer.
 */
public class RegionOverlayLayer extends Layer {

    // Invisible below ZOOM_MIN; fades in to ZOOM_FULL; fades out to ZOOM_HIDDEN.
    private static final int ZOOM_MIN    = 5;
    private static final int ZOOM_FULL   = 7;
    private static final int ZOOM_HIDDEN = 10;

    // Base RGB for each paint state — alpha is computed per frame from zoom level.
    private static final int BORDER_R    = 255, BORDER_G    = 255, BORDER_B    = 255;
    private static final int HIGHLIGHT_R =  30, HIGHLIGHT_G = 140, HIGHLIGHT_B = 255;

    // Debug: force polygons visible at any zoom in bright green.
    private static final int DEBUG_R = 0, DEBUG_G = 255, DEBUG_B = 0;
    private volatile boolean forceShow = false;

    private final int   tileSize;
    private final Paint borderPaint;
    private final Paint highlightPaint;

    // Written from main thread, read from mapsforge render thread — volatile.
    private volatile List<DownloadCatalog.Region> regions   = Collections.emptyList();
    private volatile String                        currentId = null;

    public RegionOverlayLayer(MapView mapView) {
        tileSize = mapView.getModel().displayModel.getTileSize();

        borderPaint = AndroidGraphicFactory.INSTANCE.createPaint();
        borderPaint.setStrokeWidth(2f);
        borderPaint.setStyle(Style.STROKE);

        highlightPaint = AndroidGraphicFactory.INSTANCE.createPaint();
        highlightPaint.setStrokeWidth(4f);
        highlightPaint.setStyle(Style.STROKE);
    }

    /** Safe to call from any thread. */
    public void updateRegions(List<DownloadCatalog.Region> regions) {
        this.regions = regions;
        requestRedraw();
    }

    /** Safe to call from any thread. Pass null to clear highlighting. */
    public void setCurrentRegion(String regionId) {
        this.currentId = regionId;
        requestRedraw();
    }

    /** Safe to call from any thread. When true, polygons are always visible in bright green. */
    public void setForceShowPolygons(boolean force) {
        this.forceShow = force;
        requestRedraw();
    }

    // -------------------------------------------------------------------------
    // Rendering — called from mapsforge render thread
    // -------------------------------------------------------------------------

    @Override
    public void draw(BoundingBox boundingBox, byte zoomLevel, Canvas canvas, Point topLeftPoint, Rotation rotation) {
        int zoom = zoomLevel & 0xFF;

        List<DownloadCatalog.Region> snap = regions;
        if (snap.isEmpty()) return;

        int alpha;
        if (forceShow) {
            alpha = 255;
            borderPaint.setColor(Color.argb(255, DEBUG_R, DEBUG_G, DEBUG_B));
            highlightPaint.setColor(Color.argb(255, DEBUG_R, DEBUG_G, DEBUG_B));
        } else {
            if (zoom < ZOOM_MIN || zoom >= ZOOM_HIDDEN) return;
            // Fade in from ZOOM_MIN→ZOOM_FULL, full at ZOOM_FULL, fade out to ZOOM_HIDDEN.
            if (zoom <= ZOOM_FULL) {
                alpha = 200 * (zoom - ZOOM_MIN) / (ZOOM_FULL - ZOOM_MIN);
            } else {
                alpha = 200 - (zoom - ZOOM_FULL) * 200 / (ZOOM_HIDDEN - ZOOM_FULL);
            }
            if (alpha <= 0) return;
            borderPaint.setColor(Color.argb(alpha, BORDER_R, BORDER_G, BORDER_B));
            highlightPaint.setColor(Color.argb(Math.min(255, alpha + 55),
                    HIGHLIGHT_R, HIGHLIGHT_G, HIGHLIGHT_B));
        }

        long   mapSize = MercatorProjection.getMapSize((byte) zoom, tileSize);
        String curId   = currentId;

        for (DownloadCatalog.Region r : snap) {
            drawPolygon(r.polygon, mapSize, topLeftPoint, canvas,
                    r.id.equals(curId) ? highlightPaint : borderPaint);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void drawPolygon(List<double[]> polygon, long mapSize,
                                    Point topLeft, Canvas canvas, Paint paint) {
        int n = polygon.size();
        for (int i = 1; i < n; i++) {
            double[] p1 = polygon.get(i - 1);
            double[] p2 = polygon.get(i);
            canvas.drawLine(
                    px(p1[0], mapSize, topLeft.x), py(p1[1], mapSize, topLeft.y),
                    px(p2[0], mapSize, topLeft.x), py(p2[1], mapSize, topLeft.y),
                    paint);
        }
    }

    private static int px(double lon, long mapSize, double topLeftX) {
        return (int) (MercatorProjection.longitudeToPixelX(lon, mapSize) - topLeftX);
    }

    private static int py(double lat, long mapSize, double topLeftY) {
        return (int) (MercatorProjection.latitudeToPixelY(lat, mapSize) - topLeftY);
    }
}
