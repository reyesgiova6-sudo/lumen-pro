package mx.lumen.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Iconos vectoriales dibujados a mano (sin dependencias) para la interfaz. */
public class IconView extends View {

    public static final int FLASH_OFF = 0;
    public static final int FLASH_AUTO = 1;
    public static final int FLASH_ON = 2;
    public static final int FLASH_TORCH = 3;
    public static final int TIMER = 4;
    public static final int SETTINGS = 5;
    public static final int FLIP = 6;
    public static final int GRID = 7;
    public static final int LEVEL = 8;
    public static final int HISTOGRAM = 9;
    public static final int RAW = 10;
    public static final int CLOSE = 11;
    public static final int SHARE = 12;
    public static final int DELETE = 13;
    public static final int GALLERY = 14;
    public static final int CHECK = 15;
    public static final int BACK = 16;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private int icon = FLASH_OFF;
    private boolean active = false;

    public IconView(Context context) {
        this(context, null);
    }

    public IconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.7f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        text.setFakeBoldText(true);
        text.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    public void setIcon(int icon) {
        this.icon = icon;
        invalidate();
    }

    public int icon() {
        return icon;
    }

    public void setActive(boolean active) {
        this.active = active;
        invalidate();
    }

    public boolean isActive() {
        return active;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float size = Math.min(w, h) * 0.62f;
        if (size <= 0) return;

        int color = active ? 0xFFFFD166 : 0xFFF5F5F7;
        if (!isEnabled()) color = 0xFF5A5A60;
        paint.setColor(color);
        paint.setStrokeWidth(1.8f);
        text.setColor(color);
        text.setTextSize(size * 0.30f);

        canvas.save();
        canvas.translate((w - size) / 2f, (h - size) / 2f);
        canvas.scale(size / 24f, size / 24f);
        drawIcon(canvas, icon);
        canvas.restore();
    }

