package de.codevoid.aNavMode.debug;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.codevoid.aNavMode.R;
import de.codevoid.aNavMode.benchmark.BenchmarkConfig;
import de.codevoid.aNavMode.benchmark.BenchmarkResult;
import de.codevoid.aNavMode.benchmark.BenchmarkRunner;
import de.codevoid.aNavMode.map.MapManager;

public class DebugSheet {

    public interface Callbacks {
        void onDownloadMap();
        void onUpdateRouteData();
        void onClearWaypoints();
        void onTogglePolygons(boolean show);
    }

    private final Context context;

    public DebugSheet(Context context, View panelView, Callbacks callbacks,
                      boolean polygonsOn, BenchmarkRunner benchmarkRunner,
                      MapManager mapManager) {
        this.context = context;

        // Migration
        Button btnMigrate = panelView.findViewById(R.id.btnMigrateMaps);
        java.io.File legacyDir = mapManager.getLegacyExternalMapsDir();
        if (!legacyDir.isDirectory()) {
            btnMigrate.setEnabled(false);
            btnMigrate.setText("Migrate Maps (no legacy dir found)");
        }
        btnMigrate.setOnClickListener(v -> {
            btnMigrate.setEnabled(false);
            btnMigrate.setText("Migrating…");
            mapManager.migrateFromExternal(new MapManager.MigrationCallback() {
                @Override public void onProgress(String filename, int done, int total) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            btnMigrate.setText("Migrating " + done + "/" + total + ": " + filename));
                }
                @Override public void onComplete(int migrated, int skipped) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        btnMigrate.setText("Done: " + migrated + " copied, " + skipped + " skipped");
                        android.widget.Toast.makeText(context,
                                "Migration complete: " + migrated + " files copied",
                                android.widget.Toast.LENGTH_LONG).show();
                    });
                }
                @Override public void onError(String reason) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        btnMigrate.setEnabled(true);
                        btnMigrate.setText("Migration failed");
                        android.widget.Toast.makeText(context, reason,
                                android.widget.Toast.LENGTH_LONG).show();
                    });
                }
            });
        });

        android.widget.ToggleButton btnPolygons = panelView.findViewById(R.id.btnTogglePolygons);
        btnPolygons.setChecked(polygonsOn);
        btnPolygons.setOnCheckedChangeListener((btn, isChecked) ->
                callbacks.onTogglePolygons(isChecked));

        panelView.findViewById(R.id.btnDownloadMap).setOnClickListener(v ->
                callbacks.onDownloadMap());
        panelView.findViewById(R.id.btnUpdateRouteData).setOnClickListener(v ->
                callbacks.onUpdateRouteData());
        panelView.findViewById(R.id.btnClearWaypoints).setOnClickListener(v ->
                callbacks.onClearWaypoints());

        Button   btnUpdate = panelView.findViewById(R.id.btnCheckUpdate);
        TextView tvStatus = panelView.findViewById(R.id.tvUpdateStatus);

        String installedFile = "aNavMode-nightly-" + de.codevoid.aNavMode.BuildConfig.GIT_HASH + ".apk";
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText("installed: " + installedFile);

        btnUpdate.setOnClickListener(v -> {
            btnUpdate.setEnabled(false);
            btnUpdate.setText("Checking…");
            tvStatus.setText("installed: " + installedFile + "\nchecking…");

            UpdateChecker.check((release, error) -> {
                btnUpdate.setEnabled(true);
                btnUpdate.setText("Check for Update");

                if (error != null) {
                    tvStatus.setText("installed: " + installedFile + "\nError: " + error);
                    return;
                }

                String availFile = release.assetName != null ? release.assetName : release.name;
                if (!release.isNewerThanThisBuild()) {
                    tvStatus.setText("installed: " + installedFile + "\navailable: " + availFile + " (up to date)");
                    return;
                }

                tvStatus.setText("installed: " + installedFile + "\navailable: " + availFile);
                btnUpdate.setText("Download & Install");
                btnUpdate.setOnClickListener(dv -> startDownload(btnUpdate, tvStatus, release));
            });
        });

        // Benchmark
        Button   btnRound1  = panelView.findViewById(R.id.btnRunBenchmark1);
        Button   btnRound2  = panelView.findViewById(R.id.btnRunBenchmark2);
        Button   btnRound3  = panelView.findViewById(R.id.btnRunBenchmark3);
        Button   btnStop    = panelView.findViewById(R.id.btnStopBenchmark);
        TextView tvProgress = panelView.findViewById(R.id.tvBenchmarkProgress);

        View.OnClickListener startBenchmark = v -> {
            String roundLabel;
            List<BenchmarkConfig> matrix;
            if (v.getId() == R.id.btnRunBenchmark3) {
                roundLabel = "Round 3";
                matrix     = BenchmarkRunner.buildRound3Matrix();
            } else if (v.getId() == R.id.btnRunBenchmark2) {
                roundLabel = "Round 2";
                matrix     = BenchmarkRunner.buildRound2Matrix();
            } else {
                roundLabel = "Round 1";
                matrix     = BenchmarkRunner.buildRound1Matrix();
            }

            btnRound1.setEnabled(false);
            btnRound2.setEnabled(false);
            btnRound3.setEnabled(false);
            btnStop.setVisibility(View.VISIBLE);
            btnStop.setEnabled(true);
            tvProgress.setVisibility(View.VISIBLE);
            tvProgress.setText(roundLabel + ": preparing…");

            benchmarkRunner.start(matrix, new BenchmarkRunner.Listener() {
                @Override
                public void onProgress(int current, int total, BenchmarkConfig config) {
                    tvProgress.setText(roundLabel + " " + current + "/" + total
                            + "\n" + config.label());
                }

                @Override
                public void onComplete(List<BenchmarkResult> results) {
                    btnRound1.setEnabled(true);
                    btnRound2.setEnabled(true);
                    btnRound3.setEnabled(true);
                    btnStop.setVisibility(View.GONE);
                    tvProgress.setText(roundLabel + " done — " + results.size() + " runs");
                    showReport(results, benchmarkRunner, roundLabel);
                }
            });
        };

        btnRound1.setOnClickListener(startBenchmark);
        btnRound2.setOnClickListener(startBenchmark);
        btnRound3.setOnClickListener(startBenchmark);

        btnStop.setOnClickListener(v -> {
            benchmarkRunner.stop();
            btnStop.setEnabled(false);
            tvProgress.setText(tvProgress.getText() + "\n(stopping after current run…)");
        });
    }

    private void showReport(List<BenchmarkResult> results, BenchmarkRunner runner,
                            String roundLabel) {
        String report = BenchmarkRunner.generateReport(results,
                runner.getStartPosition(), roundLabel);

        TextView tv = new TextView(context);
        tv.setText(report);
        tv.setTextSize(11f);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(32, 16, 32, 16);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(tv);

        new AlertDialog.Builder(context)
                .setTitle("Benchmark Results")
                .setView(scroll)
                .setPositiveButton("Save to Downloads", (d, w) -> saveReport(report))
                .setNegativeButton("Dismiss", null)
                .show();
    }

    private void saveReport(String report) {
        new Thread(() -> {
            try {
                File dir  = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                String ts = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
                File   f  = new File(dir, "anavmode-bench-" + ts + ".txt");
                try (FileWriter w = new FileWriter(f)) { w.write(report); }
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(() -> android.widget.Toast.makeText(context,
                        "Saved: " + f.getName(), android.widget.Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(() -> android.widget.Toast.makeText(context,
                        "Save failed: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show());
            }
        }, "bench-save").start();
    }

    private void startDownload(Button btn, TextView tvStatus, UpdateChecker.Release release) {
        btn.setEnabled(false);
        btn.setText("Downloading…");
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText("Downloading 0%");

        UpdateChecker.download(context, release, new UpdateChecker.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                tvStatus.setText("Downloading " + percent + "%");
            }

            @Override
            public void onComplete(java.io.File apk) {
                tvStatus.setText("Download complete. Installing…");
                UpdateChecker.install(context, apk);
            }

            @Override
            public void onError(String error) {
                btn.setEnabled(true);
                btn.setText("Retry Download");
                tvStatus.setText("Download failed: " + error);
            }
        });
    }
}
