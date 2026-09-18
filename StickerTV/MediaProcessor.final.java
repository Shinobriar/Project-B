package com.raphael.stickertv;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Movie;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

public final class MediaProcessor {
    public static final long STATIC_LIMIT = 100L * 1024L;
    public static final long ANIM_LIMIT = 500L * 1024L;
    public static final long SAFE_DURATION_MS = 10_000L;

    private final Context context;
    private final StickerDb db;

    public MediaProcessor(Context context, StickerDb db) {
        this.context = context.getApplicationContext(); this.db = db;
    }

    public Models.Sticker importUri(Uri uri) throws Exception {
        ContentResolver cr = context.getContentResolver();
        String mime = cr.getType(uri);
        String displayName = displayName(cr, uri);
        String ext = extension(displayName, mime);
        String id = UUID.randomUUID().toString();
        File originals = new File(context.getFilesDir(), "originals"); originals.mkdirs();
        File src = new File(originals, id + ext);
        try (InputStream in = cr.openInputStream(uri); FileOutputStream out = new FileOutputStream(src)) {
            if (in == null) throw new IOException("Could not open selected file");
            copy(in, out);
        }
        String hash = sha256(src);
        if (db.hasHash(hash)) { src.delete(); return null; }

        boolean animated = isVideoMime(mime) || isGif(src) || isAnimatedWebp(src);
        File exports = new File(context.getFilesDir(), "exports"); exports.mkdirs();
        File safe = new File(exports, id + ".webp");
        try {
            long duration = animated ? processAnimated(src, safe, false) : 0;
            if (!animated) processStatic(src, safe);

            Models.Sticker s = new Models.Sticker();
            s.id = id; s.name = stripExt(displayName == null ? "Sticker" : displayName);
            s.sourcePath = src.getAbsolutePath(); s.safePath = safe.getAbsolutePath(); s.riskyPath = null;
            s.sha256 = hash; s.animated = animated; s.durationMs = duration; s.createdAt = System.currentTimeMillis();
            db.insertSticker(s);
            return s;
        } catch (Exception e) {
            src.delete(); safe.delete();
            throw e;
        }
    }

    public String ensureRiskyExport(Models.Sticker s) throws Exception {
        if (!s.animated) return s.safePath;
        if (s.riskyPath != null && new File(s.riskyPath).exists()) return s.riskyPath;
        File out = new File(new File(context.getFilesDir(), "exports"), s.id + "_full.webp");
        processAnimated(new File(s.sourcePath), out, true);
        db.setRiskyPath(s.id, out.getAbsolutePath());
        s.riskyPath = out.getAbsolutePath();
        return s.riskyPath;
    }

    public File ensureTray(Models.Pack pack, boolean risky) throws Exception {
        if (pack.trayPath != null) {
            File f = new File(pack.trayPath); if (f.exists() && f.length() <= 50 * 1024) return f;
        }
        if (pack.stickers.isEmpty()) throw new IOException("Pack has no stickers");
        Models.Sticker first = pack.stickers.get(0);
        Bitmap b = BitmapFactory.decodeFile(first.exportPath(risky));
        if (b == null) b = BitmapFactory.decodeFile(first.safePath);
        if (b == null) b = BitmapFactory.decodeFile(first.sourcePath);
        if (b == null) throw new IOException("Could not make pack icon");
        Bitmap icon = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(icon); c.drawColor(Color.TRANSPARENT); drawFit(c, b, 96, 96);
        b.recycle();
        File dir = new File(context.getFilesDir(), "trays"); dir.mkdirs();
        File out = new File(dir, pack.id + ".png");
        int q = 100;
        do {
            try (FileOutputStream fos = new FileOutputStream(out)) { icon.compress(Bitmap.CompressFormat.PNG, q, fos); }
            q -= 10;
        } while (out.length() > 50 * 1024 && q > 0);
        icon.recycle(); db.setTrayPath(pack.id, out.getAbsolutePath()); pack.trayPath = out.getAbsolutePath();
        return out;
    }

