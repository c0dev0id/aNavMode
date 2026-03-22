package de.codevoid.aNavMode.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Routes via BRouter's built-in HTTP server (localhost:17777).
 * Requires the BRouter app to be installed and its service running.
 *
 * Start BRouter's service from the debug sheet before requesting a route.
 */
public class BRouterHttpClient implements RoutingEngine {

    private static final String BASE_URL = "http://localhost:17777/brouter";

    @Override
    public List<RoutePoint> calculateRoute(double fromLat, double fromLon,
                                           double toLat, double toLon,
                                           String profile) {
        try {
            // GeoJSON spec: lon,lat order
            String urlStr = BASE_URL
                    + "?lonlats=" + fromLon + "," + fromLat
                    + "|" + toLon + "," + toLat
                    + "&profile=" + profile
                    + "&alternativeidx=0"
                    + "&format=geojson";

            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);
            conn.connect();

            if (conn.getResponseCode() != 200) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }

            return parseGeoJson(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private List<RoutePoint> parseGeoJson(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray coords = root
                .getJSONArray("features")
                .getJSONObject(0)
                .getJSONObject("geometry")
                .getJSONArray("coordinates");

        List<RoutePoint> points = new ArrayList<>(coords.length());
        for (int i = 0; i < coords.length(); i++) {
            JSONArray c = coords.getJSONArray(i);
            // GeoJSON is [lon, lat, ele] — swap to (lat, lon)
            points.add(new RoutePoint(c.getDouble(1), c.getDouble(0)));
        }
        return points;
    }
}
