package com.rapha.billiebackup;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.List;
import java.util.Locale;

final class BackupEngine {
    interface Callback {
        void onProgress(String message, int matched, int processed, long bytes);
        boolean isCancelled();
    }

    static final class Result {
        int scanned, matched, copied, moved, skipped, failed;
        long bytes;
        boolean cancelled;
        int processed() { return copied + moved + skipped + failed; }
    }

    private final DocOps docs;
    private final Callback callback;
    private final boolean preview;
    private final boolean move;

    BackupEngine(Context context, Callback callback, boolean preview, boolean move) {
        docs = new DocOps(context);
        this.callback = callback;
        this.preview = preview;
        this.move = move;
    }

    Result execute(List<Uri> sources, Uri destination) {
        Result result = new Result();
        Uri destinationRoot = docs.root(destination);
        Uri output = preview ? docs.findFolder(destination, destinationRoot, "Billie Backup")
                : docs.folder(destination, destinationRoot, "Billie Backup");
        if (!preview && output == null) {
            result.failed++;
            callback.onProgress("Couldn't create the Billie Backup folder.", 0, 0, 0);
            return result;
        }
        String skipId = output == null ? null : docs.id(output);
        for (Uri source : sources) {
            if (cancelled(result)) break;
            String sourceName = docs.name(docs.root(source));
            callback.onProgress("Scanning " + sourceName + "…", result.matched, result.processed(), result.bytes);
            scan(source, DocumentsContract.getTreeDocumentId(source), sourceName, "", false,
                    skipId, destination, output, result);
        }
        return result;
    }

    private void scan(Uri sourceTree, String parentId, String sourceName, String relative,
                      boolean inherited, String skipId, Uri destinationTree, Uri output, Result result) {
        if (cancelled(result)) return;
        for (DocOps.Entry entry : docs.children(sourceTree, parentId)) {
            if (cancelled(result)) return;
            if (skipId != null && (entry.id().equals(skipId) || entry.id().startsWith(skipId + "/"))) continue;
            String childPath = relative.isEmpty() ? entry.name() : relative + "/" + entry.name();
            if (entry.directory()) {
                boolean folderMatch = inherited || matches(entry.name()) || matches(childPath);
                scan(sourceTree, entry.id(), sourceName, childPath, folderMatch,
                        skipId, destinationTree, output, result);
                continue;
            }

            result.scanned++;
            if (!(inherited || matches(entry.name()) || matches(childPath))) continue;
            result.matched++;
            result.bytes += entry.size();
            callback.onProgress((preview ? "Found " : "Backing up ") + entry.name(),
                    result.matched, result.processed(), result.bytes);
            if (preview) continue;

            int slash = childPath.lastIndexOf('/');
            String parentPath = slash < 0 ? "" : childPath.substring(0, slash);
            Uri target = docs.folderPath(destinationTree, output,
                    DocOps.category(entry.mime(), entry.name()), sourceName, parentPath);
            if (target == null) {
                result.failed++;
                continue;
            }
            int outcome = docs.copy(entry.uri(), entry.name(), entry.mime(), entry.size(),
                    destinationTree, target, callback::isCancelled);
            if (outcome == DocOps.SKIPPED) result.skipped++;
            else if (outcome == DocOps.FAILED) result.failed++;
            else if (move && docs.delete(entry.uri())) result.moved++;
            else result.copied++;
            callback.onProgress(entry.name(), result.matched, result.processed(), result.bytes);
        }
    }

    private boolean cancelled(Result result) {
        if (!callback.isCancelled()) return false;
        result.cancelled = true;
        return true;
    }

    static boolean matches(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("billie") || lower.contains("eilish");
    }
}
