package de.codevoid.aNavMode.download;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the mirror catalog (index.json).
 *
 * On first run the bundled asset is copied to internal storage.
 * Subsequent runs load from disk. On refresh the new file is downloaded
 * to a temp location, validated, then atomically renamed into place.
 *
 * All methods must be called from a background thread.
 */
public class DownloadCatalog {

    private static final String ASSET_NAME  = "index.json";
    private static final String CATALOG_DIR = "catalog";
    private static final int    CONNECT_MS  = 10_000;
    private static final int    READ_MS     = 30_000;

    // -------------------------------------------------------------------------
    // Data model
    // -------------------------------------------------------------------------

    public static final class CatalogFile {
        public final String type;      // "map" | "poi" | "base"
        public final String path;      // relative to mirror root
        public final long   size;      // bytes
        public final String modified;  // ISO-8601

        CatalogFile(String type, String path, long size, String modified) {
            this.type = type; this.path = path; this.size = size; this.modified = modified;
        }
    }

    public static final class Tile {
        public final String path;      // e.g. "segments4/E5_N45.rd5"
        public final long   size;
        public final String modified;

        Tile(String path, long size, String modified) {
            this.path = path; this.size = size; this.modified = modified;
        }
    }

    public static final class Region {
        /** e.g. "germany/Germany-South" */
        public final String           id;
        /** Display name derived from id: last segment, dashes → spaces */
        public final String           name;
        /** Closed polygon ring as [lon, lat] pairs */
        public final List<double[]>   polygon;
        public final List<CatalogFile> files;
        /** Tile coordinate keys required for routing, e.g. "E5_N45" */
        public final List<String>     tiles;

        Region(String id, String name, List<double[]> polygon,
               List<CatalogFile> files, List<String> tiles) {
            this.id      = id;
            this.name    = name;
            this.polygon = Collections.unmodifiableList(polygon);
            this.files   = Collections.unmodifiableList(files);
            this.tiles   = Collections.unmodifiableList(tiles);
        }
    }

    public static final class Catalog {
        public final String             generated;
        public final List<Region>       regions;
        /** All known tiles, keyed by coordinate string (e.g. "E5_N45") */
        public final Map<String, Tile>  tiles;

