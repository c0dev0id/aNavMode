package de.codevoid.aNavMode.map;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.model.MapViewPosition;

import java.util.HashMap;
import java.util.Map;

import de.codevoid.aNavMode.remote.RemoteKey;

/**
 * Owns all D-pad, joystick, and zoom-key pan state and drives the per-frame
 * camera update for mapsforge.
 *
 * Call from a Choreographer.FrameCallback in the Activity:
 *   onKeyDown / onKeyUp  — from handleRemoteEvent for KeyDown / KeyUp events
 *   onJoyInput           — from handleRemoteEvent for JoyInput events
 *   onFrame              — from FrameCallback.doFrame() on every vsync
 *
 * Pan uses MercatorProjection to convert screen-pixel deltas to LatLong, which
 * correctly handles all zoom levels without manual metres-per-pixel math.
 * Zoom uses setZoomLevelDouble() for smooth sub-integer zoom steps.
 *
 * Ported from aWayToGo/PanController.kt (MapLibre → mapsforge).
 */
public class PanController {

    private static final float PAN_SPEED_PX_PER_SEC = 120f;
    private static final float ZOOM_SPEED_PER_SEC   = 1.5f;
    private static final float JOY_RAMP_RATE        = 1f / 0.3f;  // mag-units/s
    private static final float DT_CAP_S             = 0.05f;       // cap runaway frames at 50ms

    private final MapView mapView;

    // Active pan/zoom keys → vsync timestamp (ns) at first press, for ramp-up.
    private final Map<RemoteKey, Long> panStartNs = new HashMap<>();

    // Raw joystick axes [-1, 1]; (0,0) = neutral.
    private float joyDx = 0f, joyDy = 0f;
    // Smoothed effective magnitude (0–5), ramped toward target at JOY_RAMP_RATE.
    private float joyEffectiveMag = 0f;
    // Last non-zero normalised direction, preserved for ramp-down after release.
    private float joyLastDirX = 0f, joyLastDirY = 0f;

    public PanController(MapView mapView) {
        this.mapView = mapView;
    }

    /** Call on RemoteEvent.KeyDown. */
    public void onKeyDown(RemoteKey key) {
        switch (key) {
            case UP: case DOWN: case LEFT: case RIGHT:
            case ZOOM_IN: case ZOOM_OUT:
                panStartNs.put(key, System.nanoTime());
                break;
            default:
                break;
        }
    }

    /** Call on RemoteEvent.KeyUp. */
    public void onKeyUp(RemoteKey key) {
        panStartNs.remove(key);
    }

    /** Call on RemoteEvent.JoyInput. */
    public void onJoyInput(float dx, float dy) {
        joyDx = dx;
        joyDy = dy;
    }

