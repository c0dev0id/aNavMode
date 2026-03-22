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
import org.mapsforge.map.layer.overlay.Polyline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.codevoid.aNavMode.routing.RoutePoint;
import de.codevoid.aNavMode.routing.RoutingEngine;

/**
 * Single mapsforge Layer that manages the full waypoint workflow:
 * - tap empty map → add waypoint (green start / blue via / red end markers)
 * - tap existing waypoint → remove it and re-route affected segments
 * - routes segments automatically as waypoints are placed
 * - inserts route Polylines below itself in the layer stack so markers draw on top
 *
 * Must be added as the *last* layer in LayerManager so it receives taps first.
 */
public class WaypointLayer extends Layer {

    private static final int HIT_RADIUS_DP   = 24;
    private static final int MARKER_RADIUS_DP = 10;

    // Brouter profile used for all segments — change via setProfile()
    private String profile = "trekking";

    public interface Listener {
        void onRoutingFailed(int segmentIndex);
    }

    private final MapView       mapView;
    private final RoutingEngine router;
    private final ExecutorService routingQueue = Executors.newSingleThreadExecutor();
    private final Handler        mainHandler   = new Handler(Looper.getMainLooper());
    private       Listener       listener;

    private final List<LatLong> waypoints = new ArrayList<>();
    // segments.get(i) = polyline from waypoints[i] to waypoints[i+1]; null while pending
    private final List<Polyline> segments = new ArrayList<>();

    private final Paint startPaint, viaPaint, endPaint, outlinePaint, routePaint;
    private final float density;
    private final int   tileSize;

    public WaypointLayer(MapView mapView, RoutingEngine router, float density) {
        this.mapView  = mapView;
        this.router   = router;
        this.density  = density;
        this.tileSize = mapView.getModel().displayModel.getTileSize();

        startPaint   = fillPaint(Color.rgb(114, 176, 38));  // green
        viaPaint     = fillPaint(Color.rgb(56,  170, 221)); // blue
        endPaint     = fillPaint(Color.rgb(214,  62,  42)); // red

        outlinePaint = AndroidGraphicFactory.INSTANCE.createPaint();
        outlinePaint.setColor(Color.WHITE);
        outlinePaint.setStrokeWidth(dp(3));
        outlinePaint.setStyle(Style.STROKE);

        routePaint = AndroidGraphicFactory.INSTANCE.createPaint();
        routePaint.setColor(Color.argb(220, 0, 100, 255));
        routePaint.setStrokeWidth(dp(5));
        routePaint.setStyle(Style.STROKE);
    }

    public void setListener(Listener l) { listener = l; }
    public void setProfile(String profile) { this.profile = profile; }

    // -------------------------------------------------------------------------
    // Waypoint operations
    // -------------------------------------------------------------------------

    public void addWaypoint(LatLong latLong) {
        int index = waypoints.size();
        waypoints.add(latLong);

        if (index > 0) {
            segments.add(null); // placeholder for the new segment
            routeSegment(index - 1);
        }

        requestRedraw();
    }

    public void removeWaypoint(int index) {
        if (index < 0 || index >= waypoints.size()) return;

        waypoints.remove(index);

        // Remove segment from removed point to its next, if any
        if (index < segments.size()) {
            clearPolylineAt(index);
            segments.remove(index);
        }

        // Remove segment from previous to removed point; re-route if both neighbours exist
        if (index > 0 && index - 1 < segments.size()) {
            clearPolylineAt(index - 1);
            segments.remove(index - 1);
            if (index - 1 < waypoints.size()) { // prev and new-next both exist
                segments.add(index - 1, null);
                routeSegment(index - 1);
            }
        }

        requestRedraw();
    }

    public void clearAll() {
        waypoints.clear();
        for (Polyline p : segments) {
            if (p != null) mapView.getLayerManager().getLayers().remove(p);
        }
        segments.clear();
        requestRedraw();
    }

