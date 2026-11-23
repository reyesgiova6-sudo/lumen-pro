package mx.lumen.camera;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.io.InputStream;

/** Visor a pantalla completa con zoom, compartir y borrar. */
public class ViewerActivity extends Activity {

    private Uri uri;
    private MediaStoreSaver saver;
    private ZoomView zoomView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        saver = new MediaStoreSaver(this);
        uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }

        String mime = saver.mime(uri);
        if (mime.startsWith("video")) {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, mime);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(view);
            } catch (Exception ignored) {
            }
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        zoomView = new ZoomView(this);
        root.addView(zoomView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding((int) dp(6), (int) dp(6), (int) dp(6), (int) dp(6));
        bar.setBackgroundColor(0xB3000000);

        IconView back = new IconView(this);
        back.setIcon(IconView.BACK);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams((int) dp(46), (int) dp(46)));

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        IconView share = new IconView(this);
        share.setIcon(IconView.SHARE);
        share.setOnClickListener(v -> saver.share(uri));
        bar.addView(share, new LinearLayout.LayoutParams((int) dp(46), (int) dp(46)));

        IconView delete = new IconView(this);
        delete.setIcon(IconView.DELETE);
        delete.setOnClickListener(v -> confirmDelete());
        bar.addView(delete, new LinearLayout.LayoutParams((int) dp(46), (int) dp(46)));

        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, (int) dp(58));
        barParams.gravity = Gravity.TOP;
        root.addView(bar, barParams);

        setContentView(root);
        load();
    }

    private void load() {
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                int orientation = 1;
                try (InputStream exifStream = getContentResolver().openInputStream(uri)) {
                    if (exifStream != null) orientation = Util.exifOrientation(exifStream);
                }
                BitmapFactory.Options probe = new BitmapFactory.Options();
                probe.inJustDecodeBounds = true;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(is, null, probe);
                }
                int sample = 1;
                while (Math.max(probe.outWidth, probe.outHeight) / sample > 3200) sample *= 2;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    bmp = BitmapFactory.decodeStream(is, null, opts);
                }
                bmp = Util.applyExif(bmp, orientation);
            } catch (Exception ignored) {
            }
            final Bitmap result = bmp;
            runOnUiThread(() -> {
                if (result != null) {
                    zoomView.setBitmap(result);
                } else {
                    finish();
                }
            });
        }).start();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Borrar archivo")
                .setMessage("¿Eliminar esta foto de la galería?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Borrar", (d, w) -> {
                    saver.delete(uri);
                    Prefs prefs = new Prefs(this);
                    if (uri.toString().equals(prefs.lastUri())) prefs.setLastUri(null);
                    finish();
                })
                .show();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** Vista con pinch-zoom, arrastre y doble toque. */
    static class ZoomView extends View {
        private Bitmap bitmap;
        private final Matrix matrix = new Matrix();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector gestures;
        private float baseScale = 1f;
        private float lastX;
        private float lastY;

        ZoomView(Activity context) {
            super(context);
            matrix.setScale(1, 1);
            scaleDetector = new ScaleGestureDetector(context,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector d) {
                            matrix.postScale(d.getScaleFactor(), d.getScaleFactor(),
                                    d.getFocusX(), d.getFocusY());
                            constrain();
                            invalidate();
                            return true;
                        }
                    });
            gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    float[] v = new float[9];
                    matrix.getValues(v);
                    if (v[Matrix.MSCALE_X] > baseScale * 1.2f) {
                        fit();
                    } else {
                        matrix.postScale(2.6f, 2.6f, e.getX(), e.getY());
                        constrain();
                        invalidate();
                    }
                    return true;
                }
            });
        }

        void setBitmap(Bitmap bmp) {
            bitmap = bmp;
            post(this::fit);
        }

        private void fit() {
            if (bitmap == null || getWidth() == 0) return;
            float sx = getWidth() / (float) bitmap.getWidth();
            float sy = getHeight() / (float) bitmap.getHeight();
            baseScale = Math.min(sx, sy);
            matrix.reset();
            matrix.postScale(baseScale, baseScale);
            matrix.postTranslate((getWidth() - bitmap.getWidth() * baseScale) / 2f,
                    (getHeight() - bitmap.getHeight() * baseScale) / 2f);
            invalidate();
        }

        private void constrain() {
            if (bitmap == null) return;
            float[] v = new float[9];
            matrix.getValues(v);
            float scale = v[Matrix.MSCALE_X];
            float minScale = baseScale;
            if (scale < minScale) {
                float f = minScale / scale;
                matrix.postScale(f, f, getWidth() / 2f, getHeight() / 2f);
                matrix.getValues(v);
            }
            float w = bitmap.getWidth() * v[Matrix.MSCALE_X];
            float h = bitmap.getHeight() * v[Matrix.MSCALE_Y];
            float dx = v[Matrix.MTRANS_X];
            float dy = v[Matrix.MTRANS_Y];
            float newDx = dx;
            float newDy = dy;
            if (w <= getWidth()) {
                newDx = (getWidth() - w) / 2f;
            } else {
                newDx = Math.min(0, Math.max(getWidth() - w, dx));
            }
            if (h <= getHeight()) {
                newDy = (getHeight() - h) / 2f;
            } else {
                newDy = Math.min(0, Math.max(getHeight() - h, dy));
            }
            matrix.postTranslate(newDx - dx, newDy - dy);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap != null) canvas.drawBitmap(bitmap, matrix, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            gestures.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastX = event.getX();
                lastY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                lastX = event.getX();
                lastY = event.getY();
                matrix.postTranslate(dx, dy);
                constrain();
                invalidate();
            }
            return true;
        }
    }
}
