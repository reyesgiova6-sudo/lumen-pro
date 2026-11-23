package mx.lumen.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.DisplayMetrics;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Util {

    private Util() {}

    public static float dp(Context ctx, float value) {
        return value * ctx.getResources().getDisplayMetrics().density;
    }

    public static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    public static long clamp(long v, long min, long max) {
        return v < min ? min : (v > max ? max : v);
    }

    public static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** Formatea un tiempo de exposición en segundos de forma fotográfica. */
    public static String shutterLabel(long nanos) {
        double sec = nanos / 1_000_000_000.0;
        if (sec >= 1.0) {
            return String.format(Locale.US, "%.1fs", sec);
        }
        long denom = Math.round(1.0 / sec);
        if (denom >= 1000 && denom % 100 == 0) denom = denom / 100 * 100;
        return "1/" + denom;
    }

    public static String shutterShort(long nanos) {
        double sec = nanos / 1_000_000_000.0;
        if (sec >= 1.0) return String.format(Locale.US, "%.1fs", sec);
        return "1/" + Math.round(1.0 / sec);
    }

    public static String evLabel(float ev) {
        if (Math.abs(ev) < 0.05f) return "0";
        return String.format(Locale.US, "%+.1f", ev);
    }

    public static Bitmap rotateBitmap(Bitmap src, int degrees) {
        if (degrees % 360 == 0) return src;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (out != src) src.recycle();
        return out;
    }

    /** Espeja horizontalmente (para la cámara frontal cuando el usuario lo pide). */
    public static Bitmap mirrorBitmap(Bitmap src) {
        Matrix m = new Matrix();
        m.postScale(-1f, 1f, src.getWidth() / 2f, src.getHeight() / 2f);
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (out != src) src.recycle();
        return out;
    }

    public static Bitmap scaleToMaxLongSide(Bitmap src, int maxLongSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longSide = Math.max(w, h);
        if (longSide <= maxLongSide) return src;
        float s = maxLongSide / (float) longSide;
        Bitmap out = Bitmap.createScaledBitmap(src, Math.round(w * s), Math.round(h * s), true);
        if (out != src) src.recycle();
        return out;
    }

    public static int[] bitmapToArgb(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        return px;
    }

    /** Orientación EXIF de un JPEG en grados (0/90/180/270). */
    public static int exifRotation(byte[] jpeg) {
        try {
            androidx.exifinterface.media.ExifInterface exif =
                    new androidx.exifinterface.media.ExifInterface(
                            new java.io.ByteArrayInputStream(jpeg));
            int orientation = exif.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);
            return exifDegrees(orientation);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Lee la orientación EXIF de un stream (no lo cierra). */
    public static int exifOrientation(java.io.InputStream is) {
        try {
            androidx.exifinterface.media.ExifInterface exif =
                    new androidx.exifinterface.media.ExifInterface(is);
            return exif.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception e) {
            return 1;
        }
    }

    public static int exifDegrees(int orientation) {
        switch (orientation) {
            case 3:
            case 4:
                return 180;
            case 5:
            case 6:
            case 7:
                return 90;
            case 8:
                return 270;
            default:
                return 0;
        }
    }

    /** Aplica la orientación EXIF completa (incluye espejados) a un bitmap. */
    public static Bitmap applyExif(Bitmap src, int orientation) {
        if (src == null || orientation == 1) return src;
        Matrix m = new Matrix();
        switch (orientation) {
            case 2:
                m.postScale(-1f, 1f);
                break;
            case 3:
                m.postRotate(180f);
                break;
            case 4:
                m.postScale(1f, -1f);
                break;
            case 5:
                m.postRotate(90f);
                m.postScale(-1f, 1f);
                break;
            case 6:
                m.postRotate(90f);
                break;
            case 7:
                m.postRotate(270f);
                m.postScale(-1f, 1f);
                break;
            case 8:
                m.postRotate(270f);
                break;
            default:
                return src;
        }
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (out != src) src.recycle();
        return out;
    }

    public static String fileStamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    public static int screenWidth(Context ctx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return dm.widthPixels;
    }

    /** Convierte orientación de sensor + rotación de pantalla en grados de rotación de imagen. */
    public static int jpegRotation(int sensorOrientation, int deviceRotationDegrees, boolean front) {
        if (front) {
            return (sensorOrientation + deviceRotationDegrees) % 360;
        }
        return (sensorOrientation - deviceRotationDegrees + 360) % 360;
    }

    public static String bytesToReadable(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
