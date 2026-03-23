package de.codevoid.aNavMode.routing;

import org.mapsforge.core.model.LatLong;
import org.mapsforge.map.android.view.MapView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns all waypoint and routing state. Runs BRouter on its own single-thread executor.
 * Publishes immutable State snapshots to subscribers — no main thread involved.
 *
 * All public mutation methods are safe to call from any thread.
 */
public class RoutingDomain {

    // -------------------------------------------------------------------------
    // State snapshot — immutable, safe to read from any thread
    // -------------------------------------------------------------------------

    public static final class State {
        /** Ordered waypoints. */
        public final List<LatLong> waypoints;
        /**
         * Route polyline for segment i (waypoints[i] → waypoints[i+1]).
         * Null while routing is pending for that segment.
         */
        public final List<List<LatLong>> segments;

        private State(List<LatLong> waypoints, List<List<LatLong>> segments) {
            this.waypoints = Collections.unmodifiableList(new ArrayList<>(waypoints));
            List<List<LatLong>> copy = new ArrayList<>(segments.size());
            for (List<LatLong> seg : segments)
                copy.add(seg != null ? Collections.unmodifiableList(new ArrayList<>(seg)) : null);
            this.segments = Collections.unmodifiableList(copy);
        }

        public static final State EMPTY =
                new State(Collections.emptyList(), Collections.emptyList());
    }

    // -------------------------------------------------------------------------
    // Listener
    // -------------------------------------------------------------------------

    public interface Listener {
        /**
         * Called from the domain's internal thread whenever state changes.
         * Do not block; update volatile fields and call requestRedraw().
         */
        void onStateChanged(State state);

        /**
         * Called from the domain's internal thread when a segment cannot be routed.
         * Marshal to main thread if a Toast or UI update is needed.
         */
        void onRoutingFailed(int segmentIndex);
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final RoutingEngine router;
    private final MapView mapView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    // Mutable state — only ever touched on the executor thread
    private final List<LatLong>       waypoints = new ArrayList<>();
    private final List<List<LatLong>> segments  = new ArrayList<>();
    private String profile = "trekking";

    public RoutingDomain(RoutingEngine router, MapView mapView) {
        this.router  = router;
        this.mapView = mapView;
    }

    // -------------------------------------------------------------------------
    // Subscription
    // -------------------------------------------------------------------------

    public void addListener(Listener l)    { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    // -------------------------------------------------------------------------
    // Mutations — safe to call from any thread
    // -------------------------------------------------------------------------

    public void setProfile(String profile) {
        executor.execute(() -> this.profile = profile);
    }

    /** Adds a waypoint at the current map centre. */
    public void addAtCenter() {
        // Read centre on the calling thread (MapViewPosition.mapCenter is volatile).
        addWaypoint(mapView.getModel().mapViewPosition.getCenter());
    }

    public void addWaypoint(LatLong latLong) {
        executor.execute(() -> {
            int idx = waypoints.size();
            waypoints.add(latLong);
            if (idx > 0) {
                segments.add(null);
                publishState();         // show pending marker immediately
                routeSegment(idx - 1); // blocks until BRouter finishes
            } else {
                publishState();
            }
        });
    }

    public void removeWaypoint(int index) {
        executor.execute(() -> removeAt(index));
    }

    public void removeLastWaypoint() {
        executor.execute(() -> removeAt(waypoints.size() - 1));
    }

    public void clearAll() {
        executor.execute(() -> {
            waypoints.clear();
            segments.clear();
            publishState();
        });
    }

    public void destroy() {
        executor.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Internal — all run on executor thread
    // -------------------------------------------------------------------------

    private void removeAt(int index) {
        if (index < 0 || index >= waypoints.size()) return;

        waypoints.remove(index);

        // Drop segment from removed point forward
        if (index < segments.size()) {
            segments.remove(index);
        }

        // Drop segment from previous point to removed point; re-route if neighbours exist
        if (index > 0 && index - 1 < segments.size()) {
            segments.remove(index - 1);
            if (index - 1 < waypoints.size()) {
                segments.add(index - 1, null);
                publishState();
                routeSegment(index - 1);
                return;
            }
        }

        publishState();
    }

    /** Blocking BRouter call — must run on executor thread. */
    private void routeSegment(int idx) {
        if (idx < 0 || idx + 1 >= waypoints.size()) return;

        LatLong from = waypoints.get(idx);
        LatLong to   = waypoints.get(idx + 1);

        List<RoutePoint> points = router.calculateRoute(
                from.latitude, from.longitude,
                to.latitude,   to.longitude,
                profile);

        // Waypoints may have changed while routing was blocked; validate before writing
        if (idx >= segments.size()) return;

        if (points != null && !points.isEmpty()) {
            List<LatLong> latLongs = new ArrayList<>(points.size());
            for (RoutePoint p : points) latLongs.add(new LatLong(p.lat, p.lon));
            segments.set(idx, latLongs);
            publishState();
        } else {
            for (Listener l : listeners) l.onRoutingFailed(idx);
        }
    }

    private void publishState() {
        State s = new State(waypoints, segments);
        for (Listener l : listeners) l.onStateChanged(s);
    }
}
