package mx.lumen.camera;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Hoja de ajustes con opciones reales (nada decorativo). */
public final class SettingsDialog {

    public interface Callback {
        void onChanged(String key);
    }

    private SettingsDialog() {
    }

    public static void show(Activity a, Prefs p, CameraEngine.Caps caps, Callback cb) {
        Dialog dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xF00A0A0C);
        int pad = (int) dp(a, 18);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(a);
        title.setText("AJUSTES");
        title.setTextColor(0xFFFFD166);
        title.setTextSize(15);
        title.setLetterSpacing(0.22f);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(a);
        sub.setText("LUMEN Pro · " + Build.MANUFACTURER + " " + Build.MODEL);
        sub.setTextColor(0xFF8A8A90);
        sub.setTextSize(10);
        root.addView(sub, marginParams(a, 0, 4, 0, 10));

        ScrollView scroll = new ScrollView(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        addSection(a, list, "IMAGEN");
        final int[] resOptions = {12, 24, 48};
        addChoice(a, list, "Resolución", new String[]{"12 MP", "24 MP", "MÁXIMA"},
                indexOf(resOptions, p.resolutionMp()), i -> {
                    p.setResolutionMp(resOptions[i]);
                    cb.onChanged("resolution");
                    return i == 2 ? "MÁXIMA" : resOptions[i] + " MP";
                });
        final int[] qualityOptions = {90, 95, 97, 99};
        addChoice(a, list, "Calidad JPEG", new String[]{"90", "95", "97", "99"},
                indexOf(qualityOptions, p.jpegQuality()), i -> {
                    p.setJpegQuality(qualityOptions[i]);
                    return String.valueOf(qualityOptions[i]);
                });
        addToggle(a, list, "Guardar RAW (DNG)", p.saveRaw(), caps != null && caps.raw, v -> {
            p.setSaveRaw(v);
            cb.onChanged("raw");
        });
        addToggle(a, list, "Espejo en cámara frontal", p.mirrorFront(), true, p::setMirrorFront);

        addSection(a, list, "HDR Y NOCHE");
        final int[] hdrOptions = {3, 5, 7};
        addChoice(a, list, "Tomas HDR", new String[]{"3", "5", "7"},
                indexOf(hdrOptions, p.hdrFrames()), i -> {
                    p.setHdrFrames(hdrOptions[i]);
                    cb.onChanged("hdr");
                    return String.valueOf(hdrOptions[i]);
                });
        final int[] nightOptions = {3, 5, 7, 9};
        addChoice(a, list, "Tomas noche", new String[]{"3", "5", "7", "9"},
                indexOf(nightOptions, p.nightFrames()), i -> {
                    p.setNightFrames(nightOptions[i]);
                    cb.onChanged("night");
                    return String.valueOf(nightOptions[i]);
                });
        final int[] mergeOptions = {4, 6, 8, 12};
        addChoice(a, list, "Resolución de fusión", new String[]{"4 MP", "6 MP", "8 MP", "12 MP"},
                indexOf(mergeOptions, p.mergeMp()), i -> {
                    p.setMergeMp(mergeOptions[i]);
                    return mergeOptions[i] + " MP";
                });

        addSection(a, list, "VÍDEO");
        final int[] videoOptions = {1080, 1440, 2160};
        addChoice(a, list, "Resolución", new String[]{"1080p", "1440p", "2160p (4K)"},
                indexOf(videoOptions, p.videoHeight()), i -> {
                    p.setVideoHeight(videoOptions[i]);
                    return videoOptions[i] + "p";
                });
        final int[] bitrateOptions = {40, 60, 100, 150};
        addChoice(a, list, "Bitrate", new String[]{"40 Mbps", "60 Mbps", "100 Mbps", "150 Mbps"},
                indexOf(bitrateOptions, p.videoBitrateMbps()), i -> {
                    p.setVideoBitrateMbps(bitrateOptions[i]);
                    return bitrateOptions[i] + " Mbps";
                });
        addToggle(a, list, "Grabar en HEVC (H.265)", p.videoHevc(), true, p::setVideoHevc);

        addSection(a, list, "VISTA Y SONIDO");
        final int[] rotationOptions = {-1, 0, 90, 180, 270};
        final String[] rotationLabels = {"AUTO", "0°", "90°", "180°", "270°"};
        addChoice(a, list, "Rotación de vista", rotationLabels,
                indexOf(rotationOptions, p.previewRotation()), i -> {
                    p.setPreviewRotation(rotationOptions[i]);
                    cb.onChanged("rotation");
                    return rotationLabels[i];
                });
        addToggle(a, list, "Fotos en 16:9", p.aspect169(), true, v -> {
            p.setAspect169(v);
            cb.onChanged("aspect");
        });
        addToggle(a, list, "Sonido de obturador", p.shutterSound(), true, p::setShutterSound);
        addToggle(a, list, "Estabilización de vídeo", p.videoStabilization(), true, v -> {
            p.setVideoStabilization(v);
            cb.onChanged("stab");
        });
        final int[] fpsOptions = {30, 60};
        addChoice(a, list, "FPS de vídeo", new String[]{"30", "60"},
                indexOf(fpsOptions, p.videoFps()), i -> {
                    p.setVideoFps(fpsOptions[i]);
                    cb.onChanged("fps");
                    return String.valueOf(fpsOptions[i]);
                });
        addToggle(a, list, "Microcontraste en AUTO", p.autoPolish(), true, p::setAutoPolish);

        addSection(a, list, "INTERFAZ");
        addToggle(a, list, "Rejilla de tercios", p.grid(), true, v -> {
            p.setGrid(v);
            cb.onChanged("grid");
        });
        addToggle(a, list, "Horizonte / nivel", p.level(), true, v -> {
            p.setLevel(v);
            cb.onChanged("level");
        });
        addToggle(a, list, "Histograma en vivo", p.histogram(), true, v -> {
            p.setHistogram(v);
            cb.onChanged("histogram");
        });

        TextView about = new TextView(a);
        about.setText("Camera2 · control manual, RAW, ráfagas HDR y apilado nocturno.\n"
                + "Las fotos se guardan en Pictures/LUMEN y los vídeos en Movies/LUMEN.");
        about.setTextColor(0xFF6E6E74);
        about.setTextSize(10);
        about.setLineSpacing(dp(a, 3), 1f);
        list.addView(about, marginParams(a, 0, 14, 0, 4));

        TextView close = new TextView(a);
        close.setText("LISTO");
        close.setTextColor(0xFF111114);
        close.setBackground(a.getDrawable(R.drawable.bg_chip_selected));
        close.setGravity(Gravity.CENTER);
        close.setTextSize(12);
        close.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        close.setPadding(0, (int) dp(a, 12), 0, (int) dp(a, 12));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        closeParams.topMargin = (int) dp(a, 14);
        root.addView(close, closeParams);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
    }

