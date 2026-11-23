package mx.lumen.camera;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodecList;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Motor de cámara Camera2 con control manual real, RAW/DNG, ráfagas para
 * HDR/noche y grabación de vídeo con bitrate alto.
 */
public class CameraEngine {

    private static final String TAG = "LumenCamera";

    public static final int FLASH_OFF = 0;
    public static final int FLASH_AUTO = 1;
    public static final int FLASH_ON = 2;
    public static final int FLASH_TORCH = 3;

    public interface Listener {
        void onCameraReady(Caps caps);
        void onCameraFailed(String message);
        void onBurstReady(List<byte[]> frames, byte[] dng, ShotInfo info);
        void onVideoState(boolean recording, boolean ok);
        void onCameraHint(String hint);
    }

    /** Ajustes manuales. -1 significa automático. */
    public static class Manual {
        public int iso = -1;
        public long shutterNs = -1;
        public float focusDiopters = -1f;
        public int wbKelvin = -1;
        public float ev = 0f;
    }

    public static class ShotInfo {
        public long exposureNs;
        public int iso;
        public float focalLength;
        public float aperture;
        public int wbKelvin;
        public int width;
        public int height;
        public int rotation;       // grados a aplicar a la imagen
        public int frames;
        public boolean front;
    }

    public static class Caps {
        public boolean raw;
        public boolean manualSensor;
        public boolean manualFocus;
        public boolean manualWb;
        public boolean ois;
        public boolean eis;
        public boolean logicalMulti;
        public boolean flash;
        public Range<Integer> isoRange = new Range<>(100, 3200);
        public Range<Long> shutterRange = new Range<>(1_000_000L, 100_000_000L);
        public Range<Integer> evRange = new Range<>(-2, 2);
        public float evStep = 0.333f;
        public float maxZoom = 1f;
        public float minFocusDistance = 0f;
        public float focalLength = 0f;
        public float aperture = 0f;
        public int sensorOrientation = 90;
        public int hardwareLevel = 0;
        public int maxJpegPixels = 0;
        public Size previewSize;
        public Size jpegSize;
    }

    private final Activity activity;
    private final TextureView textureView;
    private final Listener listener;
    private final HandlerThread thread;
    private final Handler handler;

    private CameraManager manager;
    private CameraDevice device;
    private CameraCaptureSession session;
    private CaptureRequest.Builder previewBuilder;
    private ImageReader jpegReader;
    private ImageReader rawReader;
    private Surface previewSurface;
    private MediaRecorder recorder;
    private ParcelFileDescriptor videoPfd;
    private Surface recorderSurface;

    private CameraCharacteristics chars;
    private String cameraId;
    private boolean front;
    private Size previewSize;
    private Size photoPreviewSize;
    private Size videoPreviewSize;
    private boolean videoMode;
    private Size jpegSize;
    private Size rawSize;
    private Rect activeArray = new Rect(0, 0, 4000, 3000);
    private int sensorOrientation = 90;
    private int displayRotation = 0;
    private float zoom = 1f;
    private int flashMode = FLASH_OFF;
    private int maxJpegMp = 12;
    private boolean manualActive = false;
    private Manual manual = new Manual();
    private Caps caps = new Caps();
    private CaptureResult lastPreviewResult;

    private boolean surfacing;
    private boolean opening;
    private boolean recording;
    private boolean rawFailedOnce;

    private ShotInfo pendingInfo;
    private final List<byte[]> burst = new ArrayList<>();
    private byte[] dngBytes;
    private Image rawImage;
    private TotalCaptureResult dngResult;
    private int expectedFrames;
    private boolean shotInFlight;
    private Runnable shotTimeout;
    private boolean expectRaw;
    private int dngRetry;
    private boolean videoActive;

