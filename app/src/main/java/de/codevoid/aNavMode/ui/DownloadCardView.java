package de.codevoid.aNavMode.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * A single download card shown over the map.
 *
 * States:
 *   DOWNLOAD — tap button to start download
 *   UPDATE   — tap button to update existing region
 *   QUEUED   — waiting in queue; no interaction
 *   ACTIVE   — download in progress; shows progress bar and speed
 *
 * Cards fade in when the crosshair enters the region and fade out when
 * it leaves (unless the region is queued or active, in which case the
 * card stays until the download completes).
 */
public class DownloadCardView extends LinearLayout {

    public enum CardState { DOWNLOAD, UPDATE, QUEUED, ACTIVE }

    private static final int   CARD_WIDTH_DP   = 220;
    private static final int   CORNER_DP       = 10;
    private static final int   PAD_DP          = 10;
    private static final int   FADE_MS         = 250;

    private static final int COLOR_BG          = Color.argb(210,  18,  18,  38);
    private static final int COLOR_BG_HIGHLIGHT = Color.argb(230,  20,  60, 110);
    private static final int COLOR_TEXT         = Color.WHITE;
    private static final int COLOR_SECONDARY    = Color.argb(180, 220, 220, 220);
    private static final int COLOR_BTN          = Color.argb(255,  30, 140, 255);

    private final GradientDrawable background;
    private final TextView         nameView;
    private final TextView         rightLabel;  // speed or "Queued"
    private final Button           actionBtn;   // Download / Update
    private final ProgressBar      progressBar;

    private CardState state    = CardState.DOWNLOAD;
    private Runnable  onAction = null;

    public DownloadCardView(Context context, String regionName) {
        super(context);
        setOrientation(VERTICAL);
        int pad = dp(PAD_DP);
        setPadding(pad, pad, pad, pad);
        setAlpha(0f);

        background = new GradientDrawable();
        background.setColor(COLOR_BG);
        background.setCornerRadius(dp(CORNER_DP));
        setBackground(background);
        setElevation(dp(4));

        getLayoutParams(); // ensure layout params exist before setLayoutParams below

        // ---- Top row: name + right content ----
        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        nameView = new TextView(context);
        nameView.setTextColor(COLOR_TEXT);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        nameView.setSingleLine(true);
        nameView.setText(truncate(regionName));
        LinearLayout.LayoutParams nameParams =
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        topRow.addView(nameView, nameParams);

        rightLabel = new TextView(context);
        rightLabel.setTextColor(COLOR_SECONDARY);
        rightLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        rightLabel.setSingleLine(true);
        rightLabel.setVisibility(GONE);
        topRow.addView(rightLabel, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        actionBtn = new Button(context);
        actionBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        actionBtn.setTextColor(Color.WHITE);
        actionBtn.setAllCaps(false);
        actionBtn.setPadding(dp(8), dp(2), dp(8), dp(2));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(COLOR_BTN);
        btnBg.setCornerRadius(dp(6));
        actionBtn.setBackground(btnBg);
        actionBtn.setOnClickListener(v -> { if (onAction != null) onAction.run(); });
        topRow.addView(actionBtn, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        addView(topRow, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // ---- Progress bar ----
        progressBar = new ProgressBar(context, null,
                android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setVisibility(GONE);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(6));
        pbParams.topMargin = dp(6);
        addView(progressBar, pbParams);

        setState(CardState.DOWNLOAD);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public CardState getCardState() { return state; }

    public void setState(CardState s) {
        state = s;
        switch (s) {
            case DOWNLOAD:
                actionBtn.setText("Download");
                actionBtn.setVisibility(VISIBLE);
                rightLabel.setVisibility(GONE);
                progressBar.setVisibility(GONE);
                break;
            case UPDATE:
                actionBtn.setText("Update");
                actionBtn.setVisibility(VISIBLE);
                rightLabel.setVisibility(GONE);
                progressBar.setVisibility(GONE);
                break;
            case QUEUED:
                actionBtn.setVisibility(GONE);
                rightLabel.setText("Queued");
                rightLabel.setVisibility(VISIBLE);
                progressBar.setVisibility(GONE);
                break;
            case ACTIVE:
                actionBtn.setVisibility(GONE);
                rightLabel.setVisibility(VISIBLE);
                progressBar.setVisibility(VISIBLE);
                break;
        }
    }

    public void setProgress(long done, long total, long speedBytesPerSec) {
        if (total > 0) {
            progressBar.setProgress((int) (done * 1000 / total));
            progressBar.setIndeterminate(false);
        } else {
            progressBar.setIndeterminate(true);
        }
        rightLabel.setText(formatSpeed(speedBytesPerSec));
    }

    public void setHighlighted(boolean on) {
        background.setColor(on ? COLOR_BG_HIGHLIGHT : COLOR_BG);
    }

    public void setOnAction(Runnable r) { onAction = r; }

    // -------------------------------------------------------------------------
    // Fade animations
    // -------------------------------------------------------------------------

    public void fadeIn() {
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(FADE_MS).start();
    }

    public void fadeOut(Runnable onDone) {
        animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            setVisibility(GONE);
            if (onDone != null) onDone.run();
        }).start();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String truncate(String name) {
        return name.length() <= 16 ? name : name.substring(0, 13) + "…";
    }

    private static String formatSpeed(long bps) {
        if (bps >= 1_000_000) return String.format("%.1f MB/s", bps / 1_000_000.0);
        if (bps >= 1_000)     return String.format("%.0f KB/s", bps / 1_000.0);
        return bps + " B/s";
    }

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }
}
