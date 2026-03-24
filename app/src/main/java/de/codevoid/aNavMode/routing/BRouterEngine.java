package de.codevoid.aNavMode.routing;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import btools.router.OsmNodeNamed;
import btools.router.OsmPathElement;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;

/**
 * Routes via bundled BRouter core (no separate BRouter APK required).
 *
 * Segment files (.rd5) must be placed at:
 *   {filesDir}/brouter/segments4/
 *
 * Profile scripts are bundled in assets/brouter/profiles/ and copied
 * to getFilesDir()/brouter/profiles/ on first call.
 *
 * Supported profiles: trekking, car-fast, gravel
 */
public class BRouterEngine implements de.codevoid.aNavMode.routing.RoutingEngine {

    private static final String TAG = "BRouterEngine";

    // Segment files live in internal storage (no permissions needed).
    private static final String SEGMENTS_SUBDIR = "brouter/segments4";

    // Profile name → bundled asset file
    private static final String[] PROFILE_ASSETS = {
            "trekking.brf", "car-fast.brf", "gravel.brf", "lookups.dat"
    };

    private final Context context;
    private File profilesDir;

    public BRouterEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public List<RoutePoint> calculateRoute(double fromLat, double fromLon,
                                           double toLat, double toLon,
                                           String profile) {
        try {
            ensureProfiles();

            File segmentDir = new File(context.getFilesDir(), SEGMENTS_SUBDIR);

            if (!segmentDir.exists()) {
                Log.w(TAG, "BRouter segment dir missing: " + segmentDir);
                return null;
            }

            File profileFile = new File(profilesDir, profile + ".brf");
            if (!profileFile.exists()) {
                Log.w(TAG, "Profile not found: " + profileFile);
                return null;
            }

            List<OsmNodeNamed> waypoints = new ArrayList<>(2);
            OsmNodeNamed from = makeNode(fromLon, fromLat);
            from.name = "from";
            OsmNodeNamed to = makeNode(toLon, toLat);
            to.name = "to";
            waypoints.add(from);
            waypoints.add(to);

            RoutingContext rc = new RoutingContext();
            rc.localFunction = profileFile.getAbsolutePath();

            RoutingEngine engine = new RoutingEngine(
                    null, null, segmentDir, waypoints, rc);
            engine.quite = true;
            engine.start();
            engine.join(60_000); // max 60 s per segment

            if (engine.getErrorMessage() != null) {
                Log.w(TAG, "BRouter error: " + engine.getErrorMessage());
                return null;
            }

            List<OsmPathElement> nodes = engine.getFoundTrack().nodes;
            if (nodes == null || nodes.isEmpty()) return null;

            List<RoutePoint> result = new ArrayList<>(nodes.size());
            for (OsmPathElement node : nodes) {
                double lat = node.getILat() / 1_000_000.0 - 90.0;
                double lon = node.getILon() / 1_000_000.0 - 180.0;
                result.add(new RoutePoint(lat, lon));
            }
            return result;

        } catch (Exception e) {
            Log.e(TAG, "routing failed", e);
            return null;
        }
    }

    /** Copy profile assets to internal storage the first time. */
    private void ensureProfiles() throws Exception {
        if (profilesDir != null) return;

        File dir = new File(context.getFilesDir(), "brouter/profiles");
        if (!dir.exists()) dir.mkdirs();

        AssetManager am = context.getAssets();
        for (String asset : PROFILE_ASSETS) {
            File dest = new File(dir, asset);
            if (!dest.exists()) {
                try (InputStream in = am.open("brouter/profiles/" + asset);
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
            }
        }
        profilesDir = dir;
    }

    private static OsmNodeNamed makeNode(double lon, double lat) {
        OsmNodeNamed n = new OsmNodeNamed();
        n.ilon = (int) ((lon + 180.0) * 1_000_000.0 + 0.5);
        n.ilat = (int) ((lat +  90.0) * 1_000_000.0 + 0.5);
        return n;
    }

}
