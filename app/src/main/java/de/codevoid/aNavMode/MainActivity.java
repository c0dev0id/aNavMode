package de.codevoid.aNavMode;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.View;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.core.util.Parameters;

import de.codevoid.aNavMode.debug.DebugSheet;
import de.codevoid.aNavMode.download.DownloadCatalog;
import de.codevoid.aNavMode.download.DownloadDomain;
import de.codevoid.aNavMode.download.RegionDetector;
import de.codevoid.aNavMode.ui.DownloadCardStack;
import de.codevoid.aNavMode.map.LocationHelper;
import de.codevoid.aNavMode.map.MapManager;
import de.codevoid.aNavMode.map.PanController;
import de.codevoid.aNavMode.map.RegionOverlayLayer;
import de.codevoid.aNavMode.map.WaypointLayer;
import de.codevoid.aNavMode.remote.RemoteControlManager;
import de.codevoid.aNavMode.remote.RemoteEvent;
import de.codevoid.aNavMode.routing.BRouterEngine;
import de.codevoid.aNavMode.routing.RoutingDomain;

public class MainActivity extends AppCompatActivity
        implements DebugSheet.Callbacks, WaypointLayer.FailureListener {

    private MapView               mapView;
    private MapManager            mapManager;
    private boolean               polygonsForced = false;
    private RoutingDomain         routingDomain;
    private WaypointLayer         waypointLayer;
    private RemoteControlManager  remoteControl;
    private PanController         panController;
    private LocationHelper        locationHelper;
    private RegionDetector        regionDetector;
    private RegionOverlayLayer    regionOverlay;
    private DownloadCardStack     downloadCards;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        private long lastFrameNanos = 0;
        private org.mapsforge.core.model.LatLong lastCheckedCenter = null;

        @Override
        public void doFrame(long frameTimeNanos) {
            if (lastFrameNanos != 0) {
                panController.onFrame(frameTimeNanos, frameTimeNanos - lastFrameNanos);
            }
            lastFrameNanos = frameTimeNanos;
            if (regionDetector != null) {
                org.mapsforge.core.model.LatLong c =
                        mapView.getModel().mapViewPosition.getCenter();
                if (!c.equals(lastCheckedCenter)) {
                    regionDetector.check(c.latitude, c.longitude);
                    lastCheckedCenter = c;
                }
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private static final int REQ_STORAGE  = 1;
    private static final int REQ_LOCATION = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidGraphicFactory.createInstance(getApplication());
        Parameters.FRACTIONAL_ZOOM = true;
        setContentView(R.layout.activity_main);

        mapView      = findViewById(R.id.mapView);
        mapManager   = new MapManager(this, mapView);

        requestStorageAndInit();

        panController = new PanController(mapView);

        remoteControl = new RemoteControlManager(this);
        remoteControl.setListener(this::handleRemoteEvent);

        FloatingActionButton fabAdd = findViewById(R.id.fabAddWaypoint);
        fabAdd.setOnClickListener(v -> { if (routingDomain != null) routingDomain.addAtCenter(); });

        View debugPanel = findViewById(R.id.debugPanel);
        new DebugSheet(this, debugPanel, this, polygonsForced);

        FloatingActionButton fab = findViewById(R.id.fabDebug);
        fab.setOnClickListener(v ->
                debugPanel.setVisibility(
                        debugPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    }

    private void initMap() {
        mapManager.setInitialPosition();

        // Domain and layer are ready immediately; map tiles stream in once the file is open
        routingDomain = new RoutingDomain(new BRouterEngine(this), mapView);

        // RegionOverlayLayer sits above tiles (index 1), below WaypointLayer
        regionOverlay = new RegionOverlayLayer(mapView);
        mapView.getLayerManager().getLayers().add(regionOverlay);

        // WaypointLayer must be the last (topmost) layer so it receives taps first
        waypointLayer = new WaypointLayer(mapView, routingDomain,
                getResources().getDisplayMetrics().density);
        waypointLayer.setFailureListener(this);
        mapView.getLayerManager().getLayers().add(waypointLayer);

        locationHelper = new LocationHelper(this);
        locateAndCenter();

        new Thread(() -> {
            try {
                android.util.Log.d("MainActivity", "catalog-loader: start");
                DownloadCatalog catalog = new DownloadCatalog(this);
                DownloadCatalog.Catalog c = catalog.load();
                android.util.Log.d("MainActivity", "catalog-loader: loaded " + c.regions.size() + " regions");
                if (DownloadDomain.getInstance() == null) {
                    new DownloadDomain(MainActivity.this, c);
                }
                DownloadDomain domain = DownloadDomain.getInstance();
                android.util.Log.d("MainActivity", "catalog-loader: domain ready, queue=" + domain.queueSize());

                android.widget.LinearLayout cardContainer =
                        findViewById(R.id.downloadCardContainer);
                DownloadCardStack stack = new DownloadCardStack(cardContainer, domain);
                domain.addListener(stack);
                domain.addListener(state -> {
                    if (state.queue.isEmpty()) {
                        // All downloads done — reload tile layer to include new maps
                        mapManager.loadMapAsync(new MapManager.LoadCallback() {
                            @Override public void onLoaded() {}
                            @Override public void onError(String r) {}
                        });
                    }
                });

                RegionDetector detector = new RegionDetector(c.regions);
                detector.setListener(new RegionDetector.Listener() {
                    @Override public void onEnter(DownloadCatalog.Region r) {
                        regionOverlay.setCurrentRegion(r.id);
                        stack.onEnter(r);
                    }
                    @Override public void onLeave(DownloadCatalog.Region r) {
                        regionOverlay.setCurrentRegion(null);
                        stack.onLeave(r);
                    }
                });
                regionOverlay.updateRegions(c.regions);
                runOnUiThread(() -> {
                    android.util.Log.d("MainActivity", "catalog-loader: detector active");
                    regionDetector = detector;
                    downloadCards  = stack;
                });

                // Refresh catalog from mirror in background; update detector + overlay
                // with the fresh region list so newly added regions appear immediately.
                domain.refreshCatalogAsync(regions -> {
                    detector.updateRegions(regions);
                    regionOverlay.updateRegions(regions);
                });
            } catch (Exception e) {
                android.util.Log.w("MainActivity", "catalog load failed", e);
            }
        }, "catalog-loader").start();

        // Load the map file off the main thread; tile layer is inserted at index 0 when ready
        mapManager.loadMapAsync(new MapManager.LoadCallback() {
            @Override public void onLoaded() { /* tile layer live */ }
            @Override public void onError(String reason) { /* world.map always present */ }
        });
    }

    private void locateAndCenter() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            return;
        }
        locationHelper.fetchOnce((lat, lon) ->
                mapView.getModel().mapViewPosition.setCenter(
                        new org.mapsforge.core.model.LatLong(lat, lon)));
    }

    private void requestStorageAndInit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: need All Files Access to read /sdcard/aNavMode/
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_STORAGE);
                return;
            }
        } else {
            // Android 8-10: READ_EXTERNAL_STORAGE suffices
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
                return;
            }
        }
        initMap();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_STORAGE) {
            initMap(); // proceed regardless; loadMap() handles missing/unreadable file gracefully
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            initMap();
        } else if (requestCode == REQ_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                locateAndCenter();
            }
        }
    }

    private void handleRemoteEvent(RemoteEvent event) {
        if (event instanceof RemoteEvent.ShortPress) {
            RemoteEvent.ShortPress e = (RemoteEvent.ShortPress) event;
            switch (e.key) {
                case CONFIRM: if (routingDomain != null) routingDomain.addAtCenter();        break;
                case BACK:    if (routingDomain != null) routingDomain.removeLastWaypoint(); break;
                default: break;
            }
        } else if (event instanceof RemoteEvent.KeyDown) {
            panController.onKeyDown(((RemoteEvent.KeyDown) event).key);
        } else if (event instanceof RemoteEvent.KeyUp) {
            panController.onKeyUp(((RemoteEvent.KeyUp) event).key);
        } else if (event instanceof RemoteEvent.JoyInput) {
            RemoteEvent.JoyInput e = (RemoteEvent.JoyInput) event;
            panController.onJoyInput(e.dx, e.dy);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        remoteControl.register();
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    @Override
    protected void onPause() {
        remoteControl.unregister();
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        super.onPause();
    }

    // --- WaypointLayer.FailureListener ---

    @Override
    public void onRoutingFailed(int segmentIndex) {
        Toast.makeText(this,
                "Routing failed — is BRouter running?",
                Toast.LENGTH_LONG).show();
    }

    // --- DebugSheet.Callbacks ---

    @Override
    public void onDownloadMap() {
        Toast.makeText(this, "Map downloader — not yet implemented", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onUpdateRouteData() {
        Toast.makeText(this, "BRouter segment updater — not yet implemented", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onClearWaypoints() {
        routingDomain.clearAll();
    }

    @Override
    public void onTogglePolygons(boolean show) {
        polygonsForced = show;
        if (regionOverlay != null) regionOverlay.setForceShowPolygons(show);
    }

    @Override
    protected void onDestroy() {
        if (locationHelper  != null) locationHelper.cancel();
        if (waypointLayer   != null) waypointLayer.destroy();
        if (regionOverlay   != null) mapView.getLayerManager().getLayers().remove(regionOverlay);
        if (routingDomain   != null) routingDomain.destroy();
        DownloadDomain dd = DownloadDomain.getInstance();
        if (dd != null && downloadCards != null) dd.removeListener(downloadCards);
        mapManager.destroy();
        mapView.destroyAll();
        AndroidGraphicFactory.clearResourceMemoryCache();
        super.onDestroy();
    }
}
