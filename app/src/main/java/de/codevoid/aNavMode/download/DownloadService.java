package de.codevoid.aNavMode.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import de.codevoid.aNavMode.R;

/**
 * Foreground service that anchors the download process.
 *
 * All download logic lives in {@link DownloadDomain}. This service's sole
 * purpose is to hold a foreground notification so Android does not kill the
 * process while downloads are in progress.
 *
 * android:stopWithTask="true" means the service is stopped automatically
 * when the user swipes the app from the recents list, which terminates
 * in-progress downloads (partial files are left for resumption).
 */
public class DownloadService extends Service implements DownloadDomain.Listener {

    private static final String CHANNEL_ID  = "anavmode_download";
    private static final int    NOTIF_ID    = 2;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("Preparing download…", -1, -1));

        DownloadDomain domain = DownloadDomain.getInstance();
        if (domain != null) {
            domain.addListener(this);
        }

        // START_NOT_STICKY: don't restart after being killed
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        DownloadDomain domain = DownloadDomain.getInstance();
        if (domain != null) {
            domain.removeListener(this);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // -------------------------------------------------------------------------
    // DownloadDomain.Listener — called from worker thread
    // -------------------------------------------------------------------------

    @Override
    public void onStateChanged(DownloadDomain.State state) {
        if (state.queue.isEmpty()) {
            stopSelf();
            return;
        }

        DownloadDomain.RegionDownload active = null;
        for (DownloadDomain.RegionDownload d : state.queue) {
            if (d.status == DownloadDomain.RegionStatus.ACTIVE) { active = d; break; }
        }

        String title;
        long   done  = -1;
        long   total = -1;

        if (active != null) {
            title = active.regionName;
            done  = active.bytesDownloaded;
            total = active.bytesTotal;
        } else {
            title = state.queue.get(0).regionName + " (queued)";
        }

        // Update the foreground notification via startForeground — exempt from
        // POST_NOTIFICATIONS permission (foreground service notifications are always allowed).
        startForeground(NOTIF_ID, buildNotification(title, done, total));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Notification buildNotification(String title, long done, long total) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_crosshair_center)
                .setContentTitle(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true);

        if (total > 0) {
            int pct = (int) (done * 100 / total);
            b.setContentText(pct + "%")
             .setProgress((int) total, (int) done, false);
        } else {
            b.setProgress(0, 0, true); // indeterminate
        }
        return b.build();
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Map Downloads", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Downloading map and routing data");
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }
}
