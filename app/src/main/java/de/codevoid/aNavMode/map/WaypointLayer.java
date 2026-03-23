package de.codevoid.aNavMode.map;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import org.mapsforge.core.graphics.Canvas;
import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Point;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.layer.Layer;

import java.util.List;

import de.codevoid.aNavMode.routing.RoutingDomain;

/**
 * Pure rendering layer for waypoints and route polylines.
 *
 * All routing state is owned by RoutingDomain. This layer subscribes to domain
 * events and stores an immutable State snapshot via a volatile write, which the
 * mapsforge render thread reads on each draw. The main thread is never involved
 * in route updates.
 *
 * Must be the last layer added to LayerManager so it receives taps first.
 */
public class WaypointLayer extends Layer implements RoutingDomain.Listener {

    private static final int HIT_RADIUS_DP   = 24;
    private static final int MARKER_RADIUS_DP = 10;

    public interface FailureListener {
        /** Always called on the main thread. */
        void onRoutingFailed(int segmentIndex);
    }

    private final MapView  mapView;
    private final RoutingDomain domain;
    private final Handler  mainHandler = new Handler(Looper.getMainLooper());
    private final float    density;
    private final int      tileSize;
    private FailureListener failureListener;

    // Written from domain thread, read from mapsforge render thread.
    // The volatile guarantees the render thread always sees the latest snapshot.
    private volatile RoutingDomain.State state = RoutingDomain.State.EMPTY;

    private final Paint startPaint, viaPaint, endPaint, outlinePaint, routePaint, dragLinePaint;

    public WaypointLayer(MapView mapView, RoutingDomain domain, float density) {
        this.mapView  = mapView;
        this.domain   = domain;
        this.density  = density;
        this.tileSize = mapView.getModel().displayModel.getTileSize();

        domain.addListener(this);

        startPaint = fillPaint(Color.rgb(114, 176, 38));  // green
        viaPaint   = fillPaint(Color.rgb(56,  170, 221)); // blue
        endPaint   = fillPaint(Color.rgb(214,  62,  42)); // red

        outlinePaint = AndroidGraphicFactory.INSTANCE.createPaint();
        outlinePaint.setColor(Color.WHITE);
        outlinePaint.setStrokeWidth(dp(3));
        outlinePaint.setStyle(Style.STROKE);

        routePaint = AndroidGraphicFactory.INSTANCE.createPaint();
        routePaint.setColor(Color.argb(220, 0, 100, 255));
        routePaint.setStrokeWidth(dp(5));
        routePaint.setStyle(Style.STROKE);

        dragLinePaint = AndroidGraphicFactory.INSTANCE.createPaint();
        dragLinePaint.setColor(Color.argb(160, 0, 100, 255));
        dragLinePaint.setStrokeWidth(dp(2));
        dragLinePaint.setStyle(Style.STROKE);
        dragLinePaint.setDashPathEffect(new float[]{dp(10), dp(6)});
    }

    public void setFailureListener(FailureListener l) { failureListener = l; }

    // -------------------------------------------------------------------------
    // RoutingDomain.Listener — called from domain thread, not main thread
    // -------------------------------------------------------------------------

    @Override
    public void onStateChanged(RoutingDomain.State newState) {
        state = newState;  // volatile write — render thread picks it up on next draw
        requestRedraw();   // thread-safe in mapsforge
    }

    @Override
    public void onRoutingFailed(int segmentIndex) {
        if (failureListener != null) {
            mainHandler.post(() -> failureListener.onRoutingFailed(segmentIndex));
        }
    }

    // -------------------------------------------------------------------------
    // Rendering — called from mapsforge render thread
    // -------------------------------------------------------------------------

