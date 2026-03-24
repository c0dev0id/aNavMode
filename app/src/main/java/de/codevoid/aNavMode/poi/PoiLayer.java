package de.codevoid.aNavMode.poi;

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

/**
 * Renders POI markers from PoiDomain state.
 *
 * Calls domain.queryViewport() in draw() so queries are driven by the actual
 * rendered viewport. No-ops when both category toggles are off.
 */
public class PoiLayer extends Layer implements PoiDomain.Listener {

    private static final int RADIUS_DP  = 5;
    private static final int OUTLINE_DP = 2;

    private final PoiDomain domain;
    private final int       tileSize;
    private final float     density;
    private final Paint     fuelPaint;
    private final Paint     foodPaint;
    private final Paint     outlinePaint;

    private volatile PoiDomain.State state    = PoiDomain.State.EMPTY;
    private volatile boolean         showFuel = false;
    private volatile boolean         showFood = false;

    public PoiLayer(MapView mapView, PoiDomain domain, float density) {
        this.domain   = domain;
        this.tileSize = mapView.getModel().displayModel.getTileSize();
        this.density  = density;
        domain.addListener(this);

        fuelPaint = fillPaint(Color.rgb(255, 165,  0));  // amber
        foodPaint = fillPaint(Color.rgb( 76, 175, 80));  // green

        outlinePaint = AndroidGraphicFactory.INSTANCE.createPaint();
        outlinePaint.setColor(Color.WHITE);
        outlinePaint.setStrokeWidth(dp(OUTLINE_DP));
        outlinePaint.setStyle(Style.STROKE);
    }

    public void setShowFuel(boolean show) { showFuel = show; requestRedraw(); }
    public void setShowFood(boolean show) { showFood = show; requestRedraw(); }

    // -------------------------------------------------------------------------
    // PoiDomain.Listener
    // -------------------------------------------------------------------------

    @Override
    public void onPoiStateChanged(PoiDomain.State newState) {
        state = newState;
        requestRedraw();
    }

    // -------------------------------------------------------------------------
    // Layer rendering — mapsforge render thread
    // -------------------------------------------------------------------------

    @Override
    public void draw(BoundingBox bb, byte zoom, Canvas canvas, Point topLeft, Rotation rot) {
        if (!showFuel && !showFood) return;
        domain.queryViewport(bb);

        PoiDomain.State s = state;
        if (s.pois.isEmpty()) return;

        long mapSize = MercatorProjection.getMapSize(zoom, tileSize);
        int  radius  = (int) dp(RADIUS_DP);

        for (PoiDomain.Poi poi : s.pois) {
            Paint paint;
            if      (poi.category == PoiDomain.Category.FUEL && showFuel) paint = fuelPaint;
            else if (poi.category == PoiDomain.Category.FOOD && showFood) paint = foodPaint;
            else continue;

            int cx = (int) (MercatorProjection.longitudeToPixelX(poi.longitude, mapSize) - topLeft.x);
            int cy = (int) (MercatorProjection.latitudeToPixelY(poi.latitude,   mapSize) - topLeft.y);
            canvas.drawCircle(cx, cy, radius, paint);
            canvas.drawCircle(cx, cy, radius, outlinePaint);
        }
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
