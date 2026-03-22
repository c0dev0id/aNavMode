package de.codevoid.aNavMode;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.view.MapView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.codevoid.aNavMode.debug.DebugSheet;
import de.codevoid.aNavMode.map.MapManager;
import de.codevoid.aNavMode.map.RouteOverlayManager;
import de.codevoid.aNavMode.routing.BRouterHttpClient;
import de.codevoid.aNavMode.routing.RoutePoint;
import de.codevoid.aNavMode.routing.RoutingEngine;

public class MainActivity extends AppCompatActivity implements DebugSheet.Callbacks {

    private MapView mapView;
    private MapManager mapManager;
    private RouteOverlayManager routeOverlay;
    private RoutingEngine router;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidGraphicFactory.createInstance(getApplication());
        setContentView(R.layout.activity_main);

        mapView = findViewById(R.id.mapView);
        mapManager = new MapManager(this, mapView);
        routeOverlay = new RouteOverlayManager(mapView);
        router = new BRouterHttpClient();

        if (!mapManager.loadMap(mapManager.getDefaultMapFile())) {
            Toast.makeText(this,
                    "No map file found. Use Debug → Download Offline Map.",
                    Toast.LENGTH_LONG).show();
        }

        mapManager.setInitialPosition();

        FloatingActionButton fab = findViewById(R.id.fabDebug);
        fab.setOnClickListener(v -> new DebugSheet(this, this).show());
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
    public void onTestRoute() {
        // TODO: Replace with real test coordinates inside your loaded map area.
        // BRouter must be installed and its service running on the device.
        final double fromLat = 0.0, fromLon = 0.0;
        final double toLat   = 0.0, toLon   = 0.0;

        executor.execute(() -> {
            List<RoutePoint> points = router.calculateRoute(fromLat, fromLon, toLat, toLon, "trekking");
            runOnUiThread(() -> {
                if (points == null || points.isEmpty()) {
                    Toast.makeText(this,
                            "Routing failed — is BRouter running?",
                            Toast.LENGTH_LONG).show();
                } else {
                    routeOverlay.showRoute(points);
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        mapManager.destroy();
        mapView.destroyAll();
        AndroidGraphicFactory.clearResourceMemoryCache();
        super.onDestroy();
    }
}