    private void drawIcon(Canvas c, int ic) {
        Paint.Style oldStyle = paint.getStyle();
        switch (ic) {
            case FLASH_ON: {
                paint.setStyle(Paint.Style.FILL);
                Path p = new Path();
                p.moveTo(13.5f, 2f);
                p.lineTo(5f, 13.5f);
                p.lineTo(11f, 13.5f);
                p.lineTo(10f, 22f);
                p.lineTo(19f, 10.5f);
                p.lineTo(13f, 10.5f);
                p.close();
                c.drawPath(p, paint);
                break;
            }
            case FLASH_OFF: {
                Path p = new Path();
                p.moveTo(13.5f, 2f);
                p.lineTo(5f, 13.5f);
                p.lineTo(11f, 13.5f);
                p.lineTo(10f, 22f);
                p.lineTo(19f, 10.5f);
                p.lineTo(13f, 10.5f);
                p.close();
                c.drawPath(p, paint);
                c.drawLine(3, 3, 21, 21, paint);
                break;
            }
            case FLASH_AUTO: {
                Path p = new Path();
                p.moveTo(13.5f, 2f);
                p.lineTo(5f, 13.5f);
                p.lineTo(11f, 13.5f);
                p.lineTo(10f, 22f);
                p.lineTo(19f, 10.5f);
                p.lineTo(13f, 10.5f);
                p.close();
                c.drawPath(p, paint);
                text.setTextSize(7.5f);
                c.drawText("A", 6.5f, 21f, text);
                break;
            }
            case FLASH_TORCH: {
                paint.setStyle(Paint.Style.FILL);
                Path p = new Path();
                p.moveTo(13.5f, 2f);
                p.lineTo(5f, 13.5f);
                p.lineTo(11f, 13.5f);
                p.lineTo(10f, 22f);
                p.lineTo(19f, 10.5f);
                p.lineTo(13f, 10.5f);
                p.close();
                c.drawPath(p, paint);
                paint.setStyle(Paint.Style.STROKE);
                c.drawArc(new RectF(1.5f, 1.5f, 22.5f, 22.5f), -50, 100, false, paint);
                break;
            }
            case TIMER: {
                c.drawCircle(12f, 13.5f, 7.6f, paint);
                c.drawLine(12f, 13.5f, 12f, 9f, paint);
                c.drawLine(12f, 13.5f, 15f, 15.5f, paint);
                c.drawLine(10f, 2.5f, 14f, 2.5f, paint);
                c.drawLine(12f, 2.5f, 12f, 5.5f, paint);
                break;
            }
            case SETTINGS: {
                c.drawCircle(12f, 12f, 3.6f, paint);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8.0;
                    float x1 = (float) (12 + Math.cos(a) * 6.2);
                    float y1 = (float) (12 + Math.sin(a) * 6.2);
                    float x2 = (float) (12 + Math.cos(a) * 8.6);
                    float y2 = (float) (12 + Math.sin(a) * 8.6);
                    c.drawLine(x1, y1, x2, y2, paint);
                }
                break;
            }
            case FLIP: {
                RectF r1 = new RectF(4f, 5f, 20f, 15f);
                c.drawArc(r1, 200, 140, false, paint);
                c.drawLine(4.5f, 10f, 4.5f, 6f, paint);
                c.drawLine(4.5f, 10f, 8.5f, 10f, paint);
                RectF r2 = new RectF(4f, 9f, 20f, 19f);
                c.drawArc(r2, 20, 140, false, paint);
                c.drawLine(19.5f, 14f, 19.5f, 18f, paint);
                c.drawLine(19.5f, 14f, 15.5f, 14f, paint);
                break;
            }
            case GRID: {
                c.drawLine(8f, 3f, 8f, 21f, paint);
                c.drawLine(16f, 3f, 16f, 21f, paint);
                c.drawLine(3f, 8f, 21f, 8f, paint);
                c.drawLine(3f, 16f, 21f, 16f, paint);
                break;
            }
            case LEVEL: {
                c.drawLine(2f, 12f, 9f, 12f, paint);
                c.drawLine(15f, 12f, 22f, 12f, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(12f, 12f, 1.8f, paint);
                break;
            }
            case HISTOGRAM: {
                paint.setStyle(Paint.Style.FILL);
                float[] bars = {4f, 9f, 14f, 18f, 12f, 7f};
                for (int i = 0; i < bars.length; i++) {
                    c.drawRect(3.5f + i * 3f, 21f - bars[i], 5.6f + i * 3f, 21f, paint);
                }
                break;
            }
            case RAW: {
                text.setTextSize(8.4f);
                c.drawText("RAW", 12f, 15f, text);
                break;
            }
            case CLOSE: {
                c.drawLine(5f, 5f, 19f, 19f, paint);
                c.drawLine(19f, 5f, 5f, 19f, paint);
                break;
            }
            case SHARE: {
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(6f, 12f, 2.2f, paint);
                c.drawCircle(18f, 6.5f, 2.2f, paint);
                c.drawCircle(18f, 17.5f, 2.2f, paint);
                paint.setStyle(Paint.Style.STROKE);
                c.drawLine(7.9f, 11f, 16.2f, 7.4f, paint);
                c.drawLine(7.9f, 13f, 16.2f, 16.6f, paint);
                break;
            }
            case DELETE: {
                c.drawLine(4f, 6.5f, 20f, 6.5f, paint);
                c.drawLine(9.5f, 6.5f, 9.5f, 3.5f, paint);
                c.drawLine(14.5f, 6.5f, 14.5f, 3.5f, paint);
                c.drawLine(9.5f, 3.5f, 14.5f, 3.5f, paint);
                c.drawRect(6.5f, 6.5f, 17.5f, 21f, paint);
                c.drawLine(10.5f, 10f, 10.5f, 17.5f, paint);
                c.drawLine(13.5f, 10f, 13.5f, 17.5f, paint);
                break;
            }
            case GALLERY: {
                c.drawRoundRect(new RectF(3f, 5f, 21f, 19f), 2.2f, 2.2f, paint);
                Path p = new Path();
                p.moveTo(5f, 17f);
                p.lineTo(10f, 11.5f);
                p.lineTo(14f, 16f);
                p.lineTo(16.5f, 13.5f);
                p.lineTo(19f, 17f);
                c.drawPath(p, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(16.5f, 9f, 1.4f, paint);
                break;
            }
            case CHECK: {
                paint.setStrokeWidth(paint.getStrokeWidth() * 1.15f);
                c.drawLine(4.5f, 12.5f, 9.5f, 17.5f, paint);
                c.drawLine(9.5f, 17.5f, 19.5f, 6.5f, paint);
                break;
            }
            case BACK: {
                c.drawLine(14.5f, 5f, 7.5f, 12f, paint);
                c.drawLine(7.5f, 12f, 14.5f, 19f, paint);
                break;
            }
            default:
                break;
        }
        paint.setStyle(oldStyle);
    }
}
