package de.codevoid.aNavMode.map;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.Point;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.model.MapViewPosition;

/**
 * MapView with fluid (non-snapping) pinch zoom.
 *
 * Mapsforge's built-in TouchEventHandler calls setZoomLevel() on
 * ACTION_POINTER_UP, which resets the fractional scale factor and snaps
 * the map to the nearest integer zoom level. We intercept multi-touch events
 * with our own ScaleGestureDetector, apply zoom() fractionally, and never
 * call super for scale events, preventing the snap entirely.
 *
 * Single-touch events are passed to super unchanged so mapsforge handles
 * panning and tapping as normal.
 */
public class SmoothMapView extends MapView {

    private final ScaleGestureDetector scaleDetector;
    private boolean pinching = false;

    public SmoothMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        MapViewPosition pos = getModel().mapViewPosition;
                        pos.setPivot(screenToMapPoint(d.getFocusX(), d.getFocusY()));
                        pos.zoom(d.getScaleFactor());
                        return true;
                    }

                    @Override
                    public boolean onScaleEnd(ScaleGestureDetector d) {
                        // Clear pivot only — intentionally no setZoomLevel() here
                        getModel().mapViewPosition.setPivot(null);
                        return true;
                    }
                });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() == 2) {
            // Second finger down: cancel mapsforge's pan tracker so it won't
            // apply its own zoom/snap logic while we're handling the gesture.
            pinching = true;
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.onTouchEvent(cancel);
            cancel.recycle();
            return true;
        }

        if (pinching) {
            if (action == MotionEvent.ACTION_POINTER_UP && event.getPointerCount() == 2) {
                // Back to one finger: end pinch and re-anchor mapsforge's pan tracker
                // on the remaining finger so dragging resumes immediately.
                pinching = false;
                int idx = event.getActionIndex() == 0 ? 1 : 0;
                MotionEvent down = MotionEvent.obtain(
                        event.getEventTime(), event.getEventTime(),
                        MotionEvent.ACTION_DOWN,
                        event.getX(idx), event.getY(idx), 0);
                super.onTouchEvent(down);
                down.recycle();
                return true;
            }
            return true; // consume all other multi-touch events
        }

        return super.onTouchEvent(event);
    }

    // -------------------------------------------------------------------------

    /** Converts a screen coordinate to a mapsforge map-pixel Point. */
    private Point screenToMapPoint(float screenX, float screenY) {
        MapViewPosition pos = getModel().mapViewPosition;
        LatLong center = pos.getCenter();
        byte zoom = pos.getZoomLevel();
        int tileSize = getModel().displayModel.getTileSize();
        long mapSize = MercatorProjection.getMapSize(zoom, tileSize);
        double cx = MercatorProjection.longitudeToPixelX(center.longitude, mapSize);
        double cy = MercatorProjection.latitudeToPixelY(center.latitude, mapSize);
        return new Point(cx + screenX - getWidth() / 2.0,
                         cy + screenY - getHeight() / 2.0);
    }
}
