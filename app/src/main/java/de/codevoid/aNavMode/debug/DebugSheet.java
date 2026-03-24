package de.codevoid.aNavMode.debug;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import de.codevoid.aNavMode.R;

public class DebugSheet {

    public interface Callbacks {
        void onDownloadMap();
        void onUpdateRouteData();
        void onClearWaypoints();
        void onTogglePolygons(boolean show);
    }

    private final Context context;

    public DebugSheet(Context context, View panelView, Callbacks callbacks, boolean polygonsOn) {
        this.context = context;

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

        Button btnUpdate = panelView.findViewById(R.id.btnCheckUpdate);
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