    @Override
    public void draw(BoundingBox boundingBox, byte zoomLevel, Canvas canvas, Point topLeftPoint) {
        RoutingDomain.State s = state; // single volatile read for a consistent snapshot
        if (s.waypoints.isEmpty()) return;

        long mapSize = MercatorProjection.getMapSize(zoomLevel, tileSize);

        // Route segments — drawn directly, no Polyline layer objects needed
        for (List<LatLong> seg : s.segments) {
            if (seg == null || seg.size() < 2) continue;
            for (int i = 1; i < seg.size(); i++) {
                LatLong p1 = seg.get(i - 1);
                LatLong p2 = seg.get(i);
                int x1 = project(MercatorProjection.longitudeToPixelX(p1.longitude, mapSize), topLeftPoint.x);
                int y1 = project(MercatorProjection.latitudeToPixelY(p1.latitude,   mapSize), topLeftPoint.y);
                int x2 = project(MercatorProjection.longitudeToPixelX(p2.longitude, mapSize), topLeftPoint.x);
                int y2 = project(MercatorProjection.latitudeToPixelY(p2.latitude,   mapSize), topLeftPoint.y);
                canvas.drawLine(x1, y1, x2, y2, routePaint);
            }
        }

        // Drag line: dashed preview from last waypoint to the current map centre
        LatLong last   = s.waypoints.get(s.waypoints.size() - 1);
        LatLong centre = mapView.getModel().mapViewPosition.getCenter();
        canvas.drawLine(
                project(MercatorProjection.longitudeToPixelX(last.longitude,   mapSize), topLeftPoint.x),
                project(MercatorProjection.latitudeToPixelY(last.latitude,     mapSize), topLeftPoint.y),
                project(MercatorProjection.longitudeToPixelX(centre.longitude, mapSize), topLeftPoint.x),
                project(MercatorProjection.latitudeToPixelY(centre.latitude,   mapSize), topLeftPoint.y),
                dragLinePaint);

        // Waypoint markers
        int radius = (int) dp(MARKER_RADIUS_DP);
        for (int i = 0; i < s.waypoints.size(); i++) {
            LatLong wp = s.waypoints.get(i);
            int cx = project(MercatorProjection.longitudeToPixelX(wp.longitude, mapSize), topLeftPoint.x);
            int cy = project(MercatorProjection.latitudeToPixelY(wp.latitude,   mapSize), topLeftPoint.y);
            canvas.drawCircle(cx, cy, radius, markerPaint(i, s.waypoints.size()));
            canvas.drawCircle(cx, cy, radius, outlinePaint);
        }
    }

    // -------------------------------------------------------------------------
    // Tap handling
    // -------------------------------------------------------------------------

    @Override
    public boolean onTap(LatLong tapLatLong, Point layerXY, Point tapXY) {
        RoutingDomain.State s = state;
        byte zoom    = mapView.getModel().mapViewPosition.getZoomLevel();
        long mapSize = MercatorProjection.getMapSize(zoom, tileSize);
        double hitSq = Math.pow(dp(HIT_RADIUS_DP), 2);

        for (int i = 0; i < s.waypoints.size(); i++) {
            LatLong wp = s.waypoints.get(i);
            double dx = MercatorProjection.longitudeToPixelX(wp.longitude,         mapSize)
                      - MercatorProjection.longitudeToPixelX(tapLatLong.longitude, mapSize);
            double dy = MercatorProjection.latitudeToPixelY(wp.latitude,           mapSize)
                      - MercatorProjection.latitudeToPixelY(tapLatLong.latitude,   mapSize);
            if (dx * dx + dy * dy <= hitSq) {
                domain.removeWaypoint(i);
                return true;
            }
        }

        return false;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void destroy() {
        domain.removeListener(this);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int project(double worldCoord, double topLeft) {
        return (int) (worldCoord - topLeft);
    }

    private Paint markerPaint(int index, int total) {
        if (index == 0)         return startPaint;
        if (index == total - 1) return endPaint;
        return viaPaint;
    }

    private Paint fillPaint(int color) {
        Paint p = AndroidGraphicFactory.INSTANCE.createPaint();
        p.setColor(color);
        p.setStyle(Style.FILL);
        return p;
    }

    private float dp(float dp) {
        return dp * density;
    }
}
