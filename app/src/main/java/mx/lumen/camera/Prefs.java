package mx.lumen.camera;

import android.content.Context;
import android.content.SharedPreferences;

/** Ajustes persistentes de la app. */
public final class Prefs {

    public static final int MODE_AUTO = 0;
    public static final int MODE_PRO = 1;
    public static final int MODE_HDR = 2;
    public static final int MODE_NIGHT = 3;
    public static final int MODE_VIDEO = 4;

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences("lumen", Context.MODE_PRIVATE);
    }

    public int mode() { return sp.getInt("mode", MODE_AUTO); }
    public void setMode(int m) { sp.edit().putInt("mode", m).apply(); }

    public boolean grid() { return sp.getBoolean("grid", true); }
    public void setGrid(boolean v) { sp.edit().putBoolean("grid", v).apply(); }

    public boolean level() { return sp.getBoolean("level", true); }
    public void setLevel(boolean v) { sp.edit().putBoolean("level", v).apply(); }

    public boolean histogram() { return sp.getBoolean("histogram", true); }
    public void setHistogram(boolean v) { sp.edit().putBoolean("histogram", v).apply(); }

    public boolean peaking() { return sp.getBoolean("peaking", false); }
    public void setPeaking(boolean v) { sp.edit().putBoolean("peaking", v).apply(); }

    public int jpegQuality() { return sp.getInt("jpegQuality", 97); }
    public void setJpegQuality(int v) { sp.edit().putInt("jpegQuality", v).apply(); }

    public int hdrFrames() { return sp.getInt("hdrFrames", 3); }
    public void setHdrFrames(int v) { sp.edit().putInt("hdrFrames", v).apply(); }

    public int nightFrames() { return sp.getInt("nightFrames", 5); }
    public void setNightFrames(int v) { sp.edit().putInt("nightFrames", v).apply(); }

    public int mergeMp() { return sp.getInt("mergeMp", 8); }
    public void setMergeMp(int v) { sp.edit().putInt("mergeMp", v).apply(); }

    public int resolutionMp() { return sp.getInt("resolutionMp", 48); }
    public void setResolutionMp(int v) { sp.edit().putInt("resolutionMp", v).apply(); }

    public int flashMode() { return sp.getInt("flashMode", 0); }
    public void setFlashMode(int v) { sp.edit().putInt("flashMode", v).apply(); }

    public boolean saveRaw() { return sp.getBoolean("saveRaw", false); }
    public void setSaveRaw(boolean v) { sp.edit().putBoolean("saveRaw", v).apply(); }

    public boolean mirrorFront() { return sp.getBoolean("mirrorFront", false); }
    public void setMirrorFront(boolean v) { sp.edit().putBoolean("mirrorFront", v).apply(); }

    public int timer() { return sp.getInt("timer", 0); }
    public void setTimer(int v) { sp.edit().putInt("timer", v).apply(); }

    public boolean useFront() { return sp.getBoolean("useFront", false); }
    public void setUseFront(boolean v) { sp.edit().putBoolean("useFront", v).apply(); }

    public String lastUri() { return sp.getString("lastUri", null); }
    public void setLastUri(String v) { sp.edit().putString("lastUri", v).apply(); }

    public int videoHeight() { return sp.getInt("videoHeight", 2160); }
    public void setVideoHeight(int v) { sp.edit().putInt("videoHeight", v).apply(); }

    public int videoBitrateMbps() { return sp.getInt("videoBitrate", 100); }
    public void setVideoBitrateMbps(int v) { sp.edit().putInt("videoBitrate", v).apply(); }

    public boolean videoHevc() { return sp.getBoolean("videoHevc", true); }
    public void setVideoHevc(boolean v) { sp.edit().putBoolean("videoHevc", v).apply(); }

    public int videoFps() { return sp.getInt("videoFps", 30); }
    public void setVideoFps(int v) { sp.edit().putInt("videoFps", v).apply(); }

    public boolean videoStabilization() { return sp.getBoolean("videoStab", true); }
    public void setVideoStabilization(boolean v) { sp.edit().putBoolean("videoStab", v).apply(); }

    /** -1 = automático, si no grados absolutos de rotación de la vista previa. */
    public int previewRotation() { return sp.getInt("previewRotation", -1); }
    public void setPreviewRotation(int v) { sp.edit().putInt("previewRotation", v).apply(); }

    public boolean aspect169() { return sp.getBoolean("aspect169", false); }
    public void setAspect169(boolean v) { sp.edit().putBoolean("aspect169", v).apply(); }

    public boolean shutterSound() { return sp.getBoolean("shutterSound", true); }
    public void setShutterSound(boolean v) { sp.edit().putBoolean("shutterSound", v).apply(); }

    public boolean autoPolish() { return sp.getBoolean("autoPolish", false); }
    public void setAutoPolish(boolean v) { sp.edit().putBoolean("autoPolish", v).apply(); }
}
