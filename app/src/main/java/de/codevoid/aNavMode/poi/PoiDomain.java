package de.codevoid.aNavMode.poi;

import android.content.Context;
import android.util.Log;

import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.Tag;
import org.mapsforge.poi.android.storage.AndroidPoiPersistenceManagerFactory;
import org.mapsforge.poi.storage.PointOfInterest;
import org.mapsforge.poi.storage.PoiCategory;
import org.mapsforge.poi.storage.PoiPersistenceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns all POI database connections and serves viewport queries.
 *
 * On construction, scans {filesDir}/pois/ recursively for .poi files and opens
 * each via AndroidPoiPersistenceManagerFactory. Queries are serialised on a single
 * background thread and results published as immutable State snapshots to listeners.
 *
 * Category names must match the titles stored in the POI database.
 * If queries return empty results, verify category titles via pm.getCategoryManager().
 */
public class PoiDomain {

    private static final String TAG = "PoiDomain";

    // Category title strings as stored by the mapsforge-poi-writer (default mapping).
    private static final String CAT_FUEL       = "Fuel";
    private static final String CAT_PARKING    = "Parking";
    private static final String CAT_RESTAURANT = "Restaurant";
    private static final String CAT_CAFE       = "Cafe";
    private static final String CAT_FAST_FOOD  = "Fast Food";

    private static final int    QUERY_LIMIT       = 500;
    // Re-query when viewport centre moves more than this fraction of the bbox width.
    private static final double REQUERY_THRESHOLD = 0.25;

    // -------------------------------------------------------------------------
    // Public data model
    // -------------------------------------------------------------------------

    public enum Category { FUEL, FOOD, PARKING }

    public static final class Poi {
        public final double   latitude;
        public final double   longitude;
        public final String   name;
        public final Category category;
        /** Phone number from OSM tags; null if not present in POI data. */
        public final String   phone;

        Poi(double lat, double lon, String name, Category category, String phone) {
            this.latitude  = lat;
            this.longitude = lon;
            this.name      = name != null ? name : "";
            this.category  = category;
            this.phone     = phone;
        }
    }

    public static final class State {
        public final List<Poi> pois;
        public static final State EMPTY = new State(Collections.emptyList());

        State(List<Poi> pois) {
            this.pois = Collections.unmodifiableList(pois);
        }
    }

    public interface Listener {
        void onPoiStateChanged(State state);
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Context                        context;
    private final ExecutorService                executor;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    // Accessed only on executor thread
    private final List<PoiPersistenceManager> managers = new ArrayList<>();

    // Volatile: checked on the calling thread (render thread) before submitting to executor,
    // so rapid draw() calls don't flood the queue with duplicate queries.
    private volatile BoundingBox lastBbox = null;

    private volatile State currentState = State.EMPTY;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public PoiDomain(Context context) {
        this.context  = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "poi-query"));
        executor.execute(this::openPoiFiles);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void addListener(Listener l)    { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    public State getState()                { return currentState; }

    /**
     * Queries all open POI databases for the given viewport bounding box.
     * Skips the query if the viewport hasn't moved significantly since the last one.
     * Results are delivered asynchronously to all registered listeners.
     * Safe to call from any thread.
     */
    public void queryViewport(BoundingBox bbox) {
        // Fast path on calling thread: skip submission if viewport hasn't moved enough.
        // lastBbox is volatile so the render thread can read it without locking.
        if (isSimilarEnough(bbox, lastBbox)) return;
        lastBbox = bbox; // update before submit so concurrent calls don't double-queue
        executor.execute(() -> {
            if (managers.isEmpty()) return;

            List<Poi> results = new ArrayList<>();

            for (PoiPersistenceManager pm : managers) {
                try {
                    // null filter = accept all categories; we filter by category title in Java.
                    // findCategories=true so getCategory() is populated.
                    Collection<PointOfInterest> found =
                            pm.findInRect(bbox, null, null, null, QUERY_LIMIT, true);
                    for (PointOfInterest p : found) {
                        Category cat = toCategory(p.getCategory());
                        if (cat == null) continue;
                        results.add(new Poi(
                                p.getLatitude(), p.getLongitude(),
                                p.getName(), cat,
                                extractPhone(p.getTags())));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "query failed: " + e.getMessage());
                }
            }

            currentState = new State(results);
            for (Listener l : listeners) l.onPoiStateChanged(currentState);
        });
    }

    /**
     * Closes all open databases and re-scans for .poi files.
     * Call after a region download completes.
     */
    public void reload() {
        lastBbox = null; // force a fresh query after reload
        executor.execute(() -> {
            closeAll();
            openPoiFiles();
        });
    }

    public void destroy() {
        executor.execute(this::closeAll);
        executor.shutdown();
    }

    // -------------------------------------------------------------------------
    // File discovery — executor thread only
    // -------------------------------------------------------------------------

    private void openPoiFiles() {
        File poisDir = new File(context.getFilesDir(), "pois");
        if (poisDir.isDirectory()) scanDir(poisDir);
        Log.d(TAG, "opened " + managers.size() + " POI database(s)");
    }

    private void scanDir(File dir) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File f : entries) {
            if (f.isDirectory()) {
                scanDir(f);
            } else if (f.getName().endsWith(".poi")) {
                try {
                    managers.add(AndroidPoiPersistenceManagerFactory
                            .getPoiPersistenceManager(f.getAbsolutePath()));
                    Log.d(TAG, "opened: " + f.getName());
                } catch (Exception e) {
                    Log.w(TAG, "failed to open " + f.getName() + ": " + e.getMessage());
                }
            }
        }
    }

    private void closeAll() {
        for (PoiPersistenceManager pm : managers) {
            try { pm.close(); } catch (Exception ignored) {}
        }
        managers.clear();
    }

    // -------------------------------------------------------------------------
    // Category matching
    // -------------------------------------------------------------------------

    private static Category toCategory(PoiCategory cat) {
        if (cat == null) return null;
        String t = cat.getTitle();
        if (CAT_FUEL.equals(t))                                          return Category.FUEL;
        if (CAT_PARKING.equals(t))                                       return Category.PARKING;
        if (CAT_RESTAURANT.equals(t) || CAT_CAFE.equals(t)
                || CAT_FAST_FOOD.equals(t))                              return Category.FOOD;
        return toCategory(cat.getParent());
    }

    // -------------------------------------------------------------------------
    // Tag helpers
    // -------------------------------------------------------------------------

    private static String extractPhone(Set<Tag> tags) {
        if (tags == null) return null;
        String phone = null;
        for (Tag tag : tags) {
            if ("phone".equals(tag.key) || "contact:phone".equals(tag.key)) {
                phone = tag.value;
                if ("phone".equals(tag.key)) break; // prefer plain "phone"
            }
        }
        return phone;
    }

    // -------------------------------------------------------------------------
    // Viewport similarity
    // -------------------------------------------------------------------------

    private static boolean isSimilarEnough(BoundingBox a, BoundingBox b) {
        if (b == null) return false;
        double threshold = (a.maxLongitude - a.minLongitude) * REQUERY_THRESHOLD;
        double acLat = (a.minLatitude  + a.maxLatitude)  / 2;
        double acLon = (a.minLongitude + a.maxLongitude) / 2;
        double bcLat = (b.minLatitude  + b.maxLatitude)  / 2;
        double bcLon = (b.minLongitude + b.maxLongitude) / 2;
        return Math.abs(acLat - bcLat) < threshold
            && Math.abs(acLon - bcLon) < threshold
            && Math.abs((a.maxLatitude - a.minLatitude) - (b.maxLatitude - b.minLatitude)) < 1e-6;
    }
}