    public CameraEngine(Activity activity, TextureView textureView, Listener listener) {
        this.activity = activity;
        this.textureView = textureView;
        this.listener = listener;
        this.thread = new HandlerThread("LumenCamera");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    public Caps caps() {
        return caps;
    }

    public boolean isFront() {
        return front;
    }

    public float zoom() {
        return zoom;
    }

    public boolean isRecording() {
        return recording;
    }

    public int displayRotation() {
        return displayRotation;
    }

    public void setDisplayRotation(int degrees) {
        displayRotation = degrees;
    }

    /** Rotación real (en grados) que hay que aplicar a la imagen para verla derecha. */
    public int previewRotation() {
        if (rotationOverride >= 0) {
            return ((rotationOverride % 360) + 360) % 360;
        }
        // La mayoría de HALs entregan la vista previa ya orientada a la pantalla.
        // El ajuste manual de rotación cubre los equipos que no lo hacen.
        return ((rotationOffset % 360) + 360) % 360;
    }

    private int rotationOffset = 0;
    private int rotationOverride = -1;
    private boolean eisEnabled = true;
    private boolean aspect169 = false;
    private int videoFps = 30;

    /** Solo para calibración/pruebas: desplaza la rotación de la vista previa. */
    public void setRotationOffset(int degrees) {
        rotationOffset = degrees;
        activity.runOnUiThread(this::updatePreviewTransform);
    }

    public void setPreviewRotationOverride(int degrees) {
        rotationOverride = degrees;
        activity.runOnUiThread(this::updatePreviewTransform);
    }

    public void setVideoStabilizationEnabled(boolean enabled) {
        eisEnabled = enabled;
    }

    public void setVideoFps(int fps) {
        videoFps = fps;
    }

    public void setJpegAspect(boolean is169) {
        aspect169 = is169;
    }

    /** Cambia entre vista previa de foto y de vídeo (16:9) recreando la sesión. */
    public void setVideoMode(boolean enabled) {
        if (videoMode == enabled) return;
        videoMode = enabled;
        previewSize = enabled ? videoPreviewSize : photoPreviewSize;
        if (previewSize == null) return;
        if (device != null && textureView.isAvailable()) {
            createPreviewSession();
        }
    }

    public int lastIso() {
        if (lastPreviewResult == null) return -1;
        Integer v = lastPreviewResult.get(CaptureResult.SENSOR_SENSITIVITY);
        return v == null ? -1 : v;
    }

    public long lastShutterNs() {
        if (lastPreviewResult == null) return -1;
        Long v = lastPreviewResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        return v == null ? -1 : v;
    }

    public float lastFocusDiopters() {
        if (lastPreviewResult == null) return -1;
        Float v = lastPreviewResult.get(CaptureResult.LENS_FOCUS_DISTANCE);
        return v == null ? -1 : v;
    }

    public void setMaxJpegMp(int mp) {
        maxJpegMp = mp;
    }

    public void setManualActive(boolean active) {
        manualActive = active;
        updateRepeating();
    }

    public void setManual(Manual m) {
        manual = m == null ? new Manual() : m;
        updateRepeating();
    }

    public Manual manual() {
        return manual;
    }

    public void setFlashMode(int mode) {
        flashMode = mode;
        updateRepeating();
    }

    public int flashMode() {
        return flashMode;
    }

    // ------------------------------------------------------------------ open

    public void open(boolean useFront) {
        front = useFront;
        manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            fail("Servicio de cámara no disponible en este dispositivo.");
            return;
        }
        try {
            String id = pickCameraId(front);
            if (id == null) {
                fail(front ? "No hay cámara frontal disponible."
                        : "No hay cámara trasera disponible.");
                return;
            }
            cameraId = id;
            chars = manager.getCameraCharacteristics(id);
            readCharacteristics();
            if (textureView.isAvailable()) {
                openDevice();
            } else {
                surfacing = true;
            }
        } catch (CameraAccessException | SecurityException e) {
            fail("No se pudo acceder a la cámara: " + e.getMessage());
        }
    }

