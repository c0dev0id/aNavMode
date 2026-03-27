package de.codevoid.aNavMode.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.codevoid.aNavMode.download.DownloadCatalog;
import de.codevoid.aNavMode.download.DownloadDomain;

/**
 * Full-screen dialog listing all available map regions sorted by distance
 * to the current crosshair position. Shows download status, progress,
 * speed, and contextual action buttons.
 */
public class MapDownloadSheet implements DownloadDomain.Listener {

    private static final int COLOR_BG         = Color.argb(240, 24, 24, 42);
    private static final int COLOR_ROW        = Color.argb(200, 32, 32, 56);
    private static final int COLOR_ROW_ALT    = Color.argb(200, 38, 38, 64);
    private static final int COLOR_TEXT        = Color.WHITE;
    private static final int COLOR_SECONDARY  = Color.argb(180, 200, 200, 200);
    private static final int COLOR_BTN_DL     = Color.argb(255, 30, 140, 255);
    private static final int COLOR_BTN_DELETE  = Color.argb(255, 200, 60, 60);
    private static final int COLOR_BTN_CANCEL  = Color.argb(255, 160, 160, 160);
    private static final int COLOR_QUEUED      = Color.argb(255, 255, 180, 0);

    private final Context context;
    private final DownloadDomain domain;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, RowViews> rows = new HashMap<>();
    private LinearLayout listContainer;
    private AlertDialog dialog;
    private Runnable onMapReload;

    private static final class RowViews {
        final String regionId;
        final LinearLayout row;
        final TextView nameView;
        final TextView sizeView;
        final TextView statusView;
        final Button actionBtn;
        final ProgressBar progressBar;
        final TextView speedView;
        final LinearLayout progressRow;

        RowViews(String regionId, LinearLayout row, TextView nameView,
                 TextView sizeView, TextView statusView, Button actionBtn,
                 ProgressBar progressBar, TextView speedView,
                 LinearLayout progressRow) {
            this.regionId = regionId;
            this.row = row;
            this.nameView = nameView;
            this.sizeView = sizeView;
            this.statusView = statusView;
            this.actionBtn = actionBtn;
            this.progressBar = progressBar;
            this.speedView = speedView;
            this.progressRow = progressRow;
        }
    }

    private static final class RegionWithDistance implements Comparable<RegionWithDistance> {
        final DownloadCatalog.Region region;
        final double distanceKm;

        RegionWithDistance(DownloadCatalog.Region region, double distanceKm) {
            this.region = region;
            this.distanceKm = distanceKm;
        }

        @Override
        public int compareTo(RegionWithDistance o) {
            return Double.compare(this.distanceKm, o.distanceKm);
        }
    }

    /**
     * Shows the map download dialog.
     *
     * @param context      Activity context
     * @param crosshairLat Current crosshair latitude
     * @param crosshairLon Current crosshair longitude
     * @param domain       Download domain (must be initialized)
     * @param onMapReload  Called after delete/download completes so caller can reload tiles
     */
    public static void show(Context context, double crosshairLat, double crosshairLon,
                            DownloadDomain domain, Runnable onMapReload) {
        new MapDownloadSheet(context, crosshairLat, crosshairLon, domain, onMapReload);
    }

