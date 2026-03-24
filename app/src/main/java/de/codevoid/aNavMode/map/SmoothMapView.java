package de.codevoid.aNavMode.map;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.core.util.Parameters;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.model.MapViewPosition;

/**
 * MapView with fluid (non-snapping) pinch zoom and no tile loading during gesture.
 *
 * During a pinch gesture the map model is never touched — all visual zoom is
 * applied through Android's View scale transform (setScaleX/Y + setPivot).
 * Tile loading is triggered exactly once when the fingers lift, via a single
 * setCenter + setZoom(fractional, false) call in commitGestureZoom().
 *
 * Single-touch events are passed to super unchanged so mapsforge handles
 * panning and tapping as normal.
 */
public class SmoothMapView extends MapView {

    private final ScaleGestureDetector scaleDetector;
    private boolean pinching = false;

    // Visual scale accumulated during a pinch gesture.
    // Applied as a View transform — no model changes until gesture ends.
    private float gestureScale = 1f;
    private float gestureFocusX, gestureFocusY;

    public SmoothMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // Required: mapsforge's getMapPosition() returns the integer zoomLevel when
        // FRACTIONAL_ZOOM is false, which causes pan math and the renderer to ignore the
        // fractional scaleFactor we set in commitGestureZoom — visible as a snap-back on
        // gesture end. With FRACTIONAL_ZOOM=true both use the fractional scaleFactor.
        Parameters.FRACTIONAL_ZOOM = true;
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector d) {
                        gestureScale  = 1f;
                        gestureFocusX = d.getFocusX();
                        gestureFocusY = d.getFocusY();
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        gestureScale  *= d.getScaleFactor();
                        gestureFocusX  = d.getFocusX();
                        gestureFocusY  = d.getFocusY();
                        setPivotX(gestureFocusX);
                        setPivotY(gestureFocusY);
                        setScaleX(gestureScale);
                        setScaleY(gestureScale);
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector d) {
                        float scale = gestureScale;
                        float fx    = gestureFocusX;
                        float fy    = gestureFocusY;
                        gestureScale = 1f;
                        // Reset View transform before the model update so that the
                        // new tiles render at scale=1 on the next frame.
                        setScaleX(1f);
                        setScaleY(1f);
                        commitGestureZoom(scale, fx, fy);
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

    /**
     * Commits the visual scale accumulated during a pinch gesture into the map
     * model. Called exactly once on gesture release — the only point where tile
     * loading is triggered.
     *
     * @param scale  total scale factor of the completed gesture (1f = no-op)
     * @param focusX screen X of the gesture focal point
     * @param focusY screen Y of the gesture focal point
     */
    private void commitGestureZoom(float scale, float focusX, float focusY) {
        if (scale == 1f) return;

        MapViewPosition pos      = getModel().mapViewPosition;
        LatLong         center   = pos.getCenter();
        int             tileSize = getModel().displayModel.getTileSize();

        // Effective map size at the current fractional zoom.
        // (tileSize * 2^z, where z may be fractional from a prior gesture commit.)
        double scaleFactor = pos.getScaleFactor();
        long   mapSize     = (long) (tileSize * scaleFactor);

        // World-pixel coordinates of the current map center and focal point.
        double cx = MercatorProjection.longitudeToPixelX(center.longitude, mapSize);
        double cy = MercatorProjection.latitudeToPixelY(center.latitude,   mapSize);
        double fx = cx + (focusX - getWidth()  / 2.0);
        double fy = cy + (focusY - getHeight() / 2.0);

        // New fractional zoom — clamp to the supported range.
        double newZoom = pos.getZoom() + Math.log(scale) / Math.log(2);
        newZoom = Math.max(pos.getZoomLevelMin(), Math.min(pos.getZoomLevelMax(), newZoom));

        // World pixels scale linearly with map size, so the focal point in the
        // new coordinate system is simply fx * scale, fy * scale.
        long   newMapSize = (long) (tileSize * Math.pow(2, newZoom));
        double newFx      = fx * scale;
        double newFy      = fy * scale;

        // New center: focal world pixel minus its screen offset from the view centre.
        double newCx = newFx - (focusX - getWidth()  / 2.0);
        double newCy = newFy - (focusY - getHeight() / 2.0);

        LatLong newCenter = new LatLong(
                MercatorProjection.pixelYToLatitude(newCy, newMapSize),
                MercatorProjection.pixelXToLongitude(newCx, newMapSize));

        pos.setCenter(newCenter);
        pos.setZoom(newZoom, false);
    }
}
