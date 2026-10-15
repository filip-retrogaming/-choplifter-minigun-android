package hr.astrid.minigun;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

public final class MainActivity extends Activity {
    // Libretro SNES gamepad IDs. B=machine gun in Choplifter III.
    static final int SNES_B=1<<0, SNES_Y=1<<1, SELECT=1<<2, START=1<<3;
    static final int UP=1<<4, DOWN=1<<5, LEFT=1<<6, RIGHT=1<<7;
    static final int SNES_A=1<<8, SNES_X=1<<9, SNES_L=1<<10, SNES_R=1<<11;
    private static final int PICK_ROM=101;
    private static final long ORIGINAL_CRC=0x227E2D1FL;
    private static final long PATCHED_CRC=0x07DFFA28L;
    private static final int ACTION_NONE=0, ACTION_SAVE=1, ACTION_LOAD=2, ACTION_RESET=3;

    private final AtomicInteger pendingAction=new AtomicInteger(ACTION_NONE);
    private final Bitmap bitmap=Bitmap.createBitmap(512,480,Bitmap.Config.ARGB_8888);
    private volatile boolean running;
    private volatile int touchMask, keyMask, axisMask;
    private Thread gameThread;
    private PlayView playView;
    private volatile int pictureWidth, pictureHeight;
    private volatile String notice="Odaberi ROM za početak.";
    private boolean screenActive;
    private final FileLookup files=new FileLookup();