    private MapDownloadSheet(Context context, double crosshairLat, double crosshairLon,
                             DownloadDomain domain, Runnable onMapReload) {
        this.context = context;
        this.domain = domain;
        this.onMapReload = onMapReload;

        DownloadCatalog.Catalog catalog = domain.getCatalog();
        if (catalog == null || catalog.regions.isEmpty()) {
            android.widget.Toast.makeText(context,
                    "No regions available — catalog not loaded", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        // Sort regions by distance to crosshair
        List<RegionWithDistance> sorted = new ArrayList<>(catalog.regions.size());
        for (DownloadCatalog.Region r : catalog.regions) {
            double[] centroid = centroid(r.polygon);
            double dist = haversineKm(crosshairLat, crosshairLon, centroid[1], centroid[0]);
            sorted.add(new RegionWithDistance(r, dist));
        }
        Collections.sort(sorted);

        // Build UI
        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        listContainer.setPadding(pad, pad, pad, pad);

        for (int i = 0; i < sorted.size(); i++) {
            RegionWithDistance rwd = sorted.get(i);
            RowViews rv = buildRow(rwd.region, rwd.distanceKm, i % 2 == 1);
            rows.put(rwd.region.id, rv);
            listContainer.addView(rv.row);
        }

        // Apply current state
        applyCurrentState();

        ScrollView scroll = new ScrollView(context);
        scroll.addView(listContainer);
        scroll.setBackgroundColor(COLOR_BG);

        domain.addListener(this);

        dialog = new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                .setView(scroll)
                .setOnDismissListener(d -> domain.removeListener(this))
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
    }

    private RowViews buildRow(DownloadCatalog.Region region, double distKm, boolean alt) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        row.setPadding(pad, dp(8), pad, dp(8));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(alt ? COLOR_ROW_ALT : COLOR_ROW);
        bg.setCornerRadius(dp(6));
        row.setBackground(bg);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(4);
        row.setLayoutParams(rowParams);

        // ---- Top row: name + distance + size + button ----
        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        // Name + distance column
        LinearLayout nameCol = new LinearLayout(context);
        nameCol.setOrientation(LinearLayout.VERTICAL);

        TextView nameView = new TextView(context);
        nameView.setTextColor(COLOR_TEXT);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        nameView.setTypeface(null, Typeface.BOLD);
        nameView.setSingleLine(true);
        nameView.setText(region.name);
        nameCol.addView(nameView);

        TextView distView = new TextView(context);
        distView.setTextColor(COLOR_SECONDARY);
        distView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        distView.setText(formatDistance(distKm));
        nameCol.addView(distView);

        LinearLayout.LayoutParams nameColParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        topRow.addView(nameCol, nameColParams);

        // Size
        TextView sizeView = new TextView(context);
        sizeView.setTextColor(COLOR_SECONDARY);
        sizeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        long totalSize = domain.regionTotalSize(region);
        sizeView.setText(formatBytes(totalSize));
        LinearLayout.LayoutParams sizeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sizeParams.setMarginEnd(dp(8));
        topRow.addView(sizeView, sizeParams);

        // Status label (for queued/active)
        TextView statusView = new TextView(context);
        statusView.setTextColor(COLOR_QUEUED);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusView.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMarginEnd(dp(8));
        topRow.addView(statusView, statusParams);

        // Action button
        Button actionBtn = new Button(context);
        actionBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        actionBtn.setAllCaps(false);
        actionBtn.setPadding(dp(12), dp(4), dp(12), dp(4));
        actionBtn.setMinWidth(0);
        actionBtn.setMinimumWidth(0);
        actionBtn.setMinHeight(0);
        actionBtn.setMinimumHeight(0);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(dp(6));
        btnBg.setColor(COLOR_BTN_DL);
        actionBtn.setBackground(btnBg);
        actionBtn.setTextColor(Color.WHITE);
        topRow.addView(actionBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        row.addView(topRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- Progress row (hidden by default) ----
        LinearLayout progressRow = new LinearLayout(context);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams prParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        prParams.topMargin = dp(4);

        ProgressBar progressBar = new ProgressBar(context, null,
                android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                0, dp(6), 1f);
        progressRow.addView(progressBar, pbParams);

        TextView speedView = new TextView(context);
        speedView.setTextColor(COLOR_SECONDARY);
        speedView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        speedView.setSingleLine(true);
        LinearLayout.LayoutParams speedParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        speedParams.setMarginStart(dp(8));
        progressRow.addView(speedView, speedParams);

        row.addView(progressRow, prParams);

        // Set initial state based on availability
        final String regionId = region.id;
        RowViews rv = new RowViews(regionId, row, nameView, sizeView, statusView,
                actionBtn, progressBar, speedView, progressRow);

        setRowState(rv, region);
        return rv;
    }

    private void setRowState(RowViews rv, DownloadCatalog.Region region) {
        DownloadDomain.Availability avail = domain.getAvailability(region);
        GradientDrawable btnBg = (GradientDrawable) rv.actionBtn.getBackground();

        rv.statusView.setVisibility(View.GONE);
        rv.progressRow.setVisibility(View.GONE);

        switch (avail) {
            case NOT_DOWNLOADED:
                rv.actionBtn.setText("Download");
                btnBg.setColor(COLOR_BTN_DL);
                rv.actionBtn.setTextColor(Color.WHITE);
                rv.actionBtn.setOnClickListener(v -> {
                    domain.enqueue(rv.regionId);
                });
                break;

            case UPDATE_AVAILABLE:
                rv.actionBtn.setText("Update");
                btnBg.setColor(COLOR_BTN_DL);
                rv.actionBtn.setTextColor(Color.WHITE);
                rv.actionBtn.setOnClickListener(v -> {
                    domain.enqueue(rv.regionId);
                });
                break;

            case CURRENT:
                rv.actionBtn.setText("Delete");
                btnBg.setColor(COLOR_BTN_DELETE);
                rv.actionBtn.setTextColor(Color.WHITE);
                rv.actionBtn.setOnClickListener(v -> {
                    rv.actionBtn.setEnabled(false);
                    rv.actionBtn.setText("...");
                    domain.deleteRegion(rv.regionId, () -> ui.post(() -> {
                        rv.actionBtn.setEnabled(true);
                        setRowState(rv, region);
                        if (onMapReload != null) onMapReload.run();
                    }));
                });
                break;
        }
    }

    private void applyCurrentState() {
        // Check which regions are currently queued/active
        // We can't get the full state synchronously, so we just set initial
        // availability states. The listener callback will update queued/active.
    }

    // -------------------------------------------------------------------------
    // DownloadDomain.Listener — called from worker thread
    // -------------------------------------------------------------------------

    @Override
    public void onStateChanged(DownloadDomain.State state) {
        ui.post(() -> updateFromState(state));
    }

    private void updateFromState(DownloadDomain.State state) {
        if (dialog == null || !dialog.isShowing()) return;

        Set<String> inQueue = new HashSet<>();

        for (DownloadDomain.RegionDownload rd : state.queue) {
            inQueue.add(rd.regionId);
            RowViews rv = rows.get(rd.regionId);
            if (rv == null) continue;

            GradientDrawable btnBg = (GradientDrawable) rv.actionBtn.getBackground();

            if (rd.status == DownloadDomain.RegionStatus.ACTIVE) {
                // Active: show progress
                rv.statusView.setVisibility(View.GONE);
                rv.progressRow.setVisibility(View.VISIBLE);

                if (rd.bytesTotal > 0) {
                    int pct = (int) (rd.bytesDownloaded * 100 / rd.bytesTotal);
                    rv.progressBar.setProgress((int) (rd.bytesDownloaded * 1000 / rd.bytesTotal));
                    rv.progressBar.setIndeterminate(false);
                    rv.sizeView.setText(pct + "%");
                } else {
                    rv.progressBar.setIndeterminate(true);
                    rv.sizeView.setText("...");
                }
                rv.speedView.setText(formatSpeed(state.speedBytesPerSec));

                rv.actionBtn.setText("Cancel");
                btnBg.setColor(COLOR_BTN_CANCEL);
                rv.actionBtn.setTextColor(Color.WHITE);
                rv.actionBtn.setOnClickListener(v -> domain.cancel(rd.regionId));
            } else {
                // Queued
                rv.statusView.setText("Queued");
                rv.statusView.setVisibility(View.VISIBLE);
                rv.progressRow.setVisibility(View.GONE);
                rv.sizeView.setText(formatBytes(rd.totalCatalogBytes));

                rv.actionBtn.setText("Cancel");
                btnBg.setColor(COLOR_BTN_CANCEL);
                rv.actionBtn.setTextColor(Color.WHITE);
                rv.actionBtn.setOnClickListener(v -> domain.cancel(rd.regionId));
            }
        }

        // Update rows that are no longer in queue (completed or cancelled)
        DownloadCatalog.Catalog catalog = domain.getCatalog();
        for (RowViews rv : rows.values()) {
            if (inQueue.contains(rv.regionId)) continue;

            // Find region and reset to availability-based state
            DownloadCatalog.Region region = null;
            for (DownloadCatalog.Region r : catalog.regions) {
                if (r.id.equals(rv.regionId)) { region = r; break; }
            }
            if (region != null) {
                rv.sizeView.setText(formatBytes(domain.regionTotalSize(region)));
                setRowState(rv, region);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Distance calculation
    // -------------------------------------------------------------------------

    /** Returns [lon, lat] centroid of the polygon. */
    private static double[] centroid(List<double[]> polygon) {
        double lonSum = 0, latSum = 0;
        int n = polygon.size();
        if (n == 0) return new double[]{0, 0};
        // Skip last point if it equals first (closed ring)
        if (n > 1 && polygon.get(0)[0] == polygon.get(n - 1)[0]
                && polygon.get(0)[1] == polygon.get(n - 1)[1]) {
            n--;
        }
        for (int i = 0; i < n; i++) {
            lonSum += polygon.get(i)[0];
            latSum += polygon.get(i)[1];
        }
        return new double[]{lonSum / n, latSum / n};
    }

    /** Great-circle distance in km using the Haversine formula. */
    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    private static String formatDistance(double km) {
        if (km < 1) return String.format("%.0f m away", km * 1000);
        if (km < 100) return String.format("%.0f km away", km);
        return String.format("%.0f km away", km);
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "";
        if (bytes >= 1_000_000_000L) return String.format("%.1f GB", bytes / 1_000_000_000.0);
        if (bytes >= 1_000_000L) return String.format("%.0f MB", bytes / 1_000_000.0);
        return String.format("%.0f KB", bytes / 1_000.0);
    }

    private static String formatSpeed(long bps) {
        if (bps <= 0) return "";
        if (bps >= 1_000_000) return String.format("%.1f MB/s", bps / 1_000_000.0);
        if (bps >= 1_000) return String.format("%.0f KB/s", bps / 1_000.0);
        return bps + " B/s";
    }

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics());
    }
}
