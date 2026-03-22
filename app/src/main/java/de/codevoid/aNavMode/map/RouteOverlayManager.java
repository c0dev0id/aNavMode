package de.codevoid.aNavMode.map;

import android.graphics.Color;

import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.layer.overlay.Polyline;

import java.util.List;

import de.codevoid.aNavMode.routing.RoutePoint;

public class RouteOverlayManager {

    private final MapView mapView;
    private Polyline currentRoute;

    public RouteOverlayManager(MapView mapView) {
        this.mapView = mapView;
    }

    public void showRoute(List<RoutePoint> points) {
        clearRoute();

        Paint paint = AndroidGraphicFactory.INSTANCE.createPaint();
        paint.setColor(Color.argb(220, 0, 100, 255));
        paint.setStrokeWidth(8);
        paint.setStyle(Style.STROKE);

        currentRoute = new Polyline(paint, AndroidGraphicFactory.INSTANCE);
        for (RoutePoint p : points) {
            currentRoute.getLatLongs().add(new LatLong(p.lat, p.lon));
        }

        mapView.getLayerManager().getLayers().add(currentRoute);
        mapView.getLayerManager().redrawLayers();
    }

    public void clearRoute() {
        if (currentRoute != null) {
            mapView.getLayerManager().getLayers().remove(currentRoute);
            currentRoute = null;
        }
    }
}