    /**
     * Drive the per-frame camera update.
     * Call from Choreographer.FrameCallback.doFrame().
     *
     * @param frameTimeNanos vsync timestamp from FrameCallback
     * @param dtNs           nanoseconds since previous frame
     * @return pan speed in px/s for optional OSD display; 0 if nothing active
     */
    public float onFrame(long frameTimeNanos, long dtNs) {
        if (panStartNs.isEmpty() && joyDx == 0f && joyDy == 0f && joyEffectiveMag <= 0.01f) {
            return 0f;
        }

        float dtS      = Math.min(dtNs / 1_000_000_000f, DT_CAP_S);
        float totalDx  = 0f;   // screen pixels, right = positive
        float totalDy  = 0f;   // screen pixels, down  = positive
        float totalZoom = 0f;
        float panSpeed  = 0f;

        // D-pad and zoom keys
        for (Map.Entry<RemoteKey, Long> entry : panStartNs.entrySet()) {
            RemoteKey key    = entry.getKey();
            float     ramp   = startRamp(frameTimeNanos, entry.getValue());
            float     ramped = 0.5f + 0.5f * ramp;

            switch (key) {
                case UP: case DOWN: case LEFT: case RIGHT: {
                    float speed = PAN_SPEED_PX_PER_SEC * ramped;
                    float px    = speed * dtS;
                    if (speed > panSpeed) panSpeed = speed;
                    switch (key) {
                        case UP:    totalDy -= px; break;
                        case DOWN:  totalDy += px; break;
                        case LEFT:  totalDx -= px; break;
                        case RIGHT: totalDx += px; break;
                        default: break;
                    }
                    break;
                }
                case ZOOM_IN:
                    totalZoom += ZOOM_SPEED_PER_SEC * ramped * dtS;
                    break;
                case ZOOM_OUT:
                    totalZoom -= ZOOM_SPEED_PER_SEC * ramped * dtS;
                    break;
                default:
                    break;
            }
        }

        // Analog joystick — ramped speed curve
        float inputMag   = Math.max(Math.abs(joyDx), Math.abs(joyDy)) * 5f;
        float rampDelta  = JOY_RAMP_RATE * dtS;
        if (joyEffectiveMag < inputMag) {
            joyEffectiveMag = Math.min(joyEffectiveMag + rampDelta, inputMag);
        } else if (joyEffectiveMag > inputMag) {
            joyEffectiveMag = Math.max(joyEffectiveMag - rampDelta, inputMag);
        }

        if (joyEffectiveMag > 0.01f) {
            float len = (float) Math.sqrt(joyDx * joyDx + joyDy * joyDy);
            if (len > 0.01f) {
                joyLastDirX = joyDx / len;
                joyLastDirY = joyDy / len;
            }
            float speedPxS = joyMagnitudeToSpeed(joyEffectiveMag);
            float px       = speedPxS * dtS;
            totalDx +=  joyLastDirX * px;
            totalDy += -joyLastDirY * px;  // joy Y+ = screen-up = subtract from dy
            if (speedPxS > panSpeed) panSpeed = speedPxS;
        }

        // Apply pan delta via MercatorProjection round-trip
        if (totalDx != 0f || totalDy != 0f) {
            byte  zoom    = mapView.getModel().mapViewPosition.getZoomLevel();
            int   tileSize = mapView.getModel().displayModel.getTileSize();
            long  mapSize  = MercatorProjection.getMapSize(zoom, tileSize);
            LatLong center = mapView.getModel().mapViewPosition.getCenter();

            double newPixelX = MercatorProjection.longitudeToPixelX(center.longitude, mapSize) + totalDx;
            double newPixelY = MercatorProjection.latitudeToPixelY(center.latitude,   mapSize) + totalDy;
            double newLon = MercatorProjection.pixelXToLongitude(newPixelX, mapSize);
            double newLat = MercatorProjection.pixelYToLatitude(newPixelY, mapSize);
            mapView.getModel().mapViewPosition.setCenter(new LatLong(newLat, newLon));
        }

        // Apply smooth zoom
        if (totalZoom != 0f) {
            MapViewPosition mvp = (MapViewPosition) mapView.getModel().mapViewPosition;
            double currentZoom = mvp.getZoomLevelDouble();
            double newZoom     = Math.max(0, Math.min(20, currentZoom + totalZoom));
            mvp.setZoomLevelDouble(newZoom);
        }

        return panSpeed;
    }

    public boolean isActive() {
        return !panStartNs.isEmpty() || joyDx != 0f || joyDy != 0f || joyEffectiveMag > 0.01f;
    }

    // -------------------------------------------------------------------------

    /** Linear 0→1 ramp over 2 seconds from first press. */
    private float startRamp(long frameTimeNanos, long startNs) {
        float t = (frameTimeNanos - startNs) / 1_000_000f / 2000f;
        return Math.max(0f, Math.min(1f, t));
    }

    /**
     * Maps smoothed magnitude (0–5) to pan speed in px/s:
     *   0 →  0,  2 → 15,  3 → 30,  4 → 60,  5 → 120
     */
    private float joyMagnitudeToSpeed(float mag) {
        if (mag <= 0f) return 0f;
        if (mag <  2f) return mag / 2f * 15f;
        if (mag <  3f) return 15f + (mag - 2f) * 15f;
        if (mag <  4f) return 30f + (mag - 3f) * 30f;
        if (mag <  5f) return 60f + (mag - 4f) * 60f;
        return 120f;
    }
}