    public void processStatic(File source, File output) throws Exception {
        Bitmap src = decodeOriented(source);
        if (src == null) throw new IOException("Unsupported image");
        Bitmap canvas = fit512(src);
        output.getParentFile().mkdirs();
        byte[] best = null;
        Bitmap.CompressFormat fmt = Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
        for (int q = 95; q >= 5; q -= 5) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            canvas.compress(fmt, q, bos); best = bos.toByteArray();
            if (best.length <= STATIC_LIMIT) break;
        }
        if (best != null && best.length > STATIC_LIMIT) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            canvas.compress(fmt, 1, bos); best = bos.toByteArray();
        }
        int[] detailSteps = {448, 384, 320, 256, 192, 128, 96};
        for (int detail : detailSteps) {
            if (best != null && best.length <= STATIC_LIMIT) break;
            Bitmap reduced = reduceDetail(canvas, detail);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            reduced.compress(fmt, 1, bos); best = bos.toByteArray();
            reduced.recycle();
        }
        canvas.recycle();
        if (best == null || best.length > STATIC_LIMIT) throw new IOException("Could not fit static sticker under 100 KB");
        try (FileOutputStream fos = new FileOutputStream(output)) { fos.write(best); }
    }

    public long processAnimated(File source, File output, boolean risky) throws Exception {
        FrameSource fs = openFrameSource(source);
        try {
            long fullDuration = Math.max(100, fs.durationMs());
            long exportDuration = risky ? fullDuration : Math.min(fullDuration, SAFE_DURATION_MS);
            if (risky) {
                double fps = Math.min(12.0, Math.max(0.05, 240_000.0 / exportDuration));
                AnimatedWebpEncoder.encode(t -> fit512(fs.frameAt(t)), exportDuration, fps, 58, output);
                return fullDuration;
            }
            double[] fpsChoices = {8, 6, 4, 3, 2, 1};
            int[] qualities = {55, 45, 38, 32, 25, 15};
            File tmp = new File(output.getAbsolutePath() + ".tmp");
            boolean ok = false;
            for (int i = 0; i < fpsChoices.length; i++) {
                if (tmp.exists()) tmp.delete();
                AnimatedWebpEncoder.encode(t -> fit512(fs.frameAt(t)), exportDuration, fpsChoices[i], qualities[i], tmp);
                if (tmp.length() <= ANIM_LIMIT) { ok = true; break; }
            }
            if (!ok && tmp.length() > ANIM_LIMIT) {
                if (tmp.exists()) tmp.delete();
                AnimatedWebpEncoder.encode(t -> fit512(fs.frameAt(t)), exportDuration, 0.5, 8, tmp);
                ok = tmp.length() <= ANIM_LIMIT;
            }
            if (!ok) {
                int[] detailSteps = {384, 320, 256, 192, 128, 96};
                for (int detail : detailSteps) {
                    if (tmp.exists()) tmp.delete();
                    final int d = detail;
                    AnimatedWebpEncoder.encode(t -> fit512LowDetail(fs.frameAt(t), d), exportDuration, 0.2, 1, tmp);
                    if (tmp.length() <= ANIM_LIMIT) { ok = true; break; }
                }
            }
            if (!ok || tmp.length() > ANIM_LIMIT) {
                tmp.delete();
                throw new IOException("Could not fit animated sticker under 500 KB");
            }
            if (output.exists()) output.delete();
            if (!tmp.renameTo(output)) { try (FileInputStream in = new FileInputStream(tmp); FileOutputStream out = new FileOutputStream(output)) { copy(in, out); } tmp.delete(); }
            return fullDuration;
        } finally { fs.close(); }
    }

    private interface FrameSource { long durationMs() throws Exception; Bitmap frameAt(long tMs) throws Exception; void close(); }

    private FrameSource openFrameSource(File file) throws Exception {
        if (isGif(file) || isAnimatedWebp(file)) {
            Movie movie = Movie.decodeFile(file.getAbsolutePath());
            if (movie != null && movie.width() > 0 && movie.height() > 0) {
                return new FrameSource() {
                    final long duration = movie.duration() > 0 ? movie.duration() : 1000;
                    public long durationMs() { return duration; }
                    public Bitmap frameAt(long tMs) {
                        Bitmap b = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(b); c.drawColor(Color.TRANSPARENT);
                        synchronized (movie) { movie.setTime((int)(tMs % duration)); movie.draw(c, 0, 0); }
                        return b;
                    }
                    public void close() { }
                };
            }
        }
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        mmr.setDataSource(file.getAbsolutePath());
        String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        long duration = d == null ? 1000 : Long.parseLong(d);
        return new FrameSource() {
            public long durationMs() { return duration; }
            public Bitmap frameAt(long tMs) {
                Bitmap b = mmr.getFrameAtTime(tMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                if (b == null) b = mmr.getFrameAtTime(tMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                return b;
            }
            public void close() { try { mmr.release(); } catch (Exception ignored) {} }
        };
    }

    public static Bitmap fit512(Bitmap src) {
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out); c.drawColor(Color.TRANSPARENT); drawFit(c, src, 512, 512);
        if (src != out) src.recycle();
        return out;
    }

    private static Bitmap fit512LowDetail(Bitmap src, int detail) {
        Bitmap full = fit512(src);
        if (full == null || detail >= 512) return full;
        Bitmap reduced = reduceDetail(full, detail);
        full.recycle();
        return reduced;
    }

    private static Bitmap reduceDetail(Bitmap source, int detail) {
        int d = Math.max(32, Math.min(512, detail));
        Bitmap small = Bitmap.createScaledBitmap(source, d, d, true);
        Bitmap restored = Bitmap.createScaledBitmap(small, 512, 512, true);
        if (small != source && small != restored) small.recycle();
        return restored;
    }

    private static void drawFit(Canvas c, Bitmap src, int w, int h) {
        float scale = Math.min(1f, Math.min((float)w / src.getWidth(), (float)h / src.getHeight()));
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        float left = (w - dw) / 2f, top = (h - dh) / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(src, null, new android.graphics.RectF(left, top, left + dw, top + dh), p);
    }

    private Bitmap decodeOriented(File f) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        int sample = 1;
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        while (max > 2048 && sample < 32) { sample *= 2; max /= 2; }
        BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = sample; opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), opts); if (b == null) return null;
        try {
            ExifInterface exif = new ExifInterface(f.getAbsolutePath());
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix m = new Matrix(); boolean changed = false;
            if (o == ExifInterface.ORIENTATION_ROTATE_90) { m.postRotate(90); changed = true; }
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) { m.postRotate(180); changed = true; }
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) { m.postRotate(270); changed = true; }
            else if (o == ExifInterface.ORIENTATION_FLIP_HORIZONTAL) { m.postScale(-1, 1); changed = true; }
            else if (o == ExifInterface.ORIENTATION_FLIP_VERTICAL) { m.postScale(1, -1); changed = true; }
            if (changed) {
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true); b.recycle(); b = r;
            }
        } catch (Exception ignored) { }
        return b;
    }

    public static boolean isAnimatedWebp(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int)Math.min(f.length(), 256 * 1024)]; int n = in.read(data);
            if (n < 16) return false;
            String s = new String(data, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1);
            return s.startsWith("RIFF") && s.length() > 12 && s.substring(8, 12).equals("WEBP") && s.contains("ANIM");
        } catch (Exception e) { return false; }
    }
    private static boolean isGif(File f) {
        try (FileInputStream in = new FileInputStream(f)) { byte[] b = new byte[6]; return in.read(b) == 6 && new String(b).startsWith("GIF8"); }
        catch (Exception e) { return false; }
    }
    private static boolean isVideoMime(String mime) { return mime != null && mime.toLowerCase(Locale.ROOT).startsWith("video/"); }

    private static String displayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return uri.getLastPathSegment();
    }
    private static String stripExt(String s) { int i = s.lastIndexOf('.'); return i > 0 ? s.substring(0, i) : s; }
    private static String extension(String name, String mime) {
        if (name != null) { int i = name.lastIndexOf('.'); if (i >= 0 && name.length() - i <= 8) return name.substring(i).toLowerCase(Locale.ROOT); }
        if (mime != null) {
            if (mime.contains("gif")) return ".gif"; if (mime.contains("webp")) return ".webp";
            if (mime.startsWith("video/")) return ".mp4"; if (mime.contains("png")) return ".png";
        }
        return ".img";
    }
    private static void copy(InputStream in, java.io.OutputStream out) throws IOException { byte[] b = new byte[64 * 1024]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); }
    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) { byte[] b = new byte[64 * 1024]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
        StringBuilder sb = new StringBuilder(); for (byte x : md.digest()) sb.append(String.format(Locale.US, "%02x", x)); return sb.toString();
    }
}
