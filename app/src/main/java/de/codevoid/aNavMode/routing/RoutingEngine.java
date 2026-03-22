package de.codevoid.aNavMode.routing;

import java.util.List;

public interface RoutingEngine {
    /**
     * Calculate a route between two points.
     * Must be called off the main thread.
     *
     * @param profile BRouter profile name, e.g. "trekking", "fastbike", "car-fast"
     * @return ordered route points, or null on failure
     */
    List<RoutePoint> calculateRoute(double fromLat, double fromLon,
                                    double toLat, double toLon,
                                    String profile);
}
