package de.codevoid.aNavMode.ui;

import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import de.codevoid.aNavMode.download.DownloadCatalog;
import de.codevoid.aNavMode.download.DownloadDomain;
import de.codevoid.aNavMode.download.RegionDetector;

/**
 * Manages the stack of {@link DownloadCardView}s shown over the map (top-right).
 *
 * Implements both {@link RegionDetector.Listener} and {@link DownloadDomain.Listener}
 * so it reacts to crosshair position changes and download state changes.
 *
 * All public methods may be called from any thread; UI mutations are marshalled
 * to the main thread.
 */
public class DownloadCardStack
        implements RegionDetector.Listener, DownloadDomain.Listener {

    private final LinearLayout                     container;
    private final DownloadDomain                   domain;
    private final Handler                          ui = new Handler(Looper.getMainLooper());

    // Keyed by region ID. Insertion order preserved (LinkedHashMap).
    // Only accessed on main thread.
    private final Map<String, DownloadCardView>    cards         = new LinkedHashMap<>();
    // Region IDs where the crosshair currently is (main thread only)
    private final Set<String>                      underCrosshair = new HashSet<>();

    public DownloadCardStack(LinearLayout container, DownloadDomain domain) {
        this.container = container;
        this.domain    = domain;
    }

    // -------------------------------------------------------------------------
    // RegionDetector.Listener — called on main thread (Choreographer)
    // -------------------------------------------------------------------------

    @Override
    public void onEnter(DownloadCatalog.Region region) {
        underCrosshair.add(region.id);

        DownloadCardView existing = cards.get(region.id);
        if (existing != null) {
            existing.setHighlighted(true);
            return;
        }

        DownloadDomain.Availability avail = domain.getAvailability(region);
        if (avail == DownloadDomain.Availability.CURRENT) return;

        final String regionId = region.id;
        DownloadCardView card = new DownloadCardView(container.getContext(), region.name);
        card.setTotalBytes(domain.regionTotalSize(region));
        card.setState(avail == DownloadDomain.Availability.UPDATE_AVAILABLE
                ? DownloadCardView.CardState.UPDATE
                : DownloadCardView.CardState.DOWNLOAD);
        card.setHighlighted(true);
        card.setOnAction(() -> domain.enqueue(regionId));
        card.setOnCancel(() -> domain.cancel(regionId));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        cards.put(region.id, card);
        container.addView(card, 0); // newest card at top
        card.fadeIn();
    }

    @Override
    public void onLeave(DownloadCatalog.Region region) {
        underCrosshair.remove(region.id);

        DownloadCardView card = cards.get(region.id);
        if (card == null) return;

        card.setHighlighted(false);

        // Only remove cards that aren't queued or active
        DownloadCardView.CardState s = card.getCardState();
        if (s == DownloadCardView.CardState.DOWNLOAD
                || s == DownloadCardView.CardState.UPDATE) {
            removeCard(region.id);
        }
    }

    // -------------------------------------------------------------------------
    // DownloadDomain.Listener — called from worker thread
    // -------------------------------------------------------------------------

    @Override
    public void onStateChanged(DownloadDomain.State state) {
        ui.post(() -> updateFromState(state));
    }

    // -------------------------------------------------------------------------
    // Internal — main thread only
    // -------------------------------------------------------------------------

    private void updateFromState(DownloadDomain.State state) {
        Set<String> inQueue = new HashSet<>();

        for (DownloadDomain.RegionDownload rd : state.queue) {
            inQueue.add(rd.regionId);
            DownloadCardView card = cards.get(rd.regionId);

            if (card == null) {
                // Card was not added yet (e.g. enqueued programmatically, crosshair elsewhere)
                // We still show it so the user can see queued/active downloads
                final String capturedId = rd.regionId;
                card = new DownloadCardView(container.getContext(), rd.regionName);
                card.setTotalBytes(rd.totalCatalogBytes);
                card.setOnCancel(() -> domain.cancel(capturedId));
                card.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                cards.put(rd.regionId, card);
                container.addView(card, 0);
                card.fadeIn();
            }

            if (rd.status == DownloadDomain.RegionStatus.ACTIVE) {
                card.setState(DownloadCardView.CardState.ACTIVE);
                card.setProgress(rd.bytesDownloaded, rd.bytesTotal, state.speedBytesPerSec);
            } else {
                card.setTotalBytes(rd.totalCatalogBytes);
                card.setState(DownloadCardView.CardState.QUEUED);
            }
            card.setHighlighted(underCrosshair.contains(rd.regionId));
        }

        // Remove cards for completed downloads
        for (String id : new HashSet<>(cards.keySet())) {
            if (!inQueue.contains(id)) {
                DownloadCardView card = cards.get(id);
                // If the crosshair is still over the region, re-evaluate availability
                if (underCrosshair.contains(id)) {
                    // Re-check: download completed — is it current or still needs update?
                    // Trigger onEnter logic again by faking a leave + re-enter
                    cards.remove(id);
                    container.removeView(card);
                    // The next frame callback will re-detect and re-show if needed
                } else {
                    removeCard(id);
                }
            }
        }
    }

    private void removeCard(String regionId) {
        DownloadCardView card = cards.remove(regionId);
        if (card == null) return;
        card.fadeOut(() -> container.removeView(card));
    }
}
