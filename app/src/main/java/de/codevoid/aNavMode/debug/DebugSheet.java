package de.codevoid.aNavMode.debug;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import de.codevoid.aNavMode.R;

public class DebugSheet {

    public interface Callbacks {
        void onDownloadMap();
        void onUpdateRouteData();
        void onClearWaypoints();
    }

    private final BottomSheetDialog dialog;

    public DebugSheet(Context context, Callbacks callbacks) {
        dialog = new BottomSheetDialog(context);
        View view = LayoutInflater.from(context).inflate(R.layout.sheet_debug, null);

        view.findViewById(R.id.btnDownloadMap).setOnClickListener(v -> {
            dialog.dismiss();
            callbacks.onDownloadMap();
        });
        view.findViewById(R.id.btnUpdateRouteData).setOnClickListener(v -> {
            dialog.dismiss();
            callbacks.onUpdateRouteData();
        });
        view.findViewById(R.id.btnClearWaypoints).setOnClickListener(v -> {
            dialog.dismiss();
            callbacks.onClearWaypoints();
        });

        Button btnUpdate = view.findViewById(R.id.btnCheckUpdate);
        TextView tvStatus = view.findViewById(R.id.tvUpdateStatus);

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
                btnUpdate.setOnClickListener(dv -> startDownload(context, btnUpdate, tvStatus, release));
            });
        });

        dialog.setContentView(view);
    }

    private void startDownload(Context context, Button btn, TextView tvStatus,
                               UpdateChecker.Release release) {
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
                dialog.dismiss();
            }

            @Override
            public void onError(String error) {
                btn.setEnabled(true);
                btn.setText("Retry Download");
                tvStatus.setText("Download failed: " + error);
            }
        });
    }

    public void show() {
        dialog.show();
    }
}
