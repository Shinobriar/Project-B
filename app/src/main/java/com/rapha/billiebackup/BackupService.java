package com.rapha.billiebackup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BackupService extends Service {
    static final String ACTION_PROGRESS = "com.rapha.billiebackup.PROGRESS";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_MATCHED = "matched";
    static final String EXTRA_PROCESSED = "processed";
    static final String EXTRA_BYTES = "bytes";
    static final String EXTRA_DONE = "done";

    private static final String CHANNEL_ID = "billie_backup_work";
    private static final int NOTIFICATION_ID = 9047;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean cancelled;
    private volatile boolean running;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Backup progress", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows progress while Billie Backup copies or moves files.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || running) return START_NOT_STICKY;
        ArrayList<String> sourceStrings = intent.getStringArrayListExtra("sources");
        String destinationString = intent.getStringExtra("destination");
        boolean preview = intent.getBooleanExtra("preview", false);
        boolean move = intent.getBooleanExtra("move", false);
        if (sourceStrings == null || sourceStrings.isEmpty() || destinationString == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        running = true;
        cancelled = false;
        startForeground(NOTIFICATION_ID, notification(preview ? "Preparing preview…" : "Preparing backup…"));
        executor.execute(() -> {
            List<Uri> sources = new ArrayList<>();
            for (String source : sourceStrings) sources.add(Uri.parse(source));
            BackupEngine engine = new BackupEngine(this, new BackupEngine.Callback() {
                @Override public void onProgress(String message, int matched, int processed, long bytes) {
                    NotificationManager manager = getSystemService(NotificationManager.class);
                    if (manager != null) manager.notify(NOTIFICATION_ID, notification(message));
                    Intent update = new Intent(ACTION_PROGRESS).setPackage(getPackageName())
                            .putExtra(EXTRA_MESSAGE, message).putExtra(EXTRA_MATCHED, matched)
                            .putExtra(EXTRA_PROCESSED, processed).putExtra(EXTRA_BYTES, bytes)
                            .putExtra(EXTRA_DONE, false);
                    sendBroadcast(update);
                }
                @Override public boolean isCancelled() {
                    return cancelled || Thread.currentThread().isInterrupted();
                }
            }, preview, move);

            BackupEngine.Result result = engine.execute(sources, Uri.parse(destinationString));
            String summary;
            if (result.cancelled) summary = "Stopped. " + result.processed() + " files processed.";
            else if (preview) summary = result.matched + " matching files found · " + MainActivity.humanBytes(result.bytes);
            else {
                int successful = move ? result.moved + result.copied : result.copied;
                summary = successful + (move ? " files moved" : " files copied");
                if (result.skipped > 0) summary += " · " + result.skipped + " duplicates skipped";
                if (result.failed > 0) summary += " · " + result.failed + " failed";
            }
            getSharedPreferences("billie_backup", MODE_PRIVATE).edit().putString("last_status", summary).apply();
            sendBroadcast(new Intent(ACTION_PROGRESS).setPackage(getPackageName())
                    .putExtra(EXTRA_MESSAGE, summary).putExtra(EXTRA_MATCHED, result.matched)
                    .putExtra(EXTRA_PROCESSED, result.processed()).putExtra(EXTRA_BYTES, result.bytes)
                    .putExtra(EXTRA_DONE, true));
            stopForeground(STOP_FOREGROUND_REMOVE);
            running = false;
            stopSelf(startId);
        });
        return START_NOT_STICKY;
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Billie Backup")
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS).build();
    }

    @Override public void onDestroy() {
        cancelled = true;
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
