package mx.lumen.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Botón de disparo de doble anillo, con modo vídeo y animación de pulsación. */
public class ShutterButton extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private boolean videoMode = false;
    private boolean recording = false;
    private float press = 0f;
    private long pressStart = 0;

    public ShutterButton(Context context) {
        this(context, null);
    }

    public ShutterButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(2.5f));
        setClickable(true);
    }

    private float dp(float v) {
        return v * density;
    }

    public void setVideoMode(boolean video) {
        videoMode = video;
        invalidate();
    }

    public void setRecording(boolean rec) {
        recording = rec;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float outer = Math.min(w, h) / 2f - dp(4);
        float inner = outer - dp(9) - press * dp(2);

        paint.setColor(0x55FFFFFF);
        canvas.drawCircle(cx, cy, outer, paint);

        ring.setColor(0x88FFFFFF);
        canvas.drawCircle(cx, cy, outer - dp(1.2f), ring);

        if (recording) {
            paint.setColor(0xFFFF453A);
            float r = inner * 0.42f;
            canvas.drawRoundRect(new RectF(cx - r, cy - r, cx + r, cy + r), dp(6), dp(6), paint);
            long t = SystemClock.uptimeMillis();
            if ((t / 600) % 2 == 0) {
                ring.setColor(0x66FF453A);
                ring.setStrokeWidth(dp(6));
                canvas.drawCircle(cx, cy, outer - dp(2), ring);
                ring.setStrokeWidth(dp(2.5f));
            }
            postInvalidateOnAnimation();
        } else if (videoMode) {
            paint.setColor(0xFFFF453A);
            canvas.drawCircle(cx, cy, inner, paint);
            ring.setColor(0xFFFF453A);
            canvas.drawCircle(cx, cy, outer - dp(1.2f), ring);
        } else {
            paint.setColor(0xFFF7F7FA);
            canvas.drawCircle(cx, cy, inner, paint);
            paint.setColor(0x22000000);
            canvas.drawCircle(cx, cy, inner - dp(1.5f), paint);
            paint.setColor(0xFFF7F7FA);
            canvas.drawCircle(cx, cy, inner - dp(2.2f), paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                press = 1f;
                pressStart = SystemClock.uptimeMillis();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                press = 0f;
                invalidate();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                press = 0f;
                invalidate();
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
