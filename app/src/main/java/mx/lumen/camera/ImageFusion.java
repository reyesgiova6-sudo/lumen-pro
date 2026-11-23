package mx.lumen.camera;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * Procesado computacional real, sin atajos:
 *
 *  - HDR: alineado de ráfaga (MTB) + fusión por exposición ponderada (well-exposedness)
 *    + microcontraste y curva tonal suave.
 *  - Noche: apilado en espacio lineal con rechazo de movimiento (reduce ruido ~sqrt(N)),
 *    denoise de croma y realce de detalle.
 *
 * Todo se ejecuta en hilos paralelos por filas y trabaja en RGB de 3 bytes por píxel
 * para no reventar la memoria en móviles.
 */
public final class ImageFusion {

    public interface Progress {
        void onProgress(int percent);
    }

    private static final float[] SRGB_TO_LIN = new float[256];
    private static final int LIN_LUT_SIZE = 4096;
    private static final float[] LIN_TO_SRGB = new float[LIN_LUT_SIZE + 1];

    static {
        for (int i = 0; i < 256; i++) {
            float c = i / 255f;
            SRGB_TO_LIN[i] = c <= 0.04045f ? c / 12.92f
                    : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
        }
        for (int i = 0; i <= LIN_LUT_SIZE; i++) {
            float v = i / (float) LIN_LUT_SIZE;
            float s = v <= 0.0031308f ? v * 12.92f
                    : 1.055f * (float) Math.pow(v, 1 / 2.4f) - 0.055f;
            LIN_TO_SRGB[i] = Util.clamp(s, 0f, 1f);
        }
    }

    private ImageFusion() {}

    private static final class Frame {
        int w;
        int h;
        byte[] rgb;
        int dx;
        int dy;
        double meanLuma;
    }

    // ------------------------------------------------------------------- HDR

    public static Bitmap fuseHdr(List<Bitmap> frames, Progress progress) {
        if (frames == null || frames.isEmpty()) return null;
        int w = frames.get(0).getWidth();
        int h = frames.get(0).getHeight();
        List<Frame> list = prepare(frames, progress, 15);
        if (list.isEmpty()) return null;
        w = list.get(0).w;
        h = list.get(0).h;

        Frame reference = pickReference(list);
        if (progress != null) progress.onProgress(30);
        align(list, reference);
        if (progress != null) progress.onProgress(45);

        byte[] out = new byte[w * h * 3];
        float[] well = new float[256];
        for (int i = 0; i < 256; i++) {
            float x = (i - 128f) / 255f;
            well[i] = (float) Math.exp(-(x * x) / (2f * 0.22f * 0.22f));
        }

        final int fw = w;
        final int fh = h;
        final Frame ref = reference;
        final int n = list.size();
        final Frame[] fr = list.toArray(new Frame[0]);

        java.util.stream.IntStream.range(0, fh).parallel().forEach(y -> {
            for (int x = 0; x < fw; x++) {
                float sumR = 0, sumG = 0, sumB = 0, sumW = 0;
                for (int k = 0; k < n; k++) {
                    Frame f = fr[k];
                    int sx = x + f.dx;
                    int sy = y + f.dy;
                    if (sx < 0) sx = 0;
                    if (sy < 0) sy = 0;
                    if (sx >= fw) sx = fw - 1;
                    if (sy >= fh) sy = fh - 1;
                    int o = (sy * fw + sx) * 3;
                    int r = f.rgb[o] & 0xFF;
                    int g = f.rgb[o + 1] & 0xFF;
                    int b = f.rgb[o + 2] & 0xFF;
                    int luma = (r * 77 + g * 150 + b * 29) >> 8;

                    float weight = well[luma];
                    int maxc = Math.max(r, Math.max(g, b));
                    int minc = Math.min(r, Math.min(g, b));
                    if (maxc > 247) weight *= Math.max(0.05f, (255 - maxc) / 12f);
                    if (minc < 6) weight *= 0.6f;
                    // Penaliza colores muy planos (zonas sin textura) para no aplanar el resultado.
                    int sat = maxc - minc;
                    weight *= 0.55f + 0.45f * (sat / 96f);
                    if (weight <= 0.0001f) weight = 0.0001f;

                    sumR += r * weight;
                    sumG += g * weight;
                    sumB += b * weight;
                    sumW += weight;
                }
                int o = (y * fw + x) * 3;
                out[o] = (byte) (sumR / sumW);
                out[o + 1] = (byte) (sumG / sumW);
                out[o + 2] = (byte) (sumB / sumW);
            }
        });
        if (progress != null) progress.onProgress(75);

        unsharp(out, w, h, 0.32f, 2f);
        if (progress != null) progress.onProgress(88);
        toneMap(out, w, h, 1f);
        if (progress != null) progress.onProgress(100);
        return toBitmap(out, w, h);
    }

