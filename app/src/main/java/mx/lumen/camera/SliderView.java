package mx.lumen.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Slider horizontal estilo cámara profesional: etiqueta, pista, valor y topes. */
public class SliderView extends View {

    public interface OnChange {
        void onChange(SliderView view, int index, float value);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private String label = "";
    private String[] values = new String[0];
    private float[] numeric = new float[0];
    private boolean[] enabledStops = new boolean[0];
    private int index = 0;
    private int manualIndex = -1; // índice que representa "AUTO"
    private OnChange listener;
    private boolean usable = true;

    public SliderView(Context context) {
        this(context, null);
    }

    public SliderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        text.setColor(0xFFF5F5F7);
        text.setTextSize(dp(11));
        text.setFakeBoldText(true);
        setClickable(true);
    }

    private float dp(float v) {
        return v * density;
    }

    public void configure(String label, String[] values, float[] numeric, int autoIndex,
                          int initialIndex) {
        this.label = label;
        this.values = values;
        this.numeric = numeric;
        this.manualIndex = autoIndex;
        this.index = Util.clamp(initialIndex, 0, Math.max(0, values.length - 1));
        this.enabledStops = new boolean[values.length];
        for (int i = 0; i < enabledStops.length; i++) enabledStops[i] = true;
        invalidate();
    }

    public void setStopEnabled(int i, boolean enabled) {
        if (i >= 0 && i < enabledStops.length) {
            enabledStops[i] = enabled;
            if (i == index && !enabled) {
                index = autoIndexOrFirst();
            }
            invalidate();
        }
    }

    private int autoIndexOrFirst() {
        if (manualIndex >= 0) return manualIndex;
        for (int i = 0; i < enabledStops.length; i++) if (enabledStops[i]) return i;
        return 0;
    }

    public void setUsable(boolean usable) {
        this.usable = usable;
        invalidate();
    }

    public boolean isAuto() {
        return manualIndex >= 0 && index == manualIndex;
    }

    public int index() {
        return index;
    }

    public float value() {
        if (index < 0 || index >= numeric.length) return 0;
        return numeric[index];
    }

    public int getStopsCount() {
        return values.length;
    }

    public float getStopValue(int i) {
        if (i < 0 || i >= numeric.length) return -1;
        return numeric[i];
    }

    public void setIndex(int i) {
        index = Util.clamp(i, 0, Math.max(0, values.length - 1));
        invalidate();
    }

    public void setOnChange(OnChange l) {
        listener = l;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float pad = dp(6);
        float labelW = dp(30);
        float valueW = dp(54);

        text.setColor(usable ? 0xFF9A9AA0 : 0xFF4A4A4E);
        text.setTextSize(dp(9));
        canvas.drawText(label, pad, h / 2f + dp(3), text);

        float trackLeft = pad + labelW;
        float trackRight = w - pad - valueW;
        float cy = h / 2f;
        float trackH = dp(3);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33FFFFFF);
        canvas.drawRoundRect(new RectF(trackLeft, cy - trackH / 2, trackRight, cy + trackH / 2),
                trackH, trackH, paint);

        if (values.length == 0) return;

        float frac = values.length == 1 ? 0 : index / (float) (values.length - 1);
        float x = trackLeft + frac * (trackRight - trackLeft);

        boolean auto = isAuto();
        paint.setColor(usable ? (auto ? 0xFFFFD166 : 0xCCFFFFFF) : 0xFF4A4A4E);
        canvas.drawRoundRect(new RectF(trackLeft, cy - trackH / 2, x, cy + trackH / 2),
                trackH, trackH, paint);

        // Marcas de cada paso
        paint.setColor(0x55FFFFFF);
        float tickR = dp(0.8f);
        for (int i = 0; i < values.length; i++) {
            float tx = trackLeft + (values.length == 1 ? 0
                    : i / (float) (values.length - 1)) * (trackRight - trackLeft);
            if (Math.abs(tx - x) < dp(3)) continue;
            canvas.drawCircle(tx, cy, tickR, paint);
        }

        // Pulgar
        paint.setColor(usable ? 0xFFFFD166 : 0xFF4A4A4E);
        canvas.drawCircle(x, cy, dp(7), paint);
        paint.setColor(0xFF111114);
        canvas.drawCircle(x, cy, dp(3), paint);

        text.setColor(usable ? 0xFFFFD166 : 0xFF4A4A4E);
        text.setTextSize(dp(11));
        text.setTextAlign(Paint.Align.RIGHT);
        String v = index < values.length ? values[index] : "";
        canvas.drawText(v, w - pad, cy + dp(4), text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!usable) return false;
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                getParent().requestDisallowInterceptTouchEvent(true);
                float pad = dp(6);
                float labelW = dp(30);
                float valueW = dp(54);
                float trackLeft = pad + labelW;
                float trackRight = getWidth() - pad - valueW;
                float frac = Util.clamp((event.getX() - trackLeft)
                        / Math.max(1f, trackRight - trackLeft), 0f, 1f);
                int newIndex = Math.round(frac * (values.length - 1));
                if (newIndex < 0) newIndex = 0;
                if (newIndex >= values.length) newIndex = values.length - 1;
                if (newIndex != index) {
                    index = newIndex;
                    invalidate();
                    if (listener != null) listener.onChange(this, index, value());
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
