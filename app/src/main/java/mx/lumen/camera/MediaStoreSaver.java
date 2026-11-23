package mx.lumen.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Guarda fotos, RAW y vídeo en la galería del sistema dentro del álbum LUMEN. */
public class MediaStoreSaver {

    public static final String ALBUM = "LUMEN";

    private final Context ctx;

    public MediaStoreSaver(Context ctx) {
        this.ctx = ctx;
    }

    private boolean modern() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    public Uri saveJpeg(byte[] jpeg, String name) throws IOException {
        return saveBytes(jpeg, name, "image/jpeg", Environment.DIRECTORY_PICTURES);
    }

    public Uri saveJpeg(Bitmap bmp, String name, CameraEngine.ShotInfo info, int quality)
            throws IOException {
        File tmp = new File(ctx.getCacheDir(), name + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, fos)) {
                throw new IOException("No se pudo codificar el JPEG");
            }
        }
        writeExif(tmp, info);
        byte[] data;
        try (FileInputStream fis = new FileInputStream(tmp)) {
            data = readAll(fis);
        }
        Uri uri = saveBytes(data, name, "image/jpeg", Environment.DIRECTORY_PICTURES);
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        return uri;
    }

    private Uri saveBytes(byte[] data, String name, String mime, String dir)
            throws IOException {
        if (modern()) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, dir + "/" + ALBUM);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = ctx.getContentResolver().insert(collectionFor(mime), values);
            if (uri == null) throw new IOException("MediaStore rechazó el archivo");
            try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IOException("Sin salida para " + uri);
                os.write(data);
                os.flush();
            } catch (IOException e) {
                ctx.getContentResolver().delete(uri, null, null);
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, done, null, null);
            return uri;
        } else {
            File dirFile = new File(Environment.getExternalStoragePublicDirectory(dir), ALBUM);
            if (!dirFile.exists() && !dirFile.mkdirs()) {
                throw new IOException("No se pudo crear la carpeta " + dirFile);
            }
            File out = new File(dirFile, name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(data);
            }
            MediaScannerConnection.scanFile(ctx, new String[]{out.getAbsolutePath()},
                    new String[]{mime}, null);
            return Uri.fromFile(out);
        }
    }

    private Uri collectionFor(String mime) {
        if (mime.startsWith("video")) {
            return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        }
        return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    public Uri saveDng(byte[] dng, String name) throws IOException {
        return saveBytes(dng, name, "image/x-adobe-dng", Environment.DIRECTORY_PICTURES);
    }

    // ------------------------------------------------------------------ video

    public static class VideoTarget {
        public Uri uri;
        public File file;
    }

    public VideoTarget createVideo(String name) throws IOException {
        VideoTarget target = new VideoTarget();
        if (modern()) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/" + ALBUM);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = ctx.getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("MediaStore rechazó el vídeo");
            target.uri = uri;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES), ALBUM);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("No se pudo crear " + dir);
            target.file = new File(dir, name);
            target.uri = Uri.fromFile(target.file);
        }
        return target;
    }

    public ParcelFileDescriptor openVideoFd(VideoTarget target) throws IOException {
        if (target.file != null) {
            return ParcelFileDescriptor.open(target.file,
                    ParcelFileDescriptor.MODE_READ_WRITE
                            | ParcelFileDescriptor.MODE_CREATE
                            | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        ParcelFileDescriptor pfd =
                ctx.getContentResolver().openFileDescriptor(target.uri, "rw");
        if (pfd == null) throw new IOException("No se pudo abrir el archivo de vídeo");
        return pfd;
    }

    public void finishVideo(VideoTarget target) {
        if (target == null || target.uri == null) return;
        if (modern() && target.file == null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            ctx.getContentResolver().update(target.uri, values, null, null);
        } else if (target.file != null) {
            MediaScannerConnection.scanFile(ctx,
                    new String[]{target.file.getAbsolutePath()},
                    new String[]{"video/mp4"}, null);
        }
    }

    // ------------------------------------------------------------------ extra

    public void delete(Uri uri) {
        try {
            ctx.getContentResolver().delete(uri, null, null);
        } catch (Exception ignored) {
        }
    }

    public Bitmap thumbnail(Uri uri, int targetSize) {
        try {
            int orientation = 1;
            try (InputStream exifStream = ctx.getContentResolver().openInputStream(uri)) {
                if (exifStream != null) orientation = Util.exifOrientation(exifStream);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 4;
            Bitmap bmp = null;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(is, null, opts);
            }
            if (bmp == null) return null;
            bmp = Util.applyExif(bmp, orientation);
            return Util.scaleToMaxLongSide(bmp, targetSize);
        } catch (Exception e) {
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    return ctx.getContentResolver().loadThumbnail(uri,
                            new android.util.Size(targetSize, targetSize), null);
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    public void share(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(mime(uri));
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ctx.startActivity(Intent.createChooser(intent, "Compartir"));
    }

    public String mime(Uri uri) {
        try {
            String m = ctx.getContentResolver().getType(uri);
            if (m != null) return m;
        } catch (Exception ignored) {
        }
        String path = uri.toString().toLowerCase(Locale.US);
        if (path.endsWith(".dng")) return "image/x-adobe-dng";
        if (path.endsWith(".mp4")) return "video/mp4";
        return "image/jpeg";
    }

    private void writeExif(File file, CameraEngine.ShotInfo info) {
        if (info == null) return;
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                    String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            exif.setAttribute(ExifInterface.TAG_MAKE, "LUMEN");
            exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL == null ? "Android" : Build.MODEL);
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "LUMEN Pro " + BuildConfig.VERSION_NAME);
            exif.setAttribute(ExifInterface.TAG_DATETIME,
                    new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                            .format(new java.util.Date()));
            if (info.iso > 0) {
                exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                        String.valueOf(info.iso));
            }
            if (info.exposureNs > 0) {
                double sec = info.exposureNs / 1_000_000_000.0;
                exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME,
                        sec >= 1 ? String.valueOf((long) sec) : "1/" + Math.round(1 / sec));
            }
            if (info.aperture > 0) {
                exif.setAttribute(ExifInterface.TAG_F_NUMBER,
                        String.format(Locale.US, "%.1f", info.aperture));
            }
            if (info.focalLength > 0) {
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH,
                        String.valueOf(Math.round(info.focalLength * 100)) + "/100");
            }
            exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, String.valueOf(
                    info.wbKelvin > 0 ? ExifInterface.WHITEBALANCE_MANUAL
                            : ExifInterface.WHITEBALANCE_AUTO));
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT,
                    "LUMEN Pro - " + info.frames + " frame(s)");
            exif.saveAttributes();
        } catch (Exception ignored) {
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
