package de.codevoid.aNavMode.map;

import android.content.Context;
import android.location.Location;
import android.os.CancellationSignal;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * One-shot location fetch using the Fused Location Provider.
 * Tries last known location first (instant); falls back to a fresh fix
 * (up to 10 s) if none is cached.
 *
 * Requires ACCESS_FINE_LOCATION permission to be granted before calling.
 */
public class LocationHelper {

    public interface Callback {
        void onLocation(double lat, double lon);
    }

    private final FusedLocationProviderClient client;
    private CancellationSignal cancellationSignal;

    public LocationHelper(Context context) {
        client = LocationServices.getFusedLocationProviderClient(context);
    }

    @SuppressWarnings("MissingPermission")
    public void fetchOnce(Callback callback) {
        // Try cached location first — instant if available
        client.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                callback.onLocation(location.getLatitude(), location.getLongitude());
            } else {
                fetchFresh(callback);
            }
        }).addOnFailureListener(e -> fetchFresh(callback));
    }

    @SuppressWarnings("MissingPermission")
    private void fetchFresh(Callback callback) {
        cancellationSignal = new CancellationSignal();
        CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(10_000)
                .setDurationMillis(10_000)
                .build();

        client.getCurrentLocation(request, cancellationSignal)
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        callback.onLocation(location.getLatitude(), location.getLongitude());
                    }
                });
    }

    public void cancel() {
        if (cancellationSignal != null) {
            cancellationSignal.cancel();
            cancellationSignal = null;
        }
    }
}