    public List<LatLong> getWaypoints() {
        return Collections.unmodifiableList(waypoints);
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    public void draw(BoundingBox boundingBox, byte zoomLevel, Canvas canvas, Point topLeftPoint) {
        if (waypoints.isEmpty()) return;

        long mapSize = MercatorProjection.getMapSize(zoomLevel, tileSize);
        int  radius  = (int) dp(MARKER_RADIUS_DP);

        for (int i = 0; i < waypoints.size(); i++) {
            LatLong wp = waypoints.get(i);
            int cx = (int) (MercatorProjection.longitudeToPixelX(wp.longitude, mapSize) - topLeftPoint.x);
            int cy = (int) (MercatorProjection.latitudeToPixelY(wp.latitude,   mapSize) - topLeftPoint.y);

            canvas.drawCircle(cx, cy, radius, markerPaint(i));
            canvas.drawCircle(cx, cy, radius, outlinePaint);
        }
    }

    // -------------------------------------------------------------------------
    // Tap handling — this layer must be the top (last) layer in the stack
    // -------------------------------------------------------------------------

    @Override
    public boolean onTap(LatLong tapLatLong, Point layerXY, Point tapXY) {
        byte zoom     = mapView.getModel().mapViewPosition.getZoomLevel();
        long mapSize  = MercatorProjection.getMapSize(zoom, tileSize);
        double hitSq  = Math.pow(dp(HIT_RADIUS_DP), 2);

        for (int i = 0; i < waypoints.size(); i++) {
            LatLong wp = waypoints.get(i);
            double dx = MercatorProjection.longitudeToPixelX(wp.longitude,        mapSize)
                      - MercatorProjection.longitudeToPixelX(tapLatLong.longitude, mapSize);
            double dy = MercatorProjection.latitudeToPixelY(wp.latitude,          mapSize)
                      - MercatorProjection.latitudeToPixelY(tapLatLong.latitude,   mapSize);
            if (dx*dx + dy*dy <= hitSq) {
                removeWaypoint(i);
                return true;
            }
        }

        addWaypoint(tapLatLong);
        return true;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void routeSegment(final int idx) {
        final LatLong from = waypoints.get(idx);
        final LatLong to   = waypoints.get(idx + 1);

        routingQueue.execute(() -> {
            List<RoutePoint> points = router.calculateRoute(
                    from.latitude, from.longitude,
                    to.latitude,   to.longitude,
                    profile);

            mainHandler.post(() -> {
                if (idx >= segments.size()) return; // cleared while routing

                if (points != null && !points.isEmpty()) {
                    clearPolylineAt(idx);

                    Polyline polyline = buildPolyline(points);
                    segments.set(idx, polyline);

                    // Insert below this layer so markers always render on top of routes
                    int myIndex = mapView.getLayerManager().getLayers().indexOf(WaypointLayer.this);
                    mapView.getLayerManager().getLayers().add(Math.max(myIndex, 0), polyline);
                    requestRedraw();
                } else if (listener != null) {
                    listener.onRoutingFailed(idx);
                }
            });
        });
    }

    private void clearPolylineAt(int idx) {
        Polyline p = segments.get(idx);
        if (p != null) {
            mapView.getLayerManager().getLayers().remove(p);
            segments.set(idx, null);
        }
    }

    private Polyline buildPolyline(List<RoutePoint> points) {
        Polyline polyline = new Polyline(routePaint, AndroidGraphicFactory.INSTANCE);
        for (RoutePoint p : points) {
            polyline.getLatLongs().add(new LatLong(p.lat, p.lon));
        }
        return polyline;
    }

    private Paint markerPaint(int index) {
        if (index == 0)                        return startPaint;
        if (index == waypoints.size() - 1)     return endPaint;
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

    public void destroy() {
        routingQueue.shutdownNow();
        clearAll();
    }
}