    // ------------------------------------------------------------------ noche

    public static Bitmap stackNight(List<Bitmap> frames, Progress progress) {
        if (frames == null || frames.isEmpty()) return null;
        List<Frame> list = prepare(frames, progress, 10);
        if (list.isEmpty()) return null;
        int w = list.get(0).w;
        int h = list.get(0).h;
        Frame reference = pickReference(list);
        if (progress != null) progress.onProgress(25);
        align(list, reference);
        if (progress != null) progress.onProgress(40);

        float[] reject = new float[256];
        for (int i = 0; i < 256; i++) {
            float d = i / 255f;
            reject[i] = (float) Math.exp(-(d * d) / (2f * 0.09f * 0.09f));
        }

        byte[] out = new byte[w * h * 3];
        final Frame[] fr = list.toArray(new Frame[0]);
        final int n = fr.length;
        final int fw = w;
        final int fh = h;
        final Frame ref = reference;

        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int rl = sampleLuma(ref.rgb, fw, fh, x, y);
                float sumR = 0, sumG = 0, sumB = 0, sumW = 0;
                for (int k = 0; k < n; k++) {
                    Frame f = fr[k];
                    int sx = x + f.dx;
                    int sy = y + f.dy;
                    if (sx < 0) sx = 0;
                    if (sy < 0) sy = 0;
                    if (sx >= fw) sx = fw - 1;
                    if (sy >= fh) sy = fh - 1;
                    int o = (sy * fw + sx) * 3;
                    int r = f.rgb[o] & 0xFF;
                    int g = f.rgb[o + 1] & 0xFF;
                    int b = f.rgb[o + 2] & 0xFF;
                    int luma = (r * 77 + g * 150 + b * 29) >> 8;

                    float weight = reject[Math.abs(luma - rl)];
                    int maxc = Math.max(r, Math.max(g, b));
                    if (maxc > 250) weight *= 0.15f;
                    if (maxc < 4) weight *= 0.5f;
                    if (weight < 0.02f) weight = 0.02f;

                    sumR += SRGB_TO_LIN[r] * weight;
                    sumG += SRGB_TO_LIN[g] * weight;
                    sumB += SRGB_TO_LIN[b] * weight;
                    sumW += weight;
                }
                float rl2 = sumR / sumW;
                float gl2 = sumG / sumW;
                float bl2 = sumB / sumW;
                int o = (y * fw + x) * 3;
                out[o] = (byte) (linToSrgb(rl2) * 255f);
                out[o + 1] = (byte) (linToSrgb(gl2) * 255f);
                out[o + 2] = (byte) (linToSrgb(bl2) * 255f);
            }
        });
        if (progress != null) progress.onProgress(70);

        chromaDenoise(out, w, h);
        if (progress != null) progress.onProgress(82);
        unsharp(out, w, h, 0.38f, 1.4f);
        toneMap(out, w, h, 1.15f);
        if (progress != null) progress.onProgress(100);
        return toBitmap(out, w, h);
    }

    /** Versión ligera para fotos individuales: microcontraste + curva suave. */
    public static Bitmap polish(Bitmap src, float amount) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = Util.bitmapToArgb(src);
        byte[] rgb = new byte[w * h * 3];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            rgb[i * 3] = (byte) (c >> 16);
            rgb[i * 3 + 1] = (byte) (c >> 8);
            rgb[i * 3 + 2] = (byte) c;
        }
        unsharp(rgb, w, h, amount, 1.6f);
        toneMap(rgb, w, h, 1f);
        return toBitmap(rgb, w, h);
    }

    // ------------------------------------------------------------- internals

    private static List<Frame> prepare(List<Bitmap> bitmaps, Progress progress, int pct) {
        List<Frame> out = new ArrayList<>();
        long budget = 140L * 1024 * 1024; // bytes máximos para todas las tramas
        int w = bitmaps.get(0).getWidth();
        int h = bitmaps.get(0).getHeight();
        int n = bitmaps.size();
        while ((long) w * h * 3 * n > budget && w > 800) {
            w /= 2;
            h /= 2;
        }
        for (int i = 0; i < n; i++) {
            Bitmap src = bitmaps.get(i);
            Bitmap b = src;
            if (src.getWidth() != w) {
                b = Bitmap.createScaledBitmap(src, w, h, true);
            }
            Frame f = new Frame();
            f.w = w;
            f.h = h;
            f.rgb = toRgb(b);
            f.meanLuma = meanLuma(f);
            out.add(f);
            if (b != src) b.recycle();
            if (!src.isRecycled() && src != bitmaps.get(0)) src.recycle();
            if (progress != null) progress.onProgress(pct * (i + 1) / n);
        }
        // La primera trama también se libera: ya está copiada a bytes.
        Bitmap first = bitmaps.get(0);
        if (!first.isRecycled()) first.recycle();
        return out;
    }

    private static byte[] toRgb(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        byte[] rgb = new byte[w * h * 3];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int o = i * 3;
            rgb[o] = (byte) (c >> 16);
            rgb[o + 1] = (byte) (c >> 8);
            rgb[o + 2] = (byte) c;
        }
        return rgb;
    }

    private static double meanLuma(Frame f) {
        long sum = 0;
        int step = Math.max(1, (f.w * f.h) / 20000);
        int count = 0;
        for (int i = 0; i < f.w * f.h; i += step) {
            int o = i * 3;
            sum += (f.rgb[o] & 0xFF) * 77 + (f.rgb[o + 1] & 0xFF) * 150
                    + (f.rgb[o + 2] & 0xFF) * 29 >> 8;
            count++;
        }
        return count == 0 ? 0 : sum / (double) count;
    }

    private static Frame pickReference(List<Frame> frames) {
        double target = 0;
        for (Frame f : frames) target += f.meanLuma / frames.size();
        Frame best = frames.get(0);
        double bestDiff = Double.MAX_VALUE;
        for (Frame f : frames) {
            double d = Math.abs(f.meanLuma - target);
            if (d < bestDiff) {
                bestDiff = d;
                best = f;
            }
        }
        return best;
    }

    private static void align(List<Frame> frames, Frame reference) {
        int w = reference.w;
        int h = reference.h;
        int step = 8;
        int sw = Math.max(4, w / step);
        int sh = Math.max(4, h / step);
        int[] refBits = mtb(reference.rgb, w, h, step, sw, sh);

        for (Frame f : frames) {
            if (f == reference) {
                f.dx = 0;
                f.dy = 0;
                continue;
            }
            int[] bits = mtb(f.rgb, w, h, step, sw, sh);
            int[] shift = search(refBits, bits, sw, sh, 8);
            f.dx = shift[0] * step;
            f.dy = shift[1] * step;
        }
    }

    private static int[] mtb(byte[] rgb, int w, int h, int step, int sw, int sh) {
        int[] luma = new int[sw * sh];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int o = ((y * step) * w + x * step) * 3;
                int r = rgb[o] & 0xFF;
                int g = rgb[o + 1] & 0xFF;
                int b = rgb[o + 2] & 0xFF;
                luma[y * sw + x] = (r * 77 + g * 150 + b * 29) >> 8;
            }
        }
        int[] sorted = luma.clone();
        java.util.Arrays.sort(sorted);
        int median = sorted[sorted.length / 2];
        int[] bits = new int[luma.length];
        for (int i = 0; i < luma.length; i++) bits[i] = luma[i] > median ? 1 : 0;
        return bits;
    }

    private static int[] search(int[] ref, int[] mov, int sw, int sh, int maxShift) {
        int bestX = 0;
        int bestY = 0;
        long bestErr = Long.MAX_VALUE;
        for (int dy = -maxShift; dy <= maxShift; dy++) {
            for (int dx = -maxShift; dx <= maxShift; dx++) {
                long err = 0;
                int y0 = Math.max(maxShift, -dy + maxShift);
                int y1 = Math.min(sh - maxShift, sh - dy - maxShift);
                int x0 = Math.max(maxShift, -dx + maxShift);
                int x1 = Math.min(sw - maxShift, sw - dx - maxShift);
                for (int y = y0; y < y1; y++) {
                    int rowRef = y * sw;
                    int rowMov = (y + dy) * sw;
                    for (int x = x0; x < x1; x++) {
                        if (ref[rowRef + x] != mov[rowMov + x + dx]) err++;
                    }
                }
                if (err < bestErr) {
                    bestErr = err;
                    bestX = dx;
                    bestY = dy;
                }
            }
        }
        return new int[]{bestX, bestY};
    }

    private static int sampleLuma(byte[] rgb, int w, int h, int x, int y) {
        int o = (y * w + x) * 3;
        return ((rgb[o] & 0xFF) * 77 + (rgb[o + 1] & 0xFF) * 150
                + (rgb[o + 2] & 0xFF) * 29) >> 8;
    }

    private static float linToSrgb(float v) {
        if (v <= 0) return 0f;
        if (v >= 1f) return 1f;
        float pos = v * LIN_LUT_SIZE;
        int i = (int) pos;
        if (i >= LIN_LUT_SIZE) return LIN_TO_SRGB[LIN_LUT_SIZE];
        float frac = pos - i;
        return LIN_TO_SRGB[i] * (1f - frac) + LIN_TO_SRGB[i + 1] * frac;
    }

    /** Microcontraste con desenfoque separable rápido. */
    private static void unsharp(byte[] rgb, int w, int h, float amount, float radius) {
        if (amount <= 0) return;
        int[] luma = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            int o = i * 3;
            luma[i] = ((rgb[o] & 0xFF) * 77 + (rgb[o + 1] & 0xFF) * 150
                    + (rgb[o + 2] & 0xFF) * 29) >> 8;
        }
        int r = Math.max(1, Math.round(radius));
        int[] blurred = new int[w * h];
        final int[] src = luma;
        final int[] dst = blurred;
        final int rad = r;
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            int acc = 0;
            int count = 0;
            for (int k = -rad; k <= rad; k++) {
                int xx = Util.clamp(k, 0, w - 1);
                acc += src[y * w + xx];
                count++;
            }
            for (int x = 0; x < w; x++) {
                dst[y * w + x] = acc / count;
                int outX = Util.clamp(x - rad, 0, w - 1);
                int inX = Util.clamp(x + rad + 1, 0, w - 1);
                acc += src[y * w + inX] - src[y * w + outX];
            }
        });
        int[] tmp = blurred;
        int[] blurred2 = new int[w * h];
        java.util.stream.IntStream.range(0, w).parallel().forEach(x -> {
            int acc = 0;
            int count = 0;
            for (int k = -rad; k <= rad; k++) {
                acc += tmp[Util.clamp(k, 0, h - 1) * w + x];
                count++;
            }
            for (int y = 0; y < h; y++) {
                blurred2[y * w + x] = acc / count;
                acc += tmp[Util.clamp(y + rad + 1, 0, h - 1) * w + x]
                        - tmp[Util.clamp(y - rad, 0, h - 1) * w + x];
            }
        });

        final int[] blur = blurred2;
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int diff = src[i] - blur[i];
                int add = Math.round(diff * amount);
                int o = i * 3;
                rgb[o] = (byte) Util.clamp((rgb[o] & 0xFF) + add, 0, 255);
                rgb[o + 1] = (byte) Util.clamp((rgb[o + 1] & 0xFF) + add, 0, 255);
                rgb[o + 2] = (byte) Util.clamp((rgb[o + 2] & 0xFF) + add, 0, 255);
            }
        });
    }

    /** Curva tonal: sombras abiertas, luces comprimidas, saturación contenida. */
    private static void toneMap(byte[] rgb, int w, int h, float strength) {
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            float x = i / 255f;
            // Contraste suave tipo S, sin lavar la imagen.
            float y = x + (float) Math.sin((x - 0.5f) * Math.PI) * 0.055f * strength;
            y = y + (x - y) * 0.35f * (1f - Math.abs(x - 0.5f) * 2f);
            if (y > 0.80f) {
                float over = (y - 0.80f) / 0.20f;
                y = 0.80f + 0.20f * (float) Math.tanh(over * 1.1f) / (float) Math.tanh(1.1f);
            }
            y = Util.clamp(y, 0f, 1f);
            lut[i] = Math.round(y * 255f);
        }
        final float sat = 1.06f + 0.05f * (strength - 1f);
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int o = (y * w + x) * 3;
                int r = lut[rgb[o] & 0xFF];
                int g = lut[rgb[o + 1] & 0xFF];
                int b = lut[rgb[o + 2] & 0xFF];
                int l = (r * 77 + g * 150 + b * 29) >> 8;
                r = Util.clamp(Math.round(l + (r - l) * sat), 0, 255);
                g = Util.clamp(Math.round(l + (g - l) * sat), 0, 255);
                b = Util.clamp(Math.round(l + (b - l) * sat), 0, 255);
                rgb[o] = (byte) r;
                rgb[o + 1] = (byte) g;
                rgb[o + 2] = (byte) b;
            }
        });
    }

    /** Suaviza croma dejando la luminancia intacta (quita manchas de color en ISOs altas). */
    private static void chromaDenoise(byte[] rgb, int w, int h) {
        byte[] out = rgb.clone();
        final int[][] offsets = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int o = (y * w + x) * 3;
                int r = rgb[o] & 0xFF;
                int g = rgb[o + 1] & 0xFF;
                int b = rgb[o + 2] & 0xFF;
                int yv = (r * 77 + g * 150 + b * 29) >> 8;
                int cb = 0;
                int cr = 0;
                int count = 0;
                for (int[] off : offsets) {
                    int xx = Util.clamp(x + off[0], 0, w - 1);
                    int yy = Util.clamp(y + off[1], 0, h - 1);
                    int p = (yy * w + xx) * 3;
                    int pr = rgb[p] & 0xFF;
                    int pg = rgb[p + 1] & 0xFF;
                    int pb = rgb[p + 2] & 0xFF;
                    int py = (pr * 77 + pg * 150 + pb * 29) >> 8;
                    cb += pb - py;
                    cr += pr - py;
                    count++;
                }
                int ncb = cb / count;
                int ncr = cr / count;
                out[o] = (byte) Util.clamp(yv + ncr, 0, 255);
                out[o + 1] = (byte) Util.clamp(yv - (ncr * 3 + ncb) / 10, 0, 255);
                out[o + 2] = (byte) Util.clamp(yv + ncb, 0, 255);
            }
        });
        System.arraycopy(out, 0, rgb, 0, rgb.length);
    }

    private static Bitmap toBitmap(byte[] rgb, int w, int h) {
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int o = i * 3;
            px[i] = Color.rgb(rgb[o] & 0xFF, rgb[o + 1] & 0xFF, rgb[o + 2] & 0xFF);
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }
}