    private String pickCameraId(boolean wantFront) throws CameraAccessException {
        int wanted = wantFront ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        String best = null;
        long bestArea = -1;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != wanted) continue;
            StreamConfigurationMap m =
                    c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (m == null) continue;
            Size[] sizes = m.getOutputSizes(ImageFormat.JPEG);
            long area = 0;
            if (sizes != null) {
                for (Size s : sizes) area = Math.max(area, (long) s.getWidth() * s.getHeight());
            }
            if (area > bestArea) {
                bestArea = area;
                best = id;
            }
        }
        return best;
    }

    private void readCharacteristics() {
        caps = new Caps();
        caps.flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != null
                && chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        Integer level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        caps.hardwareLevel = level == null ? 0 : level;

        int[] capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        List<Integer> capsList = capabilities == null
                ? Collections.<Integer>emptyList() : new ArrayList<>();
        for (int c : capabilities == null ? new int[0] : capabilities) capsList.add(c);
        caps.raw = capsList.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW);
        caps.manualSensor = capsList.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
        caps.logicalMulti = capsList.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);

        Range<Integer> iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (iso != null) caps.isoRange = iso;
        Range<Long> shutter = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (shutter != null) caps.shutterRange = shutter;

        Range<Integer> ev = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (ev != null) caps.evRange = ev;
        android.util.Rational step =
                chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        if (step != null && step.floatValue() > 0) caps.evStep = step.floatValue();

        Float maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        caps.maxZoom = maxZoom == null ? 1f : Math.max(1f, maxZoom);

        Float minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        caps.manualFocus = minFocus != null && minFocus > 0;
        caps.minFocusDistance = minFocus == null ? 0f : minFocus;

        int[] oisModes =
                chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        caps.ois = oisModes != null && contains(oisModes,
                CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON);
        int[] eisModes =
                chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        caps.eis = eisModes != null && contains(eisModes,
                CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON);

        caps.maxJpegPixels = 0;
        float[] focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (focal != null && focal.length > 0) caps.focalLength = focal[0];
        float[] apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        if (apertures != null && apertures.length > 0) caps.aperture = apertures[0];

        Rect arr = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (arr != null) activeArray = arr;
        Integer orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        sensorOrientation = orientation == null ? 90 : orientation;
        caps.sensorOrientation = sensorOrientation;

        StreamConfigurationMap map =
                chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            Size[] previews = map.getOutputSizes(SurfaceTexture.class);
            double photoAspect = aspect169 ? 16.0 / 9.0 : 4.0 / 3.0;
            photoPreviewSize = pickPreviewSize(previews, photoAspect, 1080);
            videoPreviewSize = pickPreviewSize(previews, 16.0 / 9.0, 1080);
            previewSize = videoMode ? videoPreviewSize : photoPreviewSize;
            Size[] jpegs = map.getOutputSizes(ImageFormat.JPEG);
            jpegSize = pickJpegSize(jpegs);
            if (jpegs != null) {
                StringBuilder sb = new StringBuilder();
                for (Size s : jpegs) sb.append(s.getWidth()).append('x')
                        .append(s.getHeight()).append(' ');
                Log.i(TAG, "JPEG disponibles: " + sb);
            }
            if (caps.raw) {
                Size[] raws = map.getOutputSizes(ImageFormat.RAW_SENSOR);
                if (raws != null && raws.length > 0) {
                    rawSize = raws[0];
                } else {
                    caps.raw = false;
                }
            }
        }
        if (previewSize == null) previewSize = new Size(1280, 720);
        if (photoPreviewSize == null) photoPreviewSize = previewSize;
        if (videoPreviewSize == null) videoPreviewSize = previewSize;
        if (jpegSize == null) jpegSize = new Size(1920, 1080);
        caps.previewSize = previewSize;
        caps.jpegSize = jpegSize;
        caps.maxJpegPixels = jpegSize.getWidth() * jpegSize.getHeight();
        caps.manualWb = true;
        Log.i(TAG, "preview=" + previewSize + " jpeg=" + jpegSize + " raw=" + rawSize
                + " rot=" + sensorOrientation + " iso=" + caps.isoRange
                + " shutter=" + caps.shutterRange + " manualSensor=" + caps.manualSensor
                + " raw=" + caps.raw + " maxZoom=" + caps.maxZoom);
        if (!caps.manualSensor) {
            // Sin MANUAL_SENSOR no hay ISO/obturación manuales fiables.
            caps.manualFocus = caps.manualFocus && caps.manualSensor;
        }
    }

    private static boolean contains(int[] arr, int value) {
        for (int v : arr) if (v == value) return true;
        return false;
    }

    private Size pickPreviewSize(Size[] sizes, double targetAspect, int targetHeight) {
        if (sizes == null || sizes.length == 0) return new Size(1280, 720);
        Size best = null;
        double bestScore = Double.MAX_VALUE;
        for (Size s : sizes) {
            double aspect = s.getWidth() / (double) s.getHeight();
            if (Math.abs(aspect - targetAspect) > 0.08) continue;
            double sizePenalty = Math.abs(Math.min(s.getWidth(), s.getHeight()) - targetHeight)
                    / (double) targetHeight;
            if (s.getHeight() > 1300) sizePenalty += (s.getHeight() - 1300) / 1000.0;
            if (sizePenalty < bestScore) {
                bestScore = sizePenalty;
                best = s;
            }
        }
        if (best != null) return best;
        return pickPreviewSizeFallback(sizes);
    }

    private Size pickPreviewSizeFallback(Size[] sizes) {
        Size best = null;
        double bestScore = Double.MAX_VALUE;
        for (Size s : sizes) {
            double aspect = s.getWidth() / (double) s.getHeight();
            double aspectPenalty = Math.abs(aspect - 16.0 / 9.0) * 10.0;
            double sizePenalty = Math.abs(Math.min(s.getWidth(), s.getHeight()) - 1080) / 1080.0;
            if (s.getHeight() > 1200) sizePenalty += (s.getHeight() - 1200) / 1000.0;
            double score = aspectPenalty + sizePenalty;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best == null ? sizes[0] : best;
    }

    private Size pickJpegSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1920, 1080);
        double targetAspect = aspect169 ? 16.0 / 9.0 : 4.0 / 3.0;
        boolean max = maxJpegMp >= 48;
        long targetPixels = max ? Long.MAX_VALUE : (long) maxJpegMp * 1_000_000L;
        long cap = max ? Long.MAX_VALUE : (long) (maxJpegMp * 1_150_000L);

        Size best = null;
        long bestDiff = Long.MAX_VALUE;
        Size anyBest = null;
        long anyBestArea = 0;
        Size smallest = sizes[0];
        long smallestArea = Long.MAX_VALUE;

        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            double aspect = s.getWidth() / (double) s.getHeight();
            boolean aspectOk = Math.abs(aspect - targetAspect) < 0.06;
            if (area < smallestArea) {
                smallestArea = area;
                smallest = s;
            }
            if (area > cap) continue;
            if (aspectOk) {
                long diff = max ? -area : Math.abs(area - targetPixels);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = s;
                }
            }
            if (area > anyBestArea) {
                anyBestArea = area;
                anyBest = s;
            }
        }
        if (best != null) return best;
        if (anyBest != null) return anyBest;
        return smallest;
    }

    private void openDevice() {
        if (opening || device != null) return;
        opening = true;
        try {
            if (activity.checkSelfPermission(android.Manifest.permission.CAMERA)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                opening = false;
                fail("Permiso de cámara denegado.");
                return;
            }
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    opening = false;
                    device = camera;
                    createPreviewSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    opening = false;
                    camera.close();
                    device = null;
                    fail("La cámara se desconectó.");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    opening = false;
                    camera.close();
                    device = null;
                    fail("Error de cámara (" + error + ").");
                }
            }, handler);
        } catch (CameraAccessException | SecurityException | IllegalStateException e) {
            opening = false;
            fail("No se pudo abrir la cámara: " + e.getMessage());
        }
    }

    public void onSurfaceReady() {
        if (surfacing || device == null) {
            surfacing = false;
            if (device == null) openDevice();
            return;
        }
        if (previewSurface == null) createPreviewSession();
    }

    private void createPreviewSession() {
        if (device == null || !textureView.isAvailable()) return;
        closeSession();
        closeReaders();
        releasePreviewSurface();

        SurfaceTexture st = textureView.getSurfaceTexture();
        if (st == null) return;
        st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        float[] tm = new float[16];
        st.getTransformMatrix(tm);
        Log.i(TAG, "SurfaceTexture transform: [" + tm[0] + ", " + tm[1] + ", " + tm[4]
                + ", " + tm[5] + "] buffer=" + previewSize);
        previewSurface = new Surface(st);

        jpegReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(),
                ImageFormat.JPEG, 6);
        jpegReader.setOnImageAvailableListener(this::onJpeg, handler);

        List<Surface> surfaces = new ArrayList<>();
        surfaces.add(previewSurface);
        surfaces.add(jpegReader.getSurface());

        if (caps.raw && rawSize != null && !rawFailedOnce) {
            rawReader = ImageReader.newInstance(rawSize.getWidth(), rawSize.getHeight(),
                    ImageFormat.RAW_SENSOR, 3);
            rawReader.setOnImageAvailableListener(this::onRaw, handler);
            surfaces.add(rawReader.getSurface());
        } else {
            rawReader = null;
        }

        try {
            device.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    activity.runOnUiThread(CameraEngine.this::updatePreviewTransform);
                    updateRepeating();
                    if (listener != null) {
                        final Caps snapshot = caps;
                        activity.runOnUiThread(() -> listener.onCameraReady(snapshot));
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    if (caps.raw && !rawFailedOnce) {
                        rawFailedOnce = true;
                        Log.w(TAG, "Sesión con RAW falló, reintentando sin RAW");
                        caps.raw = false;
                        createPreviewSession();
                    } else {
                        fail("No se pudo configurar la vista previa.");
                    }
                }
            }, handler);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            fail("Error al crear la sesión: " + e.getMessage());
        }
    }

    // --------------------------------------------------------------- requests

    private void updateRepeating() {
        if (session == null || device == null) return;
        try {
            CaptureRequest.Builder b =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            if (previewSurface != null) b.addTarget(previewSurface);
            applyCommon(b);

            if (flashMode == FLASH_TORCH) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            }
            applyManual(b, false);

            previewBuilder = b;
            session.setRepeatingRequest(b.build(), previewCallback, handler);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "updateRepeating: " + e.getMessage());
        }
    }

    private final CameraCaptureSession.CaptureCallback previewCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request,
                                               TotalCaptureResult result) {
                    lastPreviewResult = result;
                }
            };

    private void applyCommon(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO);
        if (recording) {
            if (caps.eis && eisEnabled) {
                b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
            }
            if (caps.ois) {
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
            }
        }
        applyZoom(b);
    }

    private void applyZoom(CaptureRequest.Builder b) {
        if (zoom <= 1.001f) {
            if (Build.VERSION.SDK_INT >= 30 && caps.maxZoom > 1f) {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f);
            } else {
                b.set(CaptureRequest.SCALER_CROP_REGION, activeArray);
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= 30 && caps.maxZoom > 1f) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
            return;
        }
        float ratio = 1f / zoom;
        int w = Math.round(activeArray.width() * ratio);
        int h = Math.round(activeArray.height() * ratio);
        int cx = activeArray.centerX();
        int cy = activeArray.centerY();
        Rect crop = new Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        crop.left = Math.max(activeArray.left, crop.left);
        crop.top = Math.max(activeArray.top, crop.top);
        crop.right = Math.min(activeArray.right, crop.right);
        crop.bottom = Math.min(activeArray.bottom, crop.bottom);
        b.set(CaptureRequest.SCALER_CROP_REGION, crop);
    }

    private Rect currentCrop() {
        if (Build.VERSION.SDK_INT >= 30 && zoom > 1.001f && caps.maxZoom > 1f) {
            int w = Math.round(activeArray.width() / zoom);
            int h = Math.round(activeArray.height() / zoom);
            int cx = activeArray.centerX();
            int cy = activeArray.centerY();
            return new Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        }
        return activeArray;
    }

    private boolean manualExposure() {
        return manualActive && manual.iso > 0 && manual.shutterNs > 0 && caps.manualSensor;
    }

    private void applyManual(CaptureRequest.Builder b, boolean forStill) {
        if (!manualActive) {
            if (!forStill) {
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0);
            }
            return;
        }
        int ev = Math.round(manual.ev / Math.max(0.01f, caps.evStep));
        int clampedEv = (int) Util.clamp(ev, caps.evRange.getLower(), caps.evRange.getUpper());
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedEv);

        if (manualExposure()) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampShutter(manual.shutterNs));
            b.set(CaptureRequest.SENSOR_SENSITIVITY,
                    (int) Util.clamp(manual.iso, caps.isoRange.getLower(),
                            caps.isoRange.getUpper()));
        }
        if (caps.manualFocus && manual.focusDiopters >= 0) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            float d = Util.clamp(manual.focusDiopters, 0f, caps.minFocusDistance);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, d);
        }
        if (manual.wbKelvin > 0) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
            b.set(CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinGains(manual.wbKelvin));
        }
    }

    private long clampShutter(long ns) {
        return Util.clamp(ns, caps.shutterRange.getLower(), caps.shutterRange.getUpper());
    }

    /** Aproximación de ganancias de balance de blancos para una temperatura dada. */
    private static android.hardware.camera2.params.RggbChannelVector kelvinGains(int kelvin) {
        int k = (int) Util.clamp(kelvin, 1900, 10000);
        double temp = k / 100.0;
        double r;
        double g;
        double bl;
        if (temp <= 66) {
            r = 255;
            g = 99.4708025861 * Math.log(temp) - 161.1195681661;
            bl = temp <= 19 ? 0 : 138.5177312231 * Math.log(temp - 10) - 305.0447927307;
        } else {
            r = 329.698727446 * Math.pow(temp - 60, -0.1332047592);
            g = 288.1221695283 * Math.pow(temp - 60, -0.0755148492);
            bl = 255;
        }
        r = Util.clamp((float) r, 1f, 255f);
        g = Util.clamp((float) g, 1f, 255f);
        bl = Util.clamp((float) bl, 1f, 255f);
        // El sensor está calibrado para ~5200K: compensamos respecto a esa referencia.
        float gr = (float) (255.0 / r);
        float gg = (float) (255.0 / g);
        float gb = (float) (255.0 / bl);
        float norm = Math.max(1f, Math.max(gr, Math.max(gg, gb)));
        return new android.hardware.camera2.params.RggbChannelVector(
                gr / norm * 1.6f, gg / norm * 1.6f, gg / norm * 1.6f, gb / norm * 1.6f);
    }

    public void setZoom(float value) {
        zoom = Util.clamp(value, 1f, Math.max(1f, caps.maxZoom));
        updateRepeating();
    }

    /** Enfoca en coordenadas normalizadas de la vista (0..1). */
    public void tapFocus(float nx, float ny) {
        if (session == null || previewBuilder == null) return;
        try {
            float x = Util.clamp(nx, 0f, 1f);
            float y = Util.clamp(ny, 0f, 1f);
            if (front) x = 1f - x;

            int rot = previewRotation();
            float sx;
            float sy;
            switch (rot) {
                case 90:
                    sx = y;
                    sy = 1f - x;
                    break;
                case 180:
                    sx = 1f - x;
                    sy = 1f - y;
                    break;
                case 270:
                    sx = 1f - y;
                    sy = x;
                    break;
                default:
                    sx = x;
                    sy = y;
                    break;
            }
            Rect crop = currentCrop();
            int cx = crop.left + Math.round(sx * crop.width());
            int cy = crop.top + Math.round(sy * crop.height());
            int radius = Math.max(60, Math.round(Math.min(crop.width(), crop.height()) * 0.06f));
            Rect area = new Rect(
                    Util.clamp(cx - radius, crop.left, crop.right),
                    Util.clamp(cy - radius, crop.top, crop.bottom),
                    Util.clamp(cx + radius, crop.left, crop.right),
                    Util.clamp(cy + radius, crop.top, crop.bottom));
            MeteringRectangle mr = new MeteringRectangle(area, MeteringRectangle.METERING_WEIGHT_MAX);

            boolean manualFocus = manualActive && manual.focusDiopters >= 0;
            CaptureRequest.Builder repeat =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            repeat.addTarget(previewSurface);
            applyCommon(repeat);
            if (manualActive && manualExposure()) {
                applyManual(repeat, false);
            } else {
                repeat.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{mr});
            }
            if (!manualFocus) {
                repeat.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{mr});
                repeat.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_AUTO);
            }
            if (flashMode == FLASH_TORCH) {
                repeat.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            }
            session.setRepeatingRequest(repeat.build(), previewCallback, handler);

            if (!manualFocus) {
                // Un disparo único con el trigger de AF: así no se reenfoca sin parar.
                CaptureRequest.Builder trig =
                        device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                trig.addTarget(previewSurface);
                applyCommon(trig);
                trig.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{mr});
                trig.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_AUTO);
                trig.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CaptureRequest.CONTROL_AF_TRIGGER_START);
                if (!(manualActive && manualExposure())) {
                    trig.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{mr});
                    if (flashMode != FLASH_OFF) {
                        trig.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                    }
                }
                if (flashMode == FLASH_TORCH) {
                    trig.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                }
                session.capture(trig.build(), previewCallback, handler);
            }

            handler.postDelayed(() -> {
                if (!recording) updateRepeating();
            }, 2200);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "tapFocus: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- capture

    public void capture(int frames, boolean bracket, boolean night, boolean saveRaw) {
        if (session == null || device == null) {
            Log.w(TAG, "capture: sin sesión (session=" + session + ", device=" + device + ")");
            return;
        }
        if (shotInFlight) {
            if (listener != null) listener.onCameraHint("Procesando la foto anterior…");
            return;
        }
        Log.i(TAG, "capture frames=" + frames + " bracket=" + bracket + " night=" + night
                + " raw=" + saveRaw);
        try {
            burst.clear();
            dngBytes = null;
            dngResult = null;
            shotInFlight = true;
            expectedFrames = frames;

            pendingInfo = new ShotInfo();
            pendingInfo.frames = frames;
            pendingInfo.front = front;
            pendingInfo.rotation = Util.jpegRotation(sensorOrientation, displayRotation, front);
            pendingInfo.width = jpegSize.getWidth();
            pendingInfo.height = jpegSize.getHeight();
            pendingInfo.focalLength = caps.focalLength;
            pendingInfo.aperture = caps.aperture;
            pendingInfo.wbKelvin = manualActive && manual.wbKelvin > 0
                    ? manual.wbKelvin : 5500;

            long baseNs = 15_000_000L;
            int baseIso = 400;
            Integer rIso = lastPreviewResult == null ? null
                    : lastPreviewResult.get(CaptureResult.SENSOR_SENSITIVITY);
            Long rExp = lastPreviewResult == null ? null
                    : lastPreviewResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            if (rIso != null && rIso > 0) baseIso = rIso;
            if (rExp != null && rExp > 0) baseNs = rExp;

            if (manualActive && manualExposure()) {
                baseNs = clampShutter(manual.shutterNs);
                baseIso = manual.iso;
            }
            pendingInfo.exposureNs = baseNs;
            pendingInfo.iso = baseIso;

            float[] evs;
            if (bracket) {
                evs = bracketEvs(frames);
            } else {
                evs = new float[frames];
            }

            boolean wantRaw = saveRaw && caps.raw && !bracket && frames == 1 && !night;
            expectRaw = wantRaw;
            dngRetry = 0;
            if (rawImage != null) {
                try {
                    rawImage.close();
                } catch (Exception ignored) {
                }
                rawImage = null;
            }

            List<CaptureRequest> requests = new ArrayList<>();
            for (int i = 0; i < frames; i++) {
                CaptureRequest.Builder b =
                        device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(jpegReader.getSurface());
                if (wantRaw && rawReader != null) b.addTarget(rawReader.getSurface());
                applyCommon(b);
                applyManual(b, true);
                b.set(CaptureRequest.JPEG_ORIENTATION,
                        Util.jpegRotation(sensorOrientation, displayRotation, front));
                b.set(CaptureRequest.JPEG_QUALITY, (byte) 98);

                if (bracket) {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                    double factor = Math.pow(2.0, evs[i]);
                    long t = clampShutter(Math.round(baseNs * factor));
                    // Para EV negativos preferimos bajar ISO antes que bajar tiempo si ya es corto.
                    int iso = (int) Util.clamp(Math.round(baseIso * (evs[i] < 0 ? 1 : 1)),
                            caps.isoRange.getLower(), caps.isoRange.getUpper());
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, t);
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
                } else if (night) {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                    b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0);
                }

                if (flashMode == FLASH_ON) {
                    if (manualExposure()) {
                        b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE);
                    } else {
                        b.set(CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                    }
                } else if (flashMode == FLASH_AUTO) {
                    b.set(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                } else if (flashMode == FLASH_TORCH) {
                    b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                    b.set(CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                }
                requests.add(b.build());
            }

            if (wantRaw) {
                // El RAW necesita completarse: esperamos también 1 imagen RAW.
                dngBytes = null;
            }

            CameraCaptureSession.CaptureCallback cb =
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(CameraCaptureSession s,
                                                       CaptureRequest request,
                                                       TotalCaptureResult result) {
                            if (result != null && dngResult == null) dngResult = result;
                        }

                        @Override
                        public void onCaptureSequenceCompleted(CameraCaptureSession s,
                                                               int sequenceId, long frameNumber) {
                            updateRepeating();
                            if (burst.size() >= expectedFrames) finishShot();
                        }
                    };

            if (requests.size() == 1) {
                session.capture(requests.get(0), cb, handler);
            } else {
                session.captureBurst(requests, cb, handler);
            }

            if (shotTimeout != null) handler.removeCallbacks(shotTimeout);
            shotTimeout = () -> {
                if (shotInFlight) finishShot();
            };
            handler.postDelayed(shotTimeout, 8000);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            shotInFlight = false;
            if (listener != null) listener.onCameraFailed("Error al capturar: " + e.getMessage());
        }
    }

    private float[] bracketEvs(int frames) {
        switch (frames) {
            case 2:
                return new float[]{-1.5f, 1.5f};
            case 3:
                return new float[]{-2f, 0f, 2f};
            case 5:
                return new float[]{-3f, -1.5f, 0f, 1.5f, 3f};
            case 7:
                return new float[]{-3f, -2f, -1f, 0f, 1f, 2f, 3f};
            default:
                float[] out = new float[frames];
                for (int i = 0; i < frames; i++) {
                    out[i] = -2f + 4f * i / Math.max(1, frames - 1);
                }
                return out;
        }
    }

    private void onJpeg(ImageReader reader) {
        Image img = null;
        try {
            img = reader.acquireNextImage();
            if (img == null) return;
            byte[] data = imageBytes(img);
            burst.add(data);
            Log.i(TAG, "JPEG recibido " + data.length + " bytes (" + burst.size() + "/"
                    + expectedFrames + ")");
            if (burst.size() >= expectedFrames) finishShot();
        } catch (Exception e) {
            Log.w(TAG, "onJpeg: " + e.getMessage());
        } finally {
            if (img != null) img.close();
        }
    }

    private void onRaw(ImageReader reader) {
        Image img = null;
        try {
            img = reader.acquireNextImage();
            if (img == null) return;
            // El TotalCaptureResult suele llegar después de la imagen RAW: la
            // conservamos abierta y el DNG se escribe al completar la captura.
            if (rawImage != null) rawImage.close();
            rawImage = img;
            img = null;
        } catch (Exception e) {
            Log.w(TAG, "onRaw: " + e.getMessage());
        } finally {
            if (img != null) img.close();
        }
    }

    private void buildDng() {
        if (rawImage == null || dngResult == null) return;
        try {
            DngCreator creator = new DngCreator(chars, dngResult);
            creator.setOrientation(Util.jpegRotation(sensorOrientation, displayRotation, front));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            creator.writeImage(out, rawImage);
            dngBytes = out.toByteArray();
            creator.close();
        } catch (Exception e) {
            Log.w(TAG, "DNG: " + e.getMessage());
        } finally {
            try {
                rawImage.close();
            } catch (Exception ignored) {
            }
            rawImage = null;
        }
    }

    private static byte[] imageBytes(Image img) {
        ByteBuffer buffer = img.getPlanes()[0].getBuffer();
        buffer.rewind();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        return data;
    }

    private void finishShot() {
        if (!shotInFlight) return;
        if (expectRaw && rawImage != null && dngResult == null && dngRetry++ < 8) {
            handler.postDelayed(this::finishShot, 50);
            return;
        }
        shotInFlight = false;
        if (shotTimeout != null) handler.removeCallbacks(shotTimeout);
        shotTimeout = null;
        buildDng();
        expectRaw = false;
        List<byte[]> frames = new ArrayList<>(burst);
        burst.clear();
        byte[] dng = dngBytes;
        dngBytes = null;
        ShotInfo info = pendingInfo;
        pendingInfo = null;
        dngResult = null;
        if (frames.isEmpty()) {
            Log.w(TAG, "finishShot sin frames");
            if (listener != null) listener.onCameraHint("La captura no devolvió imagen.");
            return;
        }
        Log.i(TAG, "finishShot frames=" + frames.size() + " dng=" + (dng != null));
        if (listener != null) listener.onBurstReady(frames, dng, info);
    }

    // ------------------------------------------------------------------ video

    public boolean startRecording(ParcelFileDescriptor pfd, int targetHeight,
                                  int bitrateMbps, boolean hevc) {
        if (device == null || recording) return false;
        try {
            Size videoSize = pickVideoSize(targetHeight);
            int fps = videoFps;
            try {
                StreamConfigurationMap map =
                        chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    long dur = map.getOutputMinFrameDuration(MediaRecorder.class, videoSize);
                    if (dur > 0 && 1_000_000_000L / dur < fps - 2) fps = 30;
                }
            } catch (Exception ignored) {
            }

            recorder = new MediaRecorder();
            boolean useHevc = hevc && hasHevcEncoder();
            try {
                configureRecorder(recorder, useHevc, videoSize, fps, bitrateMbps, pfd);
            } catch (Exception first) {
                Log.w(TAG, "HEVC/H.265 no válido, probando H.264: " + first.getMessage());
                try {
                    recorder.reset();
                } catch (Exception ignored) {
                }
                configureRecorder(recorder, false, videoSize, fps, bitrateMbps, pfd);
            }

            videoPfd = pfd;
            recorderSurface = recorder.getSurface();
            recording = true;
            videoActive = true;
            createRecordingSession();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "startRecording: " + e.getMessage());
            cleanupRecorder();
            recording = false;
            videoActive = false;
            if (listener != null) listener.onCameraFailed("No se pudo iniciar el vídeo: "
                    + e.getMessage());
            return false;
        }
    }

    private void configureRecorder(MediaRecorder r, boolean hevc, Size size, int fps,
                                   int bitrateMbps, ParcelFileDescriptor pfd) throws IOException {
        r.setAudioSource(MediaRecorder.AudioSource.MIC);
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        r.setOutputFile(pfd.getFileDescriptor());
        r.setVideoEncoder(hevc ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
        r.setVideoSize(size.getWidth(), size.getHeight());
        r.setVideoFrameRate(fps);
        r.setVideoEncodingBitRate(bitrateMbps * 1_000_000);
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        r.setAudioSamplingRate(48000);
        r.setAudioEncodingBitRate(192_000);
        r.setAudioChannels(2);
        r.setOrientationHint(Util.jpegRotation(sensorOrientation, displayRotation, front));
        r.prepare();
    }

    private void createRecordingSession() {
        if (device == null || previewSurface == null || recorderSurface == null) return;
        closeSession();
        try {
            device.createCaptureSession(Arrays.asList(previewSurface, recorderSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                CaptureRequest.Builder b = device.createCaptureRequest(
                                        CameraDevice.TEMPLATE_RECORD);
                                b.addTarget(previewSurface);
                                b.addTarget(recorderSurface);
                                applyCommon(b);
                                if (caps.eis && eisEnabled) {
                                    b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
                                }
                                if (caps.ois) {
                                    b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
                                }
                                if (flashMode == FLASH_TORCH) {
                                    b.set(CaptureRequest.FLASH_MODE,
                                            CaptureRequest.FLASH_MODE_TORCH);
                                }
                                session.setRepeatingRequest(b.build(), null, handler);
                                recorder.start();
                                if (listener != null) listener.onVideoState(true, true);
                            } catch (Exception e) {
                                Log.w(TAG, "record start: " + e.getMessage());
                                stopRecording();
                                if (listener != null) {
                                    listener.onCameraFailed("Error al grabar: " + e.getMessage());
                                }
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            stopRecording();
                            if (listener != null) {
                                listener.onCameraFailed("No se pudo configurar la grabación.");
                            }
                        }
                    }, handler);
        } catch (Exception e) {
            Log.w(TAG, "createRecordingSession: " + e.getMessage());
            stopRecording();
        }
    }

    public void stopRecording() {
        if (!videoActive) return;
        videoActive = false;
        recording = false;
        handler.post(() -> {
            boolean ok = cleanupRecorder();
            rec_surface_null();
            if (device != null && textureView.isAvailable()) {
                createPreviewSession();
            }
            if (listener != null) listener.onVideoState(false, ok);
        });
    }

    private void rec_surface_null() {
        if (recorderSurface != null) {
            try {
                recorderSurface.release();
            } catch (Exception ignored) {
            }
            recorderSurface = null;
        }
    }

    private boolean cleanupRecorder() {
        boolean ok = true;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception e) {
                ok = false;
                Log.w(TAG, "recorder.stop: " + e.getMessage());
            }
            try {
                recorder.reset();
            } catch (Exception ignored) {
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
        if (videoPfd != null) {
            try {
                videoPfd.close();
            } catch (Exception ignored) {
            }
            videoPfd = null;
        }
        return ok;
    }

    private Size pickVideoSize(int targetHeight) {
        StreamConfigurationMap map =
                chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size fallback = new Size(1920, 1080);
        if (map == null) return fallback;
        Size[] sizes = map.getOutputSizes(MediaRecorder.class);
        if (sizes == null || sizes.length == 0) return fallback;
        Size best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Size s : sizes) {
            if (s.getHeight() > targetHeight + 8) continue;
            if (s.getWidth() > 4096) continue;
            double aspect = s.getWidth() / (double) s.getHeight();
            int aspectPenalty = Math.abs(aspect - 16.0 / 9.0) < 0.08 ? 0 : 100;
            int score = Math.abs(s.getHeight() - targetHeight) + aspectPenalty;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        if (best == null) {
            for (Size s : sizes) {
                if (s.getWidth() <= 1920 && s.getHeight() <= 1080) {
                    best = s;
                    break;
                }
            }
        }
        return best == null ? fallback : best;
    }

    private static boolean hasHevcEncoder() {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (int i = 0; i < list.getCodecCount(); i++) {
                android.media.MediaCodecInfo info = list.getCodecInfoAt(i);
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if ("video/hevc".equalsIgnoreCase(type)) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ------------------------------------------------------------------ misc

    public void updatePreviewTransform() {
        if (textureView == null || previewSize == null) return;
        int vw = textureView.getWidth();
        int vh = textureView.getHeight();
        if (vw == 0 || vh == 0) return;

        int rot = previewRotation();
        float bw = previewSize.getWidth();
        float bh = previewSize.getHeight();
        // Dimensiones del búfer ya rotado (caja que debe cubrir la pantalla).
        float rotatedW = rot % 180 == 0 ? bw : bh;
        float rotatedH = rot % 180 == 0 ? bh : bw;

        float cx = vw / 2f;
        float cy = vh / 2f;
        float cover = Math.max(vw / rotatedW, vh / rotatedH);

        // TextureView estira el búfer a todo el view. Deshacemos ese estiramiento,
        // aplicamos escala uniforme, rotación y centrado (todo en espacio de view).
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postTranslate(-cx, -cy);
        m.postScale(bw / (float) vw, bh / (float) vh);
        m.postScale(cover, cover);
        m.postRotate(rot);
        m.postTranslate(cx, cy);
        if (front) {
            m.postScale(-1f, 1f, cx, cy);
        }
        textureView.setTransform(m);
    }

    private void closeReaders() {
        if (jpegReader != null) {
            try {
                jpegReader.close();
            } catch (Exception ignored) {
            }
            jpegReader = null;
        }
        if (rawReader != null) {
            try {
                rawReader.close();
            } catch (Exception ignored) {
            }
            rawReader = null;
        }
    }

    private void closeSession() {
        if (session != null) {
            try {
                session.stopRepeating();
            } catch (Exception ignored) {
            }
            try {
                session.abortCaptures();
            } catch (Exception ignored) {
            }
            try {
                session.close();
            } catch (Exception ignored) {
            }
            session = null;
        }
    }

    private void releasePreviewSurface() {
        if (previewSurface != null) {
            try {
                previewSurface.release();
            } catch (Exception ignored) {
            }
            previewSurface = null;
        }
    }

    public void close() {
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        recording = false;
        cleanupRecorder();
        rec_surface_null();
        closeSession();
        closeReaders();
        releasePreviewSurface();
        if (device != null) {
            try {
                device.close();
            } catch (Exception ignored) {
            }
            device = null;
        }
    }

    public void shutdown() {
        close();
        if (thread != null) thread.quitSafely();
    }

    private void fail(String message) {
        Log.e(TAG, message);
        if (listener != null) {
            activity.runOnUiThread(() -> listener.onCameraFailed(message));
        }
    }
}
