package de.codevoid.aNavMode;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.view.MapView;

import de.codevoid.aNavMode.debug.DebugSheet;
import de.codevoid.aNavMode.map.MapManager;
import de.codevoid.aNavMode.map.WaypointLayer;
import de.codevoid.aNavMode.routing.BRouterHttpClient;

public class MainActivity extends AppCompatActivity
        implements DebugSheet.Callbacks, WaypointLayer.Listener {

    private MapView      mapView;
    private MapManager   mapManager;
    private WaypointLayer waypointLayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidGraphicFactory.createInstance(getApplication());
        setContentView(R.layout.activity_main);

        mapView      = findViewById(R.id.mapView);
        mapManager   = new MapManager(this, mapView);

        if (!mapManager.loadMap(mapManager.getDefaultMapFile())) {
            Toast.makeText(this,
                    "No map file. Use Debug → Download Offline Map.",
                    Toast.LENGTH_LONG).show();
        }

        mapManager.setInitialPosition();

        // WaypointLayer must be added last — it needs to be top of stack to receive taps first
        waypointLayer = new WaypointLayer(mapView, new BRouterHttpClient(),
                getResources().getDisplayMetrics().density);
        waypointLayer.setListener(this);
        mapView.getLayerManager().getLayers().add(waypointLayer);

        FloatingActionButton fabAdd = findViewById(R.id.fabAddWaypoint);
        fabAdd.setOnClickListener(v -> waypointLayer.addAtCenter());

        FloatingActionButton fab = findViewById(R.id.fabDebug);
        fab.setOnClickListener(v -> new DebugSheet(this, this).show());
    }

    // --- WaypointLayer.Listener ---

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
        waypointLayer.clearAll();
    }

    @Override
    protected void onDestroy() {
        waypointLayer.destroy();
        mapManager.destroy();
        mapView.destroyAll();
        AndroidGraphicFactory.clearResourceMemoryCache();
        super.onDestroy();
    }
}