    private final class FileLookup {
        File rom() { return new File(getFilesDir(),"choplifter_minigun.smc"); }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fullScreen();
        playView=new PlayView(this);
        setContentView(playView);
    }
    @Override protected void onResume() {
        super.onResume();
        screenActive=true;
        fullScreen();
        if (files.rom().exists()) startGame();
    }
    @Override protected void onPause() {
        screenActive=false;
        stopGame();
        super.onPause();
    }
    @Override protected void onDestroy() {
        stopGame();
        super.onDestroy();
    }
    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        fullScreen();
    }
    private void fullScreen() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    void menuAction(int action) {
        if (action==4) {
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent,PICK_ROM);
        } else {
            pendingAction.set(action);
        }
    }
    void setTouchMask(int mask) { touchMask=mask; }
    int activeMask() { return touchMask | keyMask | axisMask; }
    void drawBitmap(android.graphics.Canvas canvas, android.graphics.Rect source,
                    android.graphics.RectF target) {
        synchronized (bitmap) { canvas.drawBitmap(bitmap,source,target,null); }
    }
    int videoWidth() { return pictureWidth; }
    int videoHeight() { return pictureHeight; }
    String notice() { return notice; }
    private void showStatus(String s) {
        notice=s;
        runOnUiThread(() -> {
            if (playView!=null) playView.invalidate();
            Toast.makeText(this,s,Toast.LENGTH_SHORT).show();
        });
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request!=PICK_ROM || result!=RESULT_OK || data==null || data.getData()==null) return;
        try {
            Uri uri=data.getData();
            byte[] bytes;
            try (InputStream input=getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                if (input==null) throw new IllegalArgumentException("ROM nije moguće otvoriti.");
                byte[] buffer=new byte[16384];
                int n;
                while ((n=input.read(buffer))!=-1) {
                    if (out.size()+n>4*1024*1024) throw new IllegalArgumentException("ROM je prevelik.");
                    out.write(buffer,0,n);
                }
                bytes=out.toByteArray();
            }
            if (bytes.length<262144) throw new IllegalArgumentException("Odabrana datoteka je premala za SNES ROM.");
            long crc=crc(bytes);
            String resultText;
            if (crc==ORIGINAL_CRC) {
                try (InputStream patch=getAssets().open("minigun.ips")) {
                    ByteArrayOutputStream payload=new ByteArrayOutputStream();
                    byte[] block=new byte[4096];
                    int n;
                    while ((n=patch.read(block))!=-1) payload.write(block,0,n);
                    bytes=IpsPatch.apply(bytes,payload.toByteArray());
                }
                if (crc(bytes)!=PATCHED_CRC)
                    throw new IllegalArgumentException("Provjera Minigun zakrpe nije uspjela.");
                resultText="Originalni ROM automatski pretvoren u MINIGUN izdanje.";
            } else if (crc==PATCHED_CRC) {
                resultText="Minigun ROM učitan.";
            } else {
                resultText="ROM nije prepoznat. Minigun izmjena nije automatski primijenjena.";
            }
            stopGame();
            // If a different ROM is imported, its SRAM/quick save must not load stale contents.
            if (crc!=getPreferences(MODE_PRIVATE).getLong("source_crc",-1)) {
                new File(getFilesDir(),"choplifter.srm").delete();
                new File(getFilesDir(),"choplifter.state").delete();
            }
            try (FileOutputStream dst=new FileOutputStream(files.rom())) { dst.write(bytes); }
            getPreferences(MODE_PRIVATE).edit().putLong("source_crc",crc).apply();
            showStatus(resultText);
            if (screenActive) startGame();
        } catch (Exception ex) {
            showStatus("Učitavanje nije uspjelo: "+ex.getMessage());
            if (screenActive && files.rom().exists()) startGame();
        }
    }
    private static long crc(byte[] bytes) {
        CRC32 c=new CRC32(); c.update(bytes); return c.getValue();
    }

    private synchronized void startGame() {
        if (!screenActive || running || !files.rom().exists()) return;
        running=true;
        notice="Pokretanje...";
        gameThread=new Thread(this::gameLoop,"SNES-emulation");
        gameThread.start();
    }
    private synchronized void stopGame() {
        if (!running) return;
        running=false;
        Thread thread=gameThread;
        if (thread!=null && Thread.currentThread()!=thread) {
            thread.interrupt();
            try { thread.join(5000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
        gameThread=null;
        pictureWidth=0; pictureHeight=0;
    }
    private void gameLoop() {
        AudioTrack track=null;
        try {
            String library=getApplicationInfo().nativeLibraryDir+"/libsnes9x_libretro_android.so";
            if (!NativeBridge.open(library,files.rom().getAbsolutePath(),getFilesDir().getAbsolutePath())) {
                showStatus("Ne mogu otvoriti SNES pogon ili ROM. Provjeri je li uključen Snes9x core.");
                return;
            }
            try {
                int rate=NativeBridge.sampleRate();
                int min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
                if (min<0) min=8192;
                track=new AudioTrack(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                    new AudioFormat.Builder().setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                    Math.max(min,rate/8*4),AudioTrack.MODE_STREAM,
                    android.media.AudioManager.AUDIO_SESSION_ID_GENERATE);
                track.play();
            } catch (RuntimeException soundError) {
                // Do not stop the game if a device has no compatible audio output.
                if (track!=null) try { track.release(); } catch (RuntimeException ignored) { }
                track=null;
            }
            notice="B: MINIGUN     Y: specijalno     SAVE/LOAD: spremanje";
            long next=System.nanoTime();
            while (running && !Thread.currentThread().isInterrupted()) {
                int action=pendingAction.getAndSet(ACTION_NONE);
                if (action==ACTION_SAVE) showStatus(NativeBridge.saveState()?"Pozicija spremljena":"Spremanje nije uspjelo");
                if (action==ACTION_LOAD) showStatus(NativeBridge.loadState()?"Pozicija učitana":"Nema spremljene pozicije");
                if (action==ACTION_RESET) NativeBridge.reset();
                int dimensions;
                synchronized (bitmap) { dimensions=NativeBridge.step(activeMask(),bitmap); }
                if (dimensions!=0) {
                    pictureWidth=(dimensions>>>16)&65535;
                    pictureHeight=dimensions&65535;
                    playView.postInvalidateOnAnimation();
                }
                short[] samples=NativeBridge.drainAudio();
                if (track!=null && samples!=null && samples.length!=0) {
                    track.write(samples,0,samples.length,AudioTrack.WRITE_NON_BLOCKING);
                }
                next+=16_667_000L;
                long wait=next-System.nanoTime();
                if (wait>0) {
                    SystemClock.sleep(Math.max(1,wait/1_000_000L));
                } else if (wait < -100_000_000L) {
                    next=System.nanoTime();
                }
            }
        } catch (Exception|LinkageError e) {
            showStatus("Pogreška SNES pogona: "+e.getMessage());
        } finally {
            try { NativeBridge.close(); } catch (Throwable ignored) { }
            if (track!=null) {
                try { track.pause(); track.flush(); track.release(); } catch (Throwable ignored) { }
            }
            running=false;
        }
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        int mask=keyMaskFor(code);
        if (mask!=0) { keyMask|=mask; return true; }
        return super.onKeyDown(code,event);
    }
    @Override public boolean onKeyUp(int code, KeyEvent event) {
        int mask=keyMaskFor(code);
        if (mask!=0) { keyMask&=~mask; return true; }
        return super.onKeyUp(code,event);
    }
    private static int keyMaskFor(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return RIGHT;
            // On most Xbox-layout pads: bottom physical A -> SNES B, left X -> SNES Y.
            case KeyEvent.KEYCODE_BUTTON_A: return SNES_B;
            case KeyEvent.KEYCODE_BUTTON_X: return SNES_Y;
            case KeyEvent.KEYCODE_BUTTON_B: return SNES_A;
            case KeyEvent.KEYCODE_BUTTON_Y: return SNES_X;
            case KeyEvent.KEYCODE_BUTTON_START: return START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return SELECT;
            case KeyEvent.KEYCODE_BUTTON_L1: return SNES_L;
            case KeyEvent.KEYCODE_BUTTON_R1: return SNES_R;
            default: return 0;
        }
    }
    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if ((e.getSource()&InputDevice.SOURCE_JOYSTICK)==InputDevice.SOURCE_JOYSTICK &&
            e.getAction()==MotionEvent.ACTION_MOVE) {
            float x=e.getAxisValue(MotionEvent.AXIS_HAT_X)+e.getAxisValue(MotionEvent.AXIS_X);
            float y=e.getAxisValue(MotionEvent.AXIS_HAT_Y)+e.getAxisValue(MotionEvent.AXIS_Y);
            axisMask=(x < -.45f? LEFT:0)|(x > .45f? RIGHT:0)|
                (y < -.45f? UP:0)|(y > .45f? DOWN:0);
            return true;
        }
        return super.onGenericMotionEvent(e);
    }
}