    private interface Cycle {
        String apply(int index);
    }

    private static void addSection(Activity a, LinearLayout parent, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextColor(0xFF8A8A90);
        tv.setTextSize(9);
        tv.setLetterSpacing(0.22f);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        parent.addView(tv, marginParams(a, 0, 16, 0, 4));
    }

    private static void addChoice(Activity a, LinearLayout parent, String label,
                                  String[] values, int current, Cycle cycle) {
        if (current < 0 || current >= values.length) current = 0;
        LinearLayout row = row(a);
        TextView title = rowTitle(a, label);
        TextView value = new TextView(a);
        value.setText(values[current]);
        value.setTextColor(0xFFFFD166);
        value.setTextSize(12);
        value.setTypeface(Typeface.MONOSPACE);
        row.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);
        final int[] idx = {current};
        row.setOnClickListener(v -> {
            idx[0] = (idx[0] + 1) % values.length;
            value.setText(cycle.apply(idx[0]));
            value.animate().alpha(0.4f).alpha(1f).setDuration(160).start();
        });
        parent.addView(row);
    }

    private interface BoolChange {
        void apply(boolean value);
    }

    private static void addToggle(Activity a, LinearLayout parent, String label,
                                  boolean current, boolean enabled, BoolChange change) {
        LinearLayout row = row(a);
        TextView title = rowTitle(a, label);
        TextView value = new TextView(a);
        value.setText(current ? "SÍ" : "NO");
        value.setTextColor(current ? 0xFFFFD166 : 0xFF6E6E74);
        value.setTextSize(12);
        value.setTypeface(Typeface.MONOSPACE);
        row.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);
        row.setAlpha(enabled ? 1f : 0.35f);
        row.setEnabled(enabled);
        final boolean[] state = {current};
        row.setOnClickListener(v -> {
            state[0] = !state[0];
            value.setText(state[0] ? "SÍ" : "NO");
            value.setTextColor(state[0] ? 0xFFFFD166 : 0xFF6E6E74);
            change.apply(state[0]);
        });
        parent.addView(row);
    }

    private static LinearLayout row(Activity a) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) dp(a, 12), 0, (int) dp(a, 12));
        row.setBackgroundColor(0x0DFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(a, 1);
        row.setLayoutParams(lp);
        row.setClickable(true);
        return row;
    }

    private static TextView rowTitle(Activity a, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextColor(0xFFF0F0F2);
        tv.setTextSize(12);
        return tv;
    }

    private static LinearLayout.LayoutParams marginParams(Activity a, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins((int) dp(a, l), (int) dp(a, t), (int) dp(a, r), (int) dp(a, b));
        return lp;
    }

    private static int indexOf(int[] values, int target) {
        for (int i = 0; i < values.length; i++) if (values[i] == target) return i;
        return 0;
    }

    private static float dp(Activity a, float v) {
        return v * a.getResources().getDisplayMetrics().density;
    }
}
