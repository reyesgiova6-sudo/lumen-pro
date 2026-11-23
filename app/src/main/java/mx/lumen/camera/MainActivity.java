package mx.lumen.camera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements CameraEngine.Listener,
        TextureView.SurfaceTextureListener {

    private static final int REQ_PERMS = 41;
    private static final String[] MODE_NAMES = {"AUTO", "PRO", "HDR", "NOCHE", "VÍDEO"};

    private Prefs prefs;
    private CameraEngine engine;
    private CameraEngine.Caps caps;
    private MediaStoreSaver saver;
    private MediaActionSound sounds;

    private TextureView preview;
    private OverlayView overlay;
    private ShutterButton shutter;
    private ImageView thumbnail;
    private LinearLayout modeStrip;
    private LinearLayout manualPanel;
    private LinearLayout processing;
    private TextView statusText;
    private TextView hintText;
    private TextView timerBadge;
    private TextView wbInfo;
    private TextView processingTitle;
    private TextView processingDetail;
    private TextView btnLockAe;
    private TextView btnResetManual;
    private IconView btnFlash;
    private IconView btnTimer;
    private IconView btnHist;
    private IconView btnSettings;
    private IconView btnFlip;
    private ProgressBar procBar;
    private View flashOverlay;

    private SliderView sliderIso;
    private SliderView sliderShutter;
    private SliderView sliderFocus;
    private SliderView sliderWb;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int mode = Prefs.MODE_AUTO;
    private boolean processingShot;
    private boolean slidersBuilt;
    private boolean recording;
    private int recordSeconds;
    private MediaStoreSaver.VideoTarget videoTarget;
    private android.content.BroadcastReceiver debugReceiver;

    private float zoomTarget = 1f;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private float levelFiltered;
    private boolean sensorRegistered;

    private boolean countdownRunning;
    private Runnable countdownRunnable;

    private int lastHistoPct = -1;

    // ------------------------------------------------------------------- life

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setImmersive();
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        saver = new MediaStoreSaver(this);
        sounds = new MediaActionSound();
        mode = prefs.mode();

        bindViews();
        buildModeChips();
        setupListeners();
        setupGestures();
        applyPrefsToUi();
        loadThumbnail();

        preview.setSurfaceTextureListener(this);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        requestNeededPermissions();
        if (BuildConfig.DEBUG) registerDebugReceiver();
    }

    /** Canal de pruebas por ADB (solo compilaciones debug). */
    private void registerDebugReceiver() {
        debugReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                if ("mx.lumen.camera.CAPTURE".equals(action)) {
                    setMode(intent.getIntExtra("mode", mode));
                    ui.postDelayed(MainActivity.this::onShutterPressed, 800);
                } else if ("mx.lumen.camera.ROTATE".equals(action)) {
                    if (engine != null) engine.setRotationOffset(intent.getIntExtra("deg", 0));
                } else if ("mx.lumen.camera.VIDEO_START".equals(action)) {
                    setMode(Prefs.MODE_VIDEO);
                    ui.postDelayed(MainActivity.this::toggleRecording, 600);
                } else if ("mx.lumen.camera.VIDEO_STOP".equals(action)) {
                    if (engine != null) engine.stopRecording();
                } else if ("mx.lumen.camera.RESTART".equals(action)) {
                    restartCamera();
                }
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction("mx.lumen.camera.CAPTURE");
        filter.addAction("mx.lumen.camera.ROTATE");
        filter.addAction("mx.lumen.camera.VIDEO_START");
        filter.addAction("mx.lumen.camera.VIDEO_STOP");
        filter.addAction("mx.lumen.camera.RESTART");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(debugReceiver, filter, android.content.Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(debugReceiver, filter);
        }
    }

    private void bindViews() {
        preview = findViewById(R.id.preview);
        overlay = findViewById(R.id.overlay);
        shutter = findViewById(R.id.shutter);
        thumbnail = findViewById(R.id.thumbnail);
        modeStrip = findViewById(R.id.modeStrip);
        manualPanel = findViewById(R.id.manualPanel);
        processing = findViewById(R.id.processing);
        statusText = findViewById(R.id.statusText);
        hintText = findViewById(R.id.hintText);
        timerBadge = findViewById(R.id.timerBadge);
        wbInfo = findViewById(R.id.wbInfo);
        processingTitle = findViewById(R.id.processingTitle);
        processingDetail = findViewById(R.id.processingDetail);
        btnLockAe = findViewById(R.id.btnLockAe);
        btnResetManual = findViewById(R.id.btnResetManual);
        btnFlash = findViewById(R.id.btnFlash);
        btnTimer = findViewById(R.id.btnTimer);
        btnHist = findViewById(R.id.btnHist);
        btnSettings = findViewById(R.id.btnSettings);
        btnFlip = findViewById(R.id.btnFlip);
        procBar = findViewById(R.id.procBar);
        flashOverlay = findViewById(R.id.flashOverlay);
        sliderIso = findViewById(R.id.sliderIso);
        sliderShutter = findViewById(R.id.sliderShutter);
        sliderFocus = findViewById(R.id.sliderFocus);
        sliderWb = findViewById(R.id.sliderWb);

        thumbnail.setClipToOutline(true);
        thumbnail.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
            }
        });
    }

    private void setImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setImmersive();
        if (hasCameraPermission()) {
            startCamera();
        }
        if (sensorManager != null && accelerometer != null && !sensorRegistered) {
            sensorManager.registerListener(levelListener, accelerometer,
                    SensorManager.SENSOR_DELAY_UI);
            sensorRegistered = true;
        }
        ui.post(histogramLoop);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCountdown();
        if (recording) engine.stopRecording();
        if (engine != null) engine.close();
        if (sensorManager != null && sensorRegistered) {
            sensorManager.unregisterListener(levelListener);
            sensorRegistered = false;
        }
        ui.removeCallbacks(histogramLoop);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (debugReceiver != null) {
            try {
                unregisterReceiver(debugReceiver);
            } catch (Exception ignored) {
            }
            debugReceiver = null;
        }
        if (engine != null) engine.shutdown();
        engine = null;
        worker.shutdownNow();
        if (sounds != null) sounds.release();
    }

    @Override
    public void onBackPressed() {
        if (recording) {
            engine.stopRecording();
            return;
        }
        super.onBackPressed();
    }

    // -------------------------------------------------------------- permissions

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        if (!hasCameraPermission()) {
            new AlertDialog.Builder(this)
                    .setTitle("Permiso de cámara")
                    .setMessage("LUMEN Pro necesita la cámara para funcionar. Actívalo en "
                            + "Ajustes › Aplicaciones › LUMEN Pro › Permisos.")
                    .setPositiveButton("Entendido", null)
                    .show();
            return;
        }
        if (engine == null) startCamera();
    }

    // ----------------------------------------------------------------- camera

    private void startCamera() {
        if (preview == null) return;
        if (engine == null) {
            engine = new CameraEngine(this, preview, this);
        }
        engine.setMaxJpegMp(prefs.resolutionMp());
        engine.setDisplayRotation(displayRotation());
        engine.setManualActive(mode == Prefs.MODE_PRO);
        engine.setFlashMode(prefs.flashMode());
        engine.setPreviewRotationOverride(prefs.previewRotation());
        engine.setJpegAspect(prefs.aspect169());
        engine.setVideoStabilizationEnabled(prefs.videoStabilization());
        engine.setVideoFps(prefs.videoFps());
        engine.setVideoMode(mode == Prefs.MODE_VIDEO);
        zoomTarget = 1f;
        overlay.setZoomText("");
        engine.open(prefs.useFront());
        overlay.setLevelSupported(accelerometer != null);
    }

    private int displayRotation() {
        int r = getWindowManager().getDefaultDisplay().getRotation();
        switch (r) {
            case android.view.Surface.ROTATION_90:
                return 90;
            case android.view.Surface.ROTATION_180:
                return 180;
            case android.view.Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    @Override
    public void onCameraReady(CameraEngine.Caps caps) {
        this.caps = caps;
        ui.post(() -> {
            if (!slidersBuilt) {
                buildSliders(caps);
                slidersBuilt = true;
            }
            applyFlashIcon();
            btnFlash.setEnabled(caps.flash);
            btnFlip.setEnabled(hasFrontCamera());
            updateStatusText();
            hint("Cámara lista · " + (caps.hardwareLevel >= 3 ? "PRO" : "estándar"));
        });
    }

    @Override
    public void onCameraFailed(String message) {
        ui.post(() -> hint(message));
    }

    @Override
    public void onCameraHint(String message) {
        ui.post(() -> hint(message));
    }

    @Override
    public void onVideoState(boolean rec, boolean ok) {
        ui.post(() -> {
            recording = rec;
            shutter.setRecording(rec);
            overlay.setRecording(rec, rec ? 0 : 0);
            if (rec) {
                recordSeconds = 0;
                ui.post(recordTick);
                if (sounds != null) sounds.play(MediaActionSound.START_VIDEO_RECORDING);
            } else {
                ui.removeCallbacks(recordTick);
                if (sounds != null) sounds.play(MediaActionSound.STOP_VIDEO_RECORDING);
                if (videoTarget != null) {
                    if (ok) {
                        saver.finishVideo(videoTarget);
                        updateThumbnail(videoTarget.uri);
                        hint("Vídeo guardado en Movies/LUMEN");
                    } else {
                        saver.delete(videoTarget.uri);
                        hint("El codificador falló: vídeo descartado. Prueba 1080p o H.264.");
                    }
                    videoTarget = null;
                }
            }
        });
    }

    private final Runnable recordTick = new Runnable() {
        @Override
        public void run() {
            if (!recording) return;
            recordSeconds++;
            overlay.setRecording(true, recordSeconds);
            ui.postDelayed(this, 1000);
        }
    };

    // ----------------------------------------------------------------- sliders

    private void buildSliders(CameraEngine.Caps caps) {
        int autoIsoIndex = 0;
        List<Integer> isos = new ArrayList<>();
        isos.add(-1);
        int[] isoStops = {50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
                1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 25600};
        for (int v : isoStops) {
            if (v >= caps.isoRange.getLower() && v <= caps.isoRange.getUpper()) isos.add(v);
        }
        if (isos.size() == 1) {
            isos.add(caps.isoRange.getLower());
            isos.add(caps.isoRange.getUpper());
        }
        String[] isoLabels = new String[isos.size()];
        float[] isoNums = new float[isos.size()];
        for (int i = 0; i < isos.size(); i++) {
            int v = isos.get(i);
            isoNums[i] = v;
            isoLabels[i] = v < 0 ? "AUTO" : String.valueOf(v);
        }
        sliderIso.configure("ISO", isoLabels, isoNums, autoIsoIndex, 0);

        long[] shutterStops = {
                1_000_000_000L / 8000, 1_000_000_000L / 6400, 1_000_000_000L / 5000,
                1_000_000_000L / 4000, 1_000_000_000L / 3200, 1_000_000_000L / 2500,
                1_000_000_000L / 2000, 1_000_000_000L / 1600, 1_000_000_000L / 1250,
                1_000_000_000L / 1000, 1_000_000_000L / 800, 1_000_000_000L / 640,
                1_000_000_000L / 500, 1_000_000_000L / 400, 1_000_000_000L / 320,
                1_000_000_000L / 250, 1_000_000_000L / 200, 1_000_000_000L / 160,
                1_000_000_000L / 125, 1_000_000_000L / 100, 1_000_000_000L / 80,
                1_000_000_000L / 60, 1_000_000_000L / 50, 1_000_000_000L / 40,
                1_000_000_000L / 30, 1_000_000_000L / 25, 1_000_000_000L / 20,
                1_000_000_000L / 15, 1_000_000_000L / 13, 1_000_000_000L / 10,
                1_000_000_000L / 8, 1_000_000_000L / 6, 1_000_000_000L / 5,
                1_000_000_000L / 4, 400_000_000L, 500_000_000L, 800_000_000L,
                1_000_000_000L, 1_300_000_000L, 2_000_000_000L, 3_000_000_000L,
                4_000_000_000L, 6_000_000_000L, 8_000_000_000L, 10_000_000_000L,
                15_000_000_000L, 20_000_000_000L, 30_000_000_000L};
        List<Long> shutters = new ArrayList<>();
        shutters.add(-1L);
        for (long v : shutterStops) {
            if (v >= caps.shutterRange.getLower() && v <= caps.shutterRange.getUpper()) {
                shutters.add(v);
            }
        }
        if (shutters.size() == 1) {
            shutters.add(caps.shutterRange.getLower());
            shutters.add(caps.shutterRange.getUpper());
        }
        String[] shutterLabels = new String[shutters.size()];
        float[] shutterNums = new float[shutters.size()];
        for (int i = 0; i < shutters.size(); i++) {
            long v = shutters.get(i);
            shutterNums[i] = v;
            shutterLabels[i] = v < 0 ? "AUTO" : Util.shutterShort(v);
        }
        sliderShutter.configure("VEL", shutterLabels, shutterNums, 0, 0);

        float maxD = Math.max(0.01f, caps.minFocusDistance);
        int focusSteps = 24;
        String[] focusLabels = new String[focusSteps + 1];
        float[] focusNums = new float[focusSteps + 1];
        focusLabels[0] = "AUTO";
        focusNums[0] = -1f;
        for (int i = 0; i < focusSteps; i++) {
            float diopters = maxD * (1f - i / (float) (focusSteps - 1));
            float meters = diopters <= 0.001f ? 999f : 1f / diopters;
            focusNums[i + 1] = diopters;
            if (meters > 20f) focusLabels[i + 1] = "∞";
            else if (meters >= 1f) focusLabels[i + 1] = String.format(Locale.US, "%.1fm", meters);
            else focusLabels[i + 1] = String.format(Locale.US, "%.2fm", meters);
        }
        sliderFocus.configure("ENF", focusLabels, focusNums, 0, 0);

        int[] wbStops = {2300, 2500, 2800, 3000, 3200, 3600, 4000, 4500, 5000, 5500,
                6000, 6500, 7000, 7500, 8000, 8500, 9000, 9500, 10000};
        List<Integer> wbs = new ArrayList<>();
        wbs.add(-1);
        for (int v : wbStops) wbs.add(v);
        String[] wbLabels = new String[wbs.size()];
        float[] wbNums = new float[wbs.size()];
        for (int i = 0; i < wbs.size(); i++) {
            int v = wbs.get(i);
            wbNums[i] = v;
            wbLabels[i] = v < 0 ? "AUTO" : v + "K";
        }
        sliderWb.configure("WB", wbLabels, wbNums, 0, 0);

        boolean manual = caps.manualSensor;
        sliderIso.setUsable(manual);
        sliderShutter.setUsable(manual);
        sliderFocus.setUsable(caps.manualFocus);
        sliderWb.setUsable(true);
        if (!manual) {
            hint("Este dispositivo no expone ISO/obturación manuales (modo PRO limitado)");
        }
        updateManualInfo();
    }

    private void setupListeners() {
        SliderView.OnChange onChange = (view, index, value) -> {
            applyManual();
            updateManualInfo();
        };
        sliderIso.setOnChange(onChange);
        sliderShutter.setOnChange(onChange);
        sliderFocus.setOnChange(onChange);
        sliderWb.setOnChange(onChange);

        shutter.setOnClickListener(v -> onShutterPressed());

        thumbnail.setOnClickListener(v -> {
            String last = prefs.lastUri();
            if (last != null) openViewer(Uri.parse(last));
        });

        btnFlash.setOnClickListener(v -> cycleFlash());
        btnTimer.setOnClickListener(v -> cycleTimer());
        btnHist.setOnClickListener(v -> {
            prefs.setHistogram(!prefs.histogram());
            overlay.setHistogramEnabled(prefs.histogram());
            btnHist.setActive(prefs.histogram());
        });
        btnSettings.setOnClickListener(v -> SettingsDialog.show(this, prefs, caps,
                key -> {
                    if ("resolution".equals(key) || "aspect".equals(key)) {
                        restartCamera();
                    }
                    if ("rotation".equals(key) && engine != null) {
                        engine.setPreviewRotationOverride(prefs.previewRotation());
                    }
                    if ("grid".equals(key)) overlay.setGrid(prefs.grid());
                    if ("level".equals(key)) overlay.setLevelEnabled(prefs.level());
                    if ("histogram".equals(key)) {
                        overlay.setHistogramEnabled(prefs.histogram());
                        btnHist.setActive(prefs.histogram());
                    }
                    updateStatusText();
                }));
        btnFlip.setOnClickListener(v -> {
            if (recording) {
                hint("Detén la grabación antes de cambiar de cámara");
                return;
            }
            prefs.setUseFront(!prefs.useFront());
            restartCamera();
        });
        btnLockAe.setOnClickListener(v -> lockExposure());
        btnResetManual.setOnClickListener(v -> {
            sliderIso.setIndex(0);
            sliderShutter.setIndex(0);
            sliderFocus.setIndex(0);
            sliderWb.setIndex(0);
            applyManual();
            updateManualInfo();
        });

        applyFlashIcon();
        btnTimer.setIcon(IconView.TIMER);
        btnTimer.setActive(prefs.timer() > 0);
        btnHist.setIcon(IconView.HISTOGRAM);
        btnHist.setActive(prefs.histogram());
        btnSettings.setIcon(IconView.SETTINGS);
        btnFlip.setIcon(IconView.FLIP);
        overlay.setZoomText("");
    }

    private void setupGestures() {
        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float max = caps == null ? 4f : Math.max(1f, caps.maxZoom);
                        zoomTarget = Util.clamp(zoomTarget * detector.getScaleFactor(), 1f, max);
                        ui.removeCallbacks(applyZoomRunnable);
                        ui.postDelayed(applyZoomRunnable, 40);
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        ui.removeCallbacks(applyZoomRunnable);
                        applyZoomRunnable.run();
                    }
                });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                overlay.showFocus(e.getX(), e.getY());
                if (engine != null) {
                    engine.tapFocus(e.getX() / Math.max(1, preview.getWidth()),
                            e.getY() / Math.max(1, preview.getHeight()));
                }
                if (sounds != null) sounds.play(MediaActionSound.FOCUS_COMPLETE);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float max = caps == null ? 2f : Math.max(1f, caps.maxZoom);
                zoomTarget = zoomTarget > 1.1f ? 1f : Math.min(2f, max);
                applyZoomRunnable.run();
                return true;
            }
        });

        preview.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private final Runnable applyZoomRunnable = () -> {
        if (engine == null) return;
        engine.setZoom(zoomTarget);
        if (zoomTarget > 1.05f) {
            overlay.setZoomText(String.format(Locale.US, "%.1f×", zoomTarget));
        } else {
            overlay.setZoomText("");
        }
    };

    // ------------------------------------------------------------------- modes

    private void buildModeChips() {
        modeStrip.removeAllViews();
        for (int i = 0; i < MODE_NAMES.length; i++) {
            final int index = i;
            TextView chip = new TextView(this);
            chip.setText(MODE_NAMES[i]);
            chip.setTextSize(11f);
            chip.setLetterSpacing(0.16f);
            chip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            int ph = (int) dp(13);
            int pv = (int) dp(6);
            chip.setPadding(ph, pv, ph, pv);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.rightMargin = (int) dp(5);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> setMode(index));
            modeStrip.addView(chip);
        }
        updateModeChips();
    }

    private void updateModeChips() {
        if (modeStrip == null) return;
        for (int i = 0; i < modeStrip.getChildCount(); i++) {
            TextView chip = (TextView) modeStrip.getChildAt(i);
            boolean selected = i == mode;
            chip.setBackgroundResource(selected ? R.drawable.bg_chip_selected
                    : R.drawable.bg_chip_normal);
            chip.setTextColor(selected ? 0xFF111114 : 0xFFF5F5F7);
            chip.setAlpha(selected ? 1f : 0.85f);
        }
    }

    private void setMode(int newMode) {
        if (recording && newMode != Prefs.MODE_VIDEO) {
            hint("Detén la grabación para cambiar de modo");
            return;
        }
        mode = newMode;
        prefs.setMode(newMode);
        updateModeChips();
        manualPanel.setVisibility(newMode == Prefs.MODE_PRO ? View.VISIBLE : View.GONE);
        shutter.setVideoMode(newMode == Prefs.MODE_VIDEO);
        if (engine != null) {
            engine.setManualActive(newMode == Prefs.MODE_PRO);
            engine.setVideoMode(newMode == Prefs.MODE_VIDEO);
        }
        applyModeText();
        updateStatusText();
    }

    private void applyModeText() {
        String text;
        switch (mode) {
            case Prefs.MODE_PRO:
                text = "PRO · control total";
                break;
            case Prefs.MODE_HDR:
                text = "HDR · " + prefs.hdrFrames() + " tomas";
                break;
            case Prefs.MODE_NIGHT:
                text = "NOCHE · " + prefs.nightFrames() + " tomas";
                break;
            case Prefs.MODE_VIDEO:
                text = "VÍDEO · " + prefs.videoHeight() + "p";
                break;
            default:
                text = "AUTO";
                break;
        }
        overlay.setModeText(text);
    }

    private void applyPrefsToUi() {
        overlay.setGrid(prefs.grid());
        overlay.setLevelEnabled(prefs.level());
        overlay.setHistogramEnabled(prefs.histogram());
        btnHist.setActive(prefs.histogram());
        manualPanel.setVisibility(mode == Prefs.MODE_PRO ? View.VISIBLE : View.GONE);
        shutter.setVideoMode(mode == Prefs.MODE_VIDEO);
        updateTimerBadge();
        applyModeText();
        updateStatusText();
    }

    // ----------------------------------------------------------------- capture

    private void onShutterPressed() {
        if (engine == null) {
            hint("La cámara aún no está lista");
            return;
        }
        if (mode == Prefs.MODE_VIDEO) {
            toggleRecording();
            return;
        }
        if (processingShot) {
            hint("Procesando la toma anterior…");
            return;
        }
        int timer = prefs.timer();
        if (timer > 0) {
            startCountdown(timer);
        } else {
            doCapture();
        }
    }

    private void startCountdown(int seconds) {
        if (countdownRunning) return;
        countdownRunning = true;
        final int[] remaining = {seconds};
        overlay.setCountdown(remaining[0]);
        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                remaining[0]--;
                overlay.setCountdown(Math.max(0, remaining[0]));
                if (remaining[0] > 0) {
                    ui.postDelayed(this, 1000);
                } else {
                    countdownRunning = false;
                    overlay.setCountdown(0);
                    doCapture();
                }
            }
        };
        ui.postDelayed(countdownRunnable, 1000);
    }

    private void stopCountdown() {
        if (countdownRunnable != null) ui.removeCallbacks(countdownRunnable);
        countdownRunning = false;
        overlay.setCountdown(0);
    }

    private void doCapture() {
        int frames;
        boolean bracket = false;
        boolean night = false;
        switch (mode) {
            case Prefs.MODE_HDR:
                frames = prefs.hdrFrames();
                bracket = true;
                break;
            case Prefs.MODE_NIGHT:
                frames = prefs.nightFrames();
                night = true;
                break;
            default:
                frames = 1;
                break;
        }
        if (sounds != null && prefs.shutterSound()) sounds.play(MediaActionSound.SHUTTER_CLICK);
        flashScreen();
        engine.capture(frames, bracket, night, prefs.saveRaw() && mode != Prefs.MODE_NIGHT);
    }

    private void flashScreen() {
        flashOverlay.animate().cancel();
        flashOverlay.setAlpha(0.65f);
        flashOverlay.animate().alpha(0f).setDuration(220).start();
    }

    @Override
    public void onBurstReady(List<byte[]> frames, byte[] dng, CameraEngine.ShotInfo info) {
        ui.post(() -> handleShot(frames, dng, info));
    }

    private void handleShot(List<byte[]> frames, byte[] dng, CameraEngine.ShotInfo info) {
        if (frames == null || frames.isEmpty()) return;
        final String stamp = Util.fileStamp();
        final boolean needsMerge = mode == Prefs.MODE_HDR || mode == Prefs.MODE_NIGHT
                || frames.size() > 1;

        if (!needsMerge) {
            worker.execute(() -> {
                try {
                    Uri uri;
                    boolean reprocess = (info != null && info.front && prefs.mirrorFront())
                            || prefs.autoPolish();
                    if (reprocess) {
                        Bitmap bmp = BitmapFactory.decodeByteArray(frames.get(0), 0,
                                frames.get(0).length);
                        if (bmp != null) {
                            if (prefs.autoPolish()) {
                                Bitmap polished = ImageFusion.polish(bmp, 0.18f);
                                if (polished != null && polished != bmp) bmp.recycle();
                                if (polished != null) bmp = polished;
                            }
                            int rot = Util.exifRotation(frames.get(0));
                            if (rot != 0) bmp = Util.rotateBitmap(bmp, rot);
                            if (info.front && prefs.mirrorFront()) bmp = Util.mirrorBitmap(bmp);
                            uri = saver.saveJpeg(bmp, "LUMEN_" + stamp + ".jpg", info,
                                    prefs.jpegQuality());
                            bmp.recycle();
                        } else {
                            uri = saver.saveJpeg(frames.get(0), "LUMEN_" + stamp + ".jpg");
                        }
                    } else {
                        uri = saver.saveJpeg(frames.get(0), "LUMEN_" + stamp + ".jpg");
                    }
                    if (dng != null) {
                        saver.saveDng(dng, "LUMEN_" + stamp + ".dng");
                    }
                    final Uri saved = uri;
                    ui.post(() -> {
                        updateThumbnail(saved);
                        hint(prefs.saveRaw() && dng != null
                                ? "Guardado · JPEG + DNG" : "Guardado · JPEG");
                    });
                } catch (Exception e) {
                    Log.w("Lumen", "guardar: " + e);
                    ui.post(() -> hint("No se pudo guardar: " + e.getMessage()));
                }
            });
            return;
        }

        processingShot = true;
        processingTitle.setText(mode == Prefs.MODE_NIGHT ? "REDUCIENDO RUIDO" : "FUSIÓN HDR");
        processingDetail.setText(frames.size() + " tomas · alineando…");
        procBar.setProgress(0);
        processing.setVisibility(View.VISIBLE);
        lastHistoPct = -1;

        final int capturedMode = mode;
        worker.execute(() -> {
            Bitmap result = null;
            try {
                List<Bitmap> bitmaps = decodeFrames(frames, prefs.mergeMp());
                if (bitmaps.isEmpty()) throw new Exception("No se pudieron decodificar las tomas");
                ImageFusion.Progress progress = pct -> {
                    if (pct - lastHistoPct >= 5 || pct == 100) {
                        lastHistoPct = pct;
                        ui.post(() -> {
                            procBar.setProgress(pct);
                            if (pct < 45) {
                                processingDetail.setText(frames.size() + " tomas · alineando…");
                            } else if (pct < 80) {
                                processingDetail.setText(capturedMode == Prefs.MODE_NIGHT
                                        ? "Apilando y limpiando ruido…" : "Fusionando exposiciones…");
                            } else {
                                processingDetail.setText("Afinando detalle y color…");
                            }
                        });
                    }
                };
                result = capturedMode == Prefs.MODE_NIGHT
                        ? ImageFusion.stackNight(bitmaps, progress)
                        : ImageFusion.fuseHdr(bitmaps, progress);
                if (result == null) throw new Exception("Fusión fallida");
                for (Bitmap b : bitmaps) if (!b.isRecycled()) b.recycle();

                Bitmap upright = result;
                int exifRot = Util.exifRotation(frames.get(0));
                if (exifRot != 0) {
                    upright = Util.rotateBitmap(result, exifRot);
                }
                Uri uri = saver.saveJpeg(upright, "LUMEN_" + stamp + ".jpg", info,
                        prefs.jpegQuality());
                upright.recycle();
                if (dng != null) saver.saveDng(dng, "LUMEN_" + stamp + ".dng");

                ui.post(() -> {
                    processing.setVisibility(View.GONE);
                    processingShot = false;
                    updateThumbnail(uri);
                    hint((capturedMode == Prefs.MODE_NIGHT ? "Noche" : "HDR") + " guardado · "
                            + frames.size() + " tomas");
                });
            } catch (Exception e) {
                if (result != null && !result.isRecycled()) result.recycle();
                ui.post(() -> {
                    processing.setVisibility(View.GONE);
                    processingShot = false;
                    hint("Error al procesar: " + e.getMessage());
                });
            }
        });
    }

    private List<Bitmap> decodeFrames(List<byte[]> frames, int maxMp) {
        List<Bitmap> out = new ArrayList<>();
        long target = (long) maxMp * 1_000_000L;
        for (byte[] data : frames) {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, probe);
            int sample = 1;
            while ((long) (probe.outWidth / sample) * (probe.outHeight / sample) > target
                    && sample < 8) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            if (bmp != null) out.add(bmp);
        }
        return out;
    }

    private void updateThumbnail(Uri uri) {
        if (uri == null) return;
        prefs.setLastUri(uri.toString());
        worker.execute(() -> {
            Bitmap bmp = saver.thumbnail(uri, 220);
            if (bmp != null) {
                ui.post(() -> thumbnail.setImageBitmap(bmp));
            }
        });
    }

    private void loadThumbnail() {
        String last = prefs.lastUri();
        if (last == null) return;
        updateThumbnail(Uri.parse(last));
    }

    private void openViewer(Uri uri) {
        Intent intent = new Intent(this, ViewerActivity.class);
        intent.setData(uri);
        startActivity(intent);
    }

    // ------------------------------------------------------------------ video

    private void toggleRecording() {
        if (engine == null) return;
        if (engine.isRecording()) {
            engine.stopRecording();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMS);
            hint("Se necesita micrófono para grabar vídeo");
            return;
        }
        try {
            String name = "LUMEN_" + Util.fileStamp() + ".mp4";
            videoTarget = saver.createVideo(name);
            ParcelFileDescriptor pfd = saver.openVideoFd(videoTarget);
            boolean started = engine.startRecording(pfd, prefs.videoHeight(),
                    prefs.videoBitrateMbps(), prefs.videoHevc());
            if (!started) {
                pfd.close();
                saver.delete(videoTarget.uri);
                videoTarget = null;
            }
        } catch (Exception e) {
            hint("No se pudo grabar: " + e.getMessage());
            if (videoTarget != null) {
                saver.delete(videoTarget.uri);
                videoTarget = null;
            }
        }
    }

    // ------------------------------------------------------------------ manual

    private void applyManual() {
        if (engine == null) return;
        CameraEngine.Manual m = new CameraEngine.Manual();
        m.iso = (int) sliderIso.value();
        m.shutterNs = (long) sliderShutter.value();
        m.focusDiopters = sliderFocus.value();
        m.wbKelvin = (int) sliderWb.value();
        m.ev = 0f;
        engine.setManual(m);
    }

    private void updateManualInfo() {
        if (wbInfo == null) return;
        String iso = sliderIso.isAuto() ? "AUTO" : String.format(Locale.US, "ISO %.0f",
                sliderIso.value());
        String sh = sliderShutter.isAuto() ? "AUTO" : Util.shutterLabel((long) sliderShutter.value());
        String wb = sliderWb.isAuto() ? "WB AUTO" : String.format(Locale.US, "%dK", (int) sliderWb.value());
        String foco = sliderFocus.isAuto() ? "AF" : "MF";
        wbInfo.setText(iso + " · " + sh + " · " + wb + " · " + foco);
        updateStatusText();
    }

    private void lockExposure() {
        if (engine == null) return;
        int iso = engine.lastIso();
        long ns = engine.lastShutterNs();
        if (iso <= 0 || ns <= 0) {
            hint("Aún no hay medición de exposición");
            return;
        }
        selectNearest(sliderIso, iso);
        selectNearest(sliderShutter, ns);
        applyManual();
        updateManualInfo();
        hint("Exposición bloqueada");
    }

    private void selectNearest(SliderView slider, float value) {
        float best = Float.MAX_VALUE;
        int bestIndex = 0;
        for (int i = 0; i < slider.getStopsCount(); i++) {
            float v = slider.getStopValue(i);
            if (v < 0) continue;
            float d = Math.abs(v - value);
            if (d < best) {
                best = d;
                bestIndex = i;
            }
        }
        slider.setIndex(bestIndex);
    }

    // ------------------------------------------------------------------- misc

    private void cycleFlash() {
        int next;
        switch (prefs.flashMode()) {
            case CameraEngine.FLASH_OFF:
                next = CameraEngine.FLASH_AUTO;
                break;
            case CameraEngine.FLASH_AUTO:
                next = CameraEngine.FLASH_ON;
                break;
            case CameraEngine.FLASH_ON:
                next = CameraEngine.FLASH_TORCH;
                break;
            default:
                next = CameraEngine.FLASH_OFF;
                break;
        }
        prefs.setFlashMode(next);
        if (engine != null) engine.setFlashMode(next);
        applyFlashIcon();
        hint(next == CameraEngine.FLASH_OFF ? "Flash desactivado"
                : next == CameraEngine.FLASH_AUTO ? "Flash automático"
                : next == CameraEngine.FLASH_ON ? "Flash encendido" : "Linterna encendida");
    }

    private void applyFlashIcon() {
        switch (prefs.flashMode()) {
            case CameraEngine.FLASH_AUTO:
                btnFlash.setIcon(IconView.FLASH_AUTO);
                btnFlash.setActive(true);
                break;
            case CameraEngine.FLASH_ON:
                btnFlash.setIcon(IconView.FLASH_ON);
                btnFlash.setActive(true);
                break;
            case CameraEngine.FLASH_TORCH:
                btnFlash.setIcon(IconView.FLASH_TORCH);
                btnFlash.setActive(true);
                break;
            default:
                btnFlash.setIcon(IconView.FLASH_OFF);
                btnFlash.setActive(false);
                break;
        }
    }

    private void cycleTimer() {
        int t = prefs.timer();
        int next = t == 0 ? 3 : t == 3 ? 10 : 0;
        prefs.setTimer(next);
        updateTimerBadge();
        hint(next == 0 ? "Temporizador desactivado" : "Temporizador " + next + "s");
    }

    private void updateTimerBadge() {
        int t = prefs.timer();
        timerBadge.setVisibility(t > 0 ? View.VISIBLE : View.GONE);
        timerBadge.setText(t + "s");
        btnTimer.setActive(t > 0);
    }

    private void updateStatusText() {
        if (statusText == null) return;
        StringBuilder sb = new StringBuilder();
        if (caps != null) {
            long pixels = (long) caps.jpegSize.getWidth() * caps.jpegSize.getHeight();
            sb.append(Math.round(pixels / 1_000_000.0)).append(" MP");
            if (caps.raw && prefs.saveRaw()) sb.append(" · DNG");
        } else {
            sb.append("LUMEN");
        }
        if (mode == Prefs.MODE_PRO) {
            int iso = engine == null ? -1 : engine.lastIso();
            long ns = engine == null ? -1 : engine.lastShutterNs();
            if (iso > 0) sb.append(" · ISO ").append(iso);
            if (ns > 0) sb.append(" · ").append(Util.shutterShort(ns));
        }
        statusText.setText(sb.toString());
    }

    private void hint(String message) {
        if (hintText == null || message == null) return;
        hintText.setText(message);
        hintText.animate().cancel();
        hintText.setAlpha(1f);
        ui.removeCallbacks(clearHint);
        ui.postDelayed(clearHint, 3200);
    }

    private final Runnable clearHint = () -> {
        if (hintText != null) hintText.animate().alpha(0f).setDuration(400).start();
    };

    private void restartCamera() {
        if (engine != null) {
            engine.close();
            engine.shutdown();
        }
        engine = null;
        ui.postDelayed(this::startCamera, 250);
    }

    private boolean hasFrontCamera() {
        try {
            android.hardware.camera2.CameraManager m =
                    (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);
            if (m == null) return false;
            for (String id : m.getCameraIdList()) {
                Integer facing = m.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                if (facing != null
                        && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    // --------------------------------------------------------------- live data

    private final Runnable histogramLoop = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && prefs.histogram()) {
                try {
                    Bitmap small = preview.getBitmap(180, 135);
                    if (small != null) {
                        int[] r = new int[256];
                        int[] g = new int[256];
                        int[] b = new int[256];
                        int[] px = new int[small.getWidth() * small.getHeight()];
                        small.getPixels(px, 0, small.getWidth(), 0, 0,
                                small.getWidth(), small.getHeight());
                        for (int c : px) {
                            r[(c >> 16) & 0xFF]++;
                            g[(c >> 8) & 0xFF]++;
                            b[c & 0xFF]++;
                        }
                        small.recycle();
                        overlay.setHistogram(r, g, b);
                    }
                } catch (Exception ignored) {
                }
            }
            ui.postDelayed(this, 400);
        }
    };

    private final SensorEventListener levelListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            float x = event.values[0];
            float y = event.values[1];
            int rot = displayRotation();
            float rx;
            float ry;
            switch (rot) {
                case 90:
                    rx = y;
                    ry = -x;
                    break;
                case 180:
                    rx = -x;
                    ry = -y;
                    break;
                case 270:
                    rx = -y;
                    ry = x;
                    break;
                default:
                    rx = x;
                    ry = y;
                    break;
            }
            float angle = (float) Math.toDegrees(Math.atan2(rx, ry));
            levelFiltered = levelFiltered * 0.85f + angle * 0.15f;
            overlay.setLevelAngle(levelFiltered);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    // -------------------------------------------------------- texture lifecycle

    @Override
    public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width,
                                         int height) {
        if (engine != null) {
            engine.onSurfaceReady();
            engine.updatePreviewTransform();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width,
                                            int height) {
        if (engine != null) engine.updatePreviewTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {
    }
}
