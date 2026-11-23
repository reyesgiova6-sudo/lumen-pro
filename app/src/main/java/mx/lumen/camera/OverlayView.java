package mx.lumen.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/** Capa de información sobre la vista previa: rejilla, horizonte, histograma, foco, REC. */
public class OverlayView extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint levelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint histoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint recPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean grid = true;
    private boolean level = true;
    private boolean histogram = true;
    private float levelAngle = 0f;
    private boolean levelSupported = true;

    private int[] histR;
    private int[] histG;
    private int[] histB;
    private int histMax = 1;

    private float focusX = -1;
    private float focusY = -1;
    private long focusStart = 0;

    private String zoomText = "";
    private String modeText = "";
    private int countdown = -1;
    private boolean recording = false;
    private int recordSeconds = 0;
    private boolean aeLock = false;
    private boolean afLock = false;
    private Bitmap peaking;

    private final float density;

    public OverlayView(Context context) {
        this(context, null);
    }

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        gridPaint.setColor(0x33FFFFFF);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(0.7f));

        centerPaint.setColor(0x66FFFFFF);
        centerPaint.setStyle(Paint.Style.STROKE);
        centerPaint.setStrokeWidth(dp(1f));

        levelPaint.setColor(0xCCFFD166);
        levelPaint.setStyle(Paint.Style.STROKE);
        levelPaint.setStrokeWidth(dp(1.6f));
        levelPaint.setPathEffect(new DashPathEffect(new float[]{dp(8), dp(6)}, 0));

        textPaint.setColor(0xFFF5F5F7);
        textPaint.setTextSize(dp(11));
        textPaint.setFakeBoldText(true);
        textPaint.setLetterSpacing(0.08f);

        histoPaint.setStyle(Paint.Style.STROKE);
        histoPaint.setStrokeWidth(dp(1f));

        focusPaint.setColor(0xFFFFD166);
        focusPaint.setStyle(Paint.Style.STROKE);
        focusPaint.setStrokeWidth(dp(1.8f));

        recPaint.setColor(0xFFFF453A);
    }

    private float dp(float v) {
        return v * density;
    }

    public void setGrid(boolean v) {
        grid = v;
        invalidate();
    }

    public void setLevelEnabled(boolean v) {
        level = v;
        invalidate();
    }

    public void setHistogramEnabled(boolean v) {
        histogram = v;
        invalidate();
    }

    public void setLevelAngle(float degrees) {
        levelAngle = degrees;
        if (level) invalidate();
    }

    public void setLevelSupported(boolean v) {
        levelSupported = v;
    }

    public void setHistogram(int[] r, int[] g, int[] b) {
        histR = r;
        histG = g;
        histB = b;
        int max = 1;
        if (r != null) {
            for (int i = 1; i < 255; i++) {
                max = Math.max(max, Math.max(r[i], Math.max(g[i], b[i])));
            }
        }
        histMax = max;
        if (histogram) invalidate();
    }

    public void setPeaking(Bitmap bitmap) {
        peaking = bitmap;
        invalidate();
    }

    public void showFocus(float x, float y) {
        focusX = x;
        focusY = y;
        focusStart = SystemClock.uptimeMillis();
        invalidate();
    }

    public void setZoomText(String text) {
        zoomText = text == null ? "" : text;
        invalidate();
    }

    public void setModeText(String text) {
        modeText = text == null ? "" : text;
        invalidate();
    }

    public void setCountdown(int seconds) {
        countdown = seconds;
        invalidate();
    }

    public void setRecording(boolean rec, int seconds) {
        recording = rec;
        recordSeconds = seconds;
        invalidate();
    }

    public void setLocks(boolean ae, boolean af) {
        aeLock = ae;
        afLock = af;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        if (peaking != null && !peaking.isRecycled()) {
            canvas.drawBitmap(peaking, null, new RectF(0, 0, w, h), null);
        }

        if (grid) {
            for (int i = 1; i <= 2; i++) {
                float x = w * i / 3f;
                float y = h * i / 3f;
                canvas.drawLine(x, 0, x, h, gridPaint);
                canvas.drawLine(0, y, w, y, gridPaint);
            }
        }

        // Marca central
        float c = dp(9);
        float cx = w / 2f;
        float cy = h / 2f;
        canvas.drawLine(cx - c, cy, cx - c / 3, cy, centerPaint);
        canvas.drawLine(cx + c / 3, cy, cx + c, cy, centerPaint);
        canvas.drawLine(cx, cy - c, cx, cy - c / 3, centerPaint);
        canvas.drawLine(cx, cy + c / 3, cx, cy + c, centerPaint);

        if (level && levelSupported) {
            drawLevel(canvas, w, h);
        }

        if (histogram && histR != null) {
            drawHistogram(canvas, w);
        }

        if (!zoomText.isEmpty()) {
            String t = zoomText;
            float tw = textPaint.measureText(t);
            float bx = w / 2f;
            float by = dp(66);
            RectF pill = new RectF(bx - tw / 2f - dp(10), by - dp(15),
                    bx + tw / 2f + dp(10), by + dp(6));
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x99000000);
            canvas.drawRoundRect(pill, dp(11), dp(11), p);
            canvas.drawText(t, bx - tw / 2f, by, textPaint);
        }

        if (!modeText.isEmpty()) {
            textPaint.setColor(0xFFFFD166);
            canvas.drawText(modeText, dp(14), dp(76), textPaint);
            textPaint.setColor(0xFFF5F5F7);
        }

        if (aeLock || afLock) {
            String t = (aeLock ? "AE-L " : "") + (afLock ? "AF-L" : "");
            float tw = textPaint.measureText(t);
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(0xCC000000);
            RectF r = new RectF(dp(12), dp(52), dp(12) + tw + dp(14), dp(72));
            canvas.drawRoundRect(r, dp(6), dp(6), bg);
            textPaint.setColor(0xFFFFD166);
            canvas.drawText(t, dp(18), dp(67), textPaint);
            textPaint.setColor(0xFFF5F5F7);
        }

        if (recording) {
            float x = dp(18);
            float y = dp(92);
            long t = SystemClock.uptimeMillis();
            boolean on = (t / 500) % 2 == 0;
            if (on) canvas.drawCircle(x + dp(5), y - dp(4), dp(5), recPaint);
            String txt = String.format(java.util.Locale.US, "%02d:%02d",
                    recordSeconds / 60, recordSeconds % 60);
            Paint mono = new Paint(Paint.ANTI_ALIAS_FLAG);
            mono.setColor(0xFFFFFFFF);
            mono.setTextSize(dp(13));
            mono.setFakeBoldText(true);
            canvas.drawText(txt, x + dp(16), y, mono);
        }

        if (countdown > 0) {
            Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
            big.setColor(0xFFFFD166);
            big.setTextSize(dp(92));
            big.setFakeBoldText(true);
            big.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(String.valueOf(countdown), w / 2f, h / 2f + dp(30), big);
        }

        if (focusX >= 0 && focusY >= 0) {
            long dt = SystemClock.uptimeMillis() - focusStart;
            if (dt < 900) {
                float p = dt / 900f;
                float size = dp(34) * (1f + 0.45f * p);
                int alpha = (int) (255 * (1f - p * p));
                focusPaint.setAlpha(alpha);
                RectF r = new RectF(focusX - size / 2, focusY - size / 2,
                        focusX + size / 2, focusY + size / 2);
                canvas.drawRoundRect(r, dp(6), dp(6), focusPaint);
                canvas.drawLine(focusX, focusY - dp(4), focusX, focusY + dp(4), focusPaint);
                canvas.drawLine(focusX - dp(4), focusY, focusX + dp(4), focusY, focusPaint);
                focusPaint.setAlpha(255);
                postInvalidateOnAnimation();
            } else {
                focusX = -1;
                focusY = -1;
            }
        }

        if (recording) postInvalidateOnAnimation();
    }

    private void drawLevel(Canvas canvas, int w, int h) {
        float cx = w / 2f;
        float cy = h / 2f;
        float len = Math.min(w, h) * 0.35f;
        canvas.save();
        canvas.rotate(levelAngle, cx, cy);
        boolean straight = Math.abs(levelAngle) < 1.0f;
        levelPaint.setColor(straight ? 0xCCFFD166 : 0x99FFFFFF);
        canvas.drawLine(cx - len, cy, cx - len * 0.18f, cy, levelPaint);
        canvas.drawLine(cx + len * 0.18f, cy, cx + len, cy, levelPaint);
        canvas.restore();
        levelPaint.setColor(0xCCFFD166);
        if (straight) {
            canvas.drawCircle(cx, cy + dp(26), dp(2.4f), levelPaint);
        }
    }

    private void drawHistogram(Canvas canvas, int w) {
        float boxW = dp(92);
        float boxH = dp(52);
        float left = w - boxW - dp(12);
        float top = dp(56);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0x8C000000);
        canvas.drawRoundRect(new RectF(left, top, left + boxW, top + boxH), dp(8), dp(8), bg);

        float innerLeft = left + dp(6);
        float innerTop = top + dp(5);
        float innerW = boxW - dp(12);
        float innerH = boxH - dp(10);
        drawChannel(canvas, histR, innerLeft, innerTop, innerW, innerH, 0xCCFF453A);
        drawChannel(canvas, histG, innerLeft, innerTop, innerW, innerH, 0xCC32D74B);
        drawChannel(canvas, histB, innerLeft, innerTop, innerW, innerH, 0xCC0A84FF);
    }

    private void drawChannel(Canvas canvas, int[] data, float left, float top,
                             float width, float height, int color) {
        if (data == null) return;
        histoPaint.setColor(color);
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(left, top + height);
        int bins = 256;
        for (int i = 0; i < bins; i++) {
            float x = left + width * i / (bins - 1f);
            float v = (float) Math.sqrt(data[i] / (double) histMax);
            float y = top + height - height * Math.min(1f, v);
            path.lineTo(x, y);
        }
        path.lineTo(left + width, top + height);
        path.close();
        int alpha = (color >>> 24) / 3;
        histoPaint.setStyle(Paint.Style.FILL);
        histoPaint.setColor((alpha << 24) | (color & 0x00FFFFFF));
        canvas.drawPath(path, histoPaint);
        histoPaint.setStyle(Paint.Style.STROKE);
        histoPaint.setColor(color);
        canvas.drawPath(path, histoPaint);
    }
}
