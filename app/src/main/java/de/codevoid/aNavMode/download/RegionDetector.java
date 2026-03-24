package de.codevoid.aNavMode.download;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects which catalog regions the map crosshair is inside.
 *
 * Call {@link #check(double, double)} from the Choreographer frame callback
 * (main thread). Fires {@link Listener#onEnter} / {@link Listener#onLeave}
 * when the crosshair crosses a region boundary.
 *
 * {@link #updateRegions} may be called from any thread (e.g. after catalog
 * refresh); the next {@link #check} picks up the new list.
 */
public class RegionDetector {

    public interface Listener {
        /** Called on the main thread when the crosshair enters a region. */
        void onEnter(DownloadCatalog.Region region);
        /** Called on the main thread when the crosshair leaves a region. */
        void onLeave(DownloadCatalog.Region region);
    }

    // Written from any thread, read on main — volatile for safe publication.
    private volatile List<DownloadCatalog.Region> regions = Collections.emptyList();

    // Only accessed on the main thread.
    private Map<String, DownloadCatalog.Region> current = new HashMap<>();
    private Map<String, DownloadCatalog.Region> next    = new HashMap<>();
    private Listener listener;

    public RegionDetector(List<DownloadCatalog.Region> regions) {
        this.regions = regions;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Replace the region list (safe to call from any thread). */
    public void updateRegions(List<DownloadCatalog.Region> regions) {
        this.regions = regions;
    }

    /**
     * Test (lat, lon) against all regions and fire enter/leave events.
     * Must be called on the main thread.
     */
    public void check(double lat, double lon) {
        if (listener == null) return;

        List<DownloadCatalog.Region> snapshot = regions;

        // Build the new inside-set (reuse map to avoid per-call allocation)
        next.clear();
        for (DownloadCatalog.Region r : snapshot) {
            if (contains(r.polygon, lon, lat)) {
                next.put(r.id, r);
            }
        }

        // Fire onEnter for newly entered regions
        for (DownloadCatalog.Region r : next.values()) {
            if (!current.containsKey(r.id)) {
                listener.onEnter(r);
            }
        }

        // Fire onLeave for exited regions
        for (Map.Entry<String, DownloadCatalog.Region> e : current.entrySet()) {
            if (!next.containsKey(e.getKey())) {
                listener.onLeave(e.getValue());
            }
        }

        // Swap so next becomes current; current is cleared on the next call.
        Map<String, DownloadCatalog.Region> tmp = current;
        current = next;
        next = tmp;
    }

    // -------------------------------------------------------------------------
    // Point-in-polygon — ray casting
    // -------------------------------------------------------------------------

    /**
     * Returns true if (lon, lat) is inside the polygon.
     * Polygon points are [lon, lat] pairs. Uses the ray-casting algorithm:
     * cast a ray in the +lon direction and count edge crossings.
     */
    static boolean contains(List<double[]> polygon, double lon, double lat) {
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i)[0], yi = polygon.get(i)[1];
            double xj = polygon.get(j)[0], yj = polygon.get(j)[1];
            if (((yi > lat) != (yj > lat))
                    && (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }
}
