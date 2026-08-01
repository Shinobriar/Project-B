package com.rapha.billiebackup;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int PICK_SOURCE = 501;
    private static final int PICK_DESTINATION = 502;
    private final LinkedHashSet<String> sources = new LinkedHashSet<>();
    private SharedPreferences prefs;
    private String destination;
    private TextView sourcesText, destinationText, statusText, operationHint;
    private CheckBox moveCheck;
    private Button previewButton, backupButton;
    private boolean registered;

    private final BroadcastReceiver progress = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(BackupService.EXTRA_MESSAGE);
            int matched = intent.getIntExtra(BackupService.EXTRA_MATCHED, 0);
            int processed = intent.getIntExtra(BackupService.EXTRA_PROCESSED, 0);
            long bytes = intent.getLongExtra(BackupService.EXTRA_BYTES, 0);
            boolean done = intent.getBooleanExtra(BackupService.EXTRA_DONE, false);
            String text = message == null ? "Working…" : message;
            if (!done && matched > 0) text += "\n" + matched + " matched · " + processed + " processed · " + humanBytes(bytes);
            statusText.setText(text);
            if (done) setWorking(false);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        getWindow().setStatusBarColor(Color.rgb(11, 13, 16));
        getWindow().setNavigationBarColor(Color.rgb(11, 13, 16));
        prefs = getSharedPreferences("billie_backup", MODE_PRIVATE);
        sources.addAll(prefs.getStringSet("sources", Set.of()));
        destination = prefs.getString("destination", null);

        sourcesText = findViewById(R.id.sourcesText);
        destinationText = findViewById(R.id.destinationText);
        statusText = findViewById(R.id.statusText);
        operationHint = findViewById(R.id.operationHint);
        moveCheck = findViewById(R.id.moveCheck);
        previewButton = findViewById(R.id.previewButton);
        backupButton = findViewById(R.id.backupButton);

        findViewById(R.id.addSource).setOnClickListener(v -> pick(PICK_SOURCE));
        findViewById(R.id.chooseDestination).setOnClickListener(v -> pick(PICK_DESTINATION));
        findViewById(R.id.clearSources).setOnClickListener(v -> {
            sources.clear();
            save();
            refreshLabels();
        });
        previewButton.setOnClickListener(v -> startJob(true));
        backupButton.setOnClickListener(v -> startJob(false));
        moveCheck.setOnCheckedChangeListener((button, checked) -> {
            operationHint.setText(checked
                    ? "Move mode is on. Originals are deleted only after a successful copy."
                    : "Recommended: keep this off for the first backup.");
            operationHint.setTextColor(checked ? Color.rgb(255, 194, 92) : Color.rgb(164, 172, 184));
        });
        refreshLabels();
        String last = prefs.getString("last_status", null);
        if (last != null) statusText.setText("Last result\n" + last);
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(BackupService.ACTION_PROGRESS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(progress, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(progress, filter);
        registered = true;
    }

    @Override protected void onStop() {
        if (registered) unregisterReceiver(progress);
        registered = false;
        super.onStop();
    }

    private void pick(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(uri, flags); }
        catch (SecurityException ignored) {
            Toast.makeText(this, "Folder access may last only until the next restart.", Toast.LENGTH_LONG).show();
        }
        if (requestCode == PICK_SOURCE) sources.add(uri.toString());
        else if (requestCode == PICK_DESTINATION) destination = uri.toString();
        save();
        refreshLabels();
    }

    private void save() {
        prefs.edit().putStringSet("sources", new LinkedHashSet<>(sources))
                .putString("destination", destination).apply();
    }

    private void refreshLabels() {
        if (sources.isEmpty()) {
            sourcesText.setText("No source folders added yet.");
            sourcesText.setTextColor(Color.rgb(164, 172, 184));
        } else {
            StringBuilder text = new StringBuilder();
            int i = 1;
            for (String value : sources) {
                if (text.length() > 0) text.append('\n');
                text.append(i++).append(". ").append(folderName(Uri.parse(value)));
            }
            sourcesText.setText(text);
            sourcesText.setTextColor(Color.rgb(244, 246, 248));
        }
        if (destination == null) {
            destinationText.setText("No destination selected.");
            destinationText.setTextColor(Color.rgb(164, 172, 184));
        } else {
            destinationText.setText(folderName(Uri.parse(destination)) + "  /  Billie Backup");
            destinationText.setTextColor(Color.rgb(244, 246, 248));
        }
    }

    private String folderName(Uri tree) {
        try {
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cursor = getContentResolver().query(document,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) return cursor.getString(0);
            }
        } catch (Exception ignored) { }
        return "Selected folder";
    }

    private void startJob(boolean preview) {
        if (sources.isEmpty()) {
            Toast.makeText(this, "Add at least one source folder first.", Toast.LENGTH_LONG).show();
            return;
        }
        if (destination == null) {
            Toast.makeText(this, "Choose a backup destination first.", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 700);
        }
        Intent work = new Intent(this, BackupService.class)
                .putStringArrayListExtra("sources", new ArrayList<>(sources))
                .putExtra("destination", destination).putExtra("preview", preview)
                .putExtra("move", moveCheck.isChecked());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(work); else startService(work);
        statusText.setText(preview ? "Starting preview scan…" : "Starting backup…");
        setWorking(true);
    }

    private void setWorking(boolean working) {
        previewButton.setEnabled(!working);
        backupButton.setEnabled(!working);
        previewButton.setAlpha(working ? .45f : 1f);
        backupButton.setAlpha(working ? .45f : 1f);
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do { value /= 1024d; unit++; } while (value >= 1024 && unit < units.length - 1);
        String format = value >= 100 ? "%.0f %s" : value >= 10 ? "%.1f %s" : "%.2f %s";
        return String.format(Locale.ROOT, format, value, units[unit]);
    }
}
