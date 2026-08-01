package com.rapha.billiebackup;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

final class DocOps {
    static final int COPIED = 1, SKIPPED = 2, FAILED = 3;
    record Entry(Uri uri, String id, String name, String mime, long size, boolean directory) { }
    private static final String[] COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
    };
    private final ContentResolver resolver;
    private final Map<String, Uri> folders = new HashMap<>();

    DocOps(Context context) { resolver = context.getContentResolver(); }

    Uri root(Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    String id(Uri uri) {
        try { return DocumentsContract.getDocumentId(uri); }
        catch (Exception first) {
            try { return DocumentsContract.getTreeDocumentId(uri); }
            catch (Exception second) { return null; }
        }
    }

    String name(Uri document) {
        try (Cursor c = resolver.query(document,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        String value = id(document);
        if (value == null) return "Selected folder";
        int at = Math.max(value.lastIndexOf('/'), value.lastIndexOf(':'));
        return at >= 0 ? value.substring(at + 1) : value;
    }

    List<Entry> children(Uri tree, String parentId) {
        ArrayList<Entry> entries = new ArrayList<>();
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor c = resolver.query(uri, COLUMNS, null, null, null)) {
            if (c == null) return entries;
            int ci = c.getColumnIndexOrThrow(COLUMNS[0]);
            int cn = c.getColumnIndexOrThrow(COLUMNS[1]);
            int cm = c.getColumnIndexOrThrow(COLUMNS[2]);
            int cs = c.getColumnIndex(COLUMNS[3]);
            while (c.moveToNext()) {
                String childId = c.getString(ci);
                String childName = c.getString(cn);
                String mime = c.getString(cm);
                long size = cs >= 0 && !c.isNull(cs) ? Math.max(0, c.getLong(cs)) : 0;
                Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, childId);
                entries.add(new Entry(child, childId, childName == null ? "Unnamed file" : childName,
                        mime, size, DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)));
            }
        } catch (Exception ignored) { }
        return entries;
    }

    Uri findFolder(Uri tree, Uri parent, String folderName) {
        Entry found = find(tree, parent, folderName);
        return found != null && found.directory ? found.uri : null;
    }

    Uri folder(Uri tree, Uri parent, String folderName) {
        if (parent == null) return null;
        String safe = safe(folderName);
        String key = parent + "\n" + safe;
        if (folders.containsKey(key)) return folders.get(key);
        Uri found = findFolder(tree, parent, safe);
        if (found == null) {
            try {
                found = DocumentsContract.createDocument(resolver, parent,
                        DocumentsContract.Document.MIME_TYPE_DIR, safe);
            } catch (Exception ignored) { }
        }
        if (found != null) folders.put(key, found);
        return found;
    }

    Uri folderPath(Uri tree, Uri start, String... segments) {
        Uri current = start;
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) continue;
            for (String part : segment.split("/")) {
                if (part.isBlank()) continue;
                current = folder(tree, current, part);
                if (current == null) return null;
            }
        }
        return current;
    }

    int copy(Uri source, String originalName, String mime, long sourceSize,
             Uri tree, Uri parent, BooleanSupplier cancelled) {
        Entry existing = find(tree, parent, originalName);
        if (existing != null && !existing.directory && sourceSize > 0 && existing.size == sourceSize) return SKIPPED;
        String targetName = existing == null ? originalName : availableName(tree, parent, originalName);
        if (targetName == null) return FAILED;
        Uri target = null;
        try {
            target = DocumentsContract.createDocument(resolver, parent,
                    mime == null || mime.isBlank() ? "application/octet-stream" : mime, targetName);
            if (target == null) return FAILED;
            try (InputStream in = resolver.openInputStream(source);
                 OutputStream out = resolver.openOutputStream(target, "w")) {
                if (in == null || out == null) throw new IllegalStateException("Stream unavailable");
                byte[] buffer = new byte[131072];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (cancelled.getAsBoolean()) throw new InterruptedException();
                    out.write(buffer, 0, count);
                }
            }
            return COPIED;
        } catch (Exception error) {
            if (target != null) try { DocumentsContract.deleteDocument(resolver, target); } catch (Exception ignored) { }
            return FAILED;
        }
    }

    boolean delete(Uri uri) {
        try { return DocumentsContract.deleteDocument(resolver, uri); }
        catch (Exception ignored) { return false; }
    }

    private Entry find(Uri tree, Uri parent, String wanted) {
        String parentId = id(parent);
        if (parentId == null) return null;
        for (Entry entry : children(tree, parentId)) if (wanted.equals(entry.name)) return entry;
        return null;
    }

    private String availableName(Uri tree, Uri parent, String original) {
        int dot = original.lastIndexOf('.');
        String base = dot > 0 ? original.substring(0, dot) : original;
        String ext = dot > 0 ? original.substring(dot) : "";
        for (int i = 2; i < 10000; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (find(tree, parent, candidate) == null) return candidate;
        }
        return null;
    }

    static String category(String mime, String name) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (m.startsWith("image/") || n.matches(".*\\.(jpg|jpeg|png|gif|webp|heic|avif)$")) return "Images";
        if (m.startsWith("video/") || n.matches(".*\\.(mp4|mkv|mov|webm|avi|m4v|3gp)$")) return "Videos";
        if (m.startsWith("audio/") || n.matches(".*\\.(mp3|m4a|aac|flac|wav|ogg|opus)$")) return "Audio";
        if (m.startsWith("text/") || m.contains("pdf") || m.contains("document")
                || n.matches(".*\\.(pdf|txt|doc|docx|xls|xlsx|ppt|pptx|zip|rar|7z)$")) return "Documents";
        return "Other";
    }

    static String safe(String value) {
        String out = value == null ? "Folder" : value.trim();
        out = out.replace('/', '／').replace('\\', '⧵');
        return out.isEmpty() ? "Folder" : out;
    }
}
