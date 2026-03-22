package de.codevoid.aNavMode.debug;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import de.codevoid.aNavMode.R;

public class DebugSheet {

    public interface Callbacks {
        void onDownloadMap();
        void onUpdateRouteData();
        void onTestRoute();
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
        view.findViewById(R.id.btnTestRoute).setOnClickListener(v -> {
            dialog.dismiss();
            callbacks.onTestRoute();
        });

        dialog.setContentView(view);
    }

    public void show() {
        dialog.show();
    }
}