        Catalog(String generated, List<Region> regions, Map<String, Tile> tiles) {
            this.generated = generated;
            this.regions   = Collections.unmodifiableList(regions);
            this.tiles     = Collections.unmodifiableMap(tiles);
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Context context;
    private final File    catalogFile;
    private final File    catalogTmp;

    public DownloadCatalog(Context context) {
        this.context = context.getApplicationContext();
        File dir = new File(context.getFilesDir(), CATALOG_DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        catalogFile = new File(dir, ASSET_NAME);
        catalogTmp  = new File(dir, ASSET_NAME + ".tmp");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Loads the catalog from disk, seeding from the bundled asset on first run.
     * If the bundled asset is newer than the on-disk file, the asset wins —
     * this ensures app updates that extend the region list take effect immediately.
     * A mirror refresh (which is even newer) will still overwrite both.
     */
    public Catalog load() throws IOException, JSONException {
        if (!catalogFile.exists()) {
            copyAssetToDisk();
        } else {
            // Prefer the bundled asset if it has a newer "generated" timestamp.
            try {
                String assetJson = readAsset();
                String diskJson  = readFile(catalogFile);
                String assetGen  = new org.json.JSONObject(assetJson).optString("generated", "");
                String diskGen   = new org.json.JSONObject(diskJson).optString("generated", "");
                if (!assetGen.isEmpty() && assetGen.compareTo(diskGen) > 0) {
                    copyAssetToDisk();
                }
            } catch (Exception ignored) {
                // If comparison fails, keep the disk version — don't break a good catalog.
            }
        }
        return parse(readFile(catalogFile));
    }

    /**
     * Downloads a fresh index.json from mirrorBaseUrl, validates it,
     * and atomically replaces the on-disk catalog.
     */
    public Catalog refresh(String mirrorBaseUrl, String authHeader) throws IOException, JSONException {
        String url = mirrorBaseUrl.endsWith("/")
                ? mirrorBaseUrl + ASSET_NAME
                : mirrorBaseUrl + "/" + ASSET_NAME;

        download(url, authHeader, catalogTmp);

        // Validate before committing
        String json = readFile(catalogTmp);
        Catalog catalog = parse(json);

        // Atomic replace — tmp and target are in the same directory
        if (!catalogTmp.renameTo(catalogFile)) {
            copyFile(catalogTmp, catalogFile);
            //noinspection ResultOfMethodCallIgnored
            catalogTmp.delete();
        }

        return catalog;
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void copyAssetToDisk() throws IOException {
        try (InputStream in  = context.getAssets().open(ASSET_NAME);
             OutputStream out = new FileOutputStream(catalogFile)) {
            pipe(in, out);
        }
    }

    private String readAsset() throws IOException {
        try (InputStream in = context.getAssets().open(ASSET_NAME)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            pipe(in, buf);
            return buf.toString("UTF-8");
        }
    }

    private void download(String urlStr, String authHeader, File dest) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_MS);
        conn.setReadTimeout(READ_MS);
        if (authHeader != null) conn.setRequestProperty("Authorization", authHeader);
        conn.connect();
        int status = conn.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new IOException("HTTP " + status);
        }
        try (InputStream  in  = conn.getInputStream();
             OutputStream out = new FileOutputStream(dest)) {
            pipe(in, out);
        } finally {
            conn.disconnect();
        }
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    private static Catalog parse(String json) throws JSONException {
        JSONObject root      = new JSONObject(json);
        String     generated = root.optString("generated", "");

        JSONArray regionsArr = root.getJSONArray("regions");
        List<Region> regions = new ArrayList<>(regionsArr.length());
        for (int i = 0; i < regionsArr.length(); i++) {
            regions.add(parseRegion(regionsArr.getJSONObject(i)));
        }

        JSONObject         tilesObj = root.getJSONObject("tiles");
        Map<String, Tile>  tiles    = new LinkedHashMap<>();
        Iterator<String>   keys     = tilesObj.keys();
        while (keys.hasNext()) {
            String     coord = keys.next();
            JSONObject t     = tilesObj.getJSONObject(coord);
            tiles.put(coord, new Tile(
                    t.getString("path"),
                    t.getLong("size"),
                    t.getString("modified")));
        }

        return new Catalog(generated, regions, tiles);
    }

    private static Region parseRegion(JSONObject obj) throws JSONException {
        String id   = obj.getString("id");
        String name = id.contains("/") ? id.substring(id.lastIndexOf('/') + 1) : id;
        name = name.replace('-', ' ');

        JSONArray     polyArr = obj.getJSONArray("polygon");
        List<double[]> polygon = new ArrayList<>(polyArr.length());
        for (int i = 0; i < polyArr.length(); i++) {
            JSONArray pt = polyArr.getJSONArray(i);
            polygon.add(new double[]{pt.getDouble(0), pt.getDouble(1)}); // [lon, lat]
        }

        JSONArray         filesArr = obj.getJSONArray("files");
        List<CatalogFile> files    = new ArrayList<>(filesArr.length());
        for (int i = 0; i < filesArr.length(); i++) {
            JSONObject f = filesArr.getJSONObject(i);
            files.add(new CatalogFile(
                    f.getString("type"),
                    f.getString("path"),
                    f.getLong("size"),
                    f.getString("modified")));
        }

        JSONArray    tilesArr = obj.getJSONArray("tiles");
        List<String> tiles    = new ArrayList<>(tilesArr.length());
        for (int i = 0; i < tilesArr.length(); i++) {
            tiles.add(tilesArr.getString(i));
        }

        return new Region(id, name, polygon, files, tiles);
    }

    // -------------------------------------------------------------------------
    // IO helpers
    // -------------------------------------------------------------------------

    private static String readFile(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n == -1) break;
                off += n;
            }
        }
        return new String(buf, "UTF-8");
    }

    private static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream  in  = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            pipe(in, out);
        }
    }
}
