package hr.astrid.minigun;

import android.graphics.Bitmap;

final class NativeBridge {
    static { System.loadLibrary("minigunbridge"); }
    private NativeBridge() {}
    static native boolean open(String libraryPath, String romPath, String saveDirectory);
    static native int step(int buttonMask, Bitmap destination);
    static native short[] drainAudio();
    static native int sampleRate();
    static native void reset();
    static native void saveSram();
    static native boolean saveState();
    static native boolean loadState();
    static native void close();
}
