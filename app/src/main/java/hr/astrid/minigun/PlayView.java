package hr.astrid.minigun;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Game screen, SNES on-screen touch buttons, and local file/savestate actions. */
final class PlayView extends View {
    private static final int BG=Color.rgb(12,20,36);
    private final MainActivity host;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final List<TouchButton> buttons=new ArrayList<>();
    private final Rect source=new Rect();
    private final RectF screen=new RectF();
    private int held;

    private static final class TouchButton {
        String label;
        int mask, action;
        RectF rect;
        TouchButton(String label, int mask, int action, RectF rect) {
            this.label=label; this.mask=mask; this.action=action; this.rect=rect;
        }
    }
    PlayView(Context context) {
        super(context);
        host=(MainActivity)context;
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
    }
    private void add(String label,int mask,int action,float x,float y,float rx,float ry) {
        buttons.add(new TouchButton(label,mask,action,new RectF(x-rx,y-ry,x+rx,y+ry)));
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
        super.onSizeChanged(w,h,oldw,oldh);
        buttons.clear();
        float unit=Math.min(w/16f,h/7.5f);
        float cx=w*.125f,cy=h*.72f,spacing=unit*.70f;
        float size=unit*.34f;
        add("↑",MainActivity.UP,0,cx,cy-spacing,size,size);
        add("↓",MainActivity.DOWN,0,cx,cy+spacing,size,size);
        add("←",MainActivity.LEFT,0,cx-spacing,cy,size,size);
        add("→",MainActivity.RIGHT,0,cx+spacing,cy,size,size);
        float ax=w*.88f,ay=h*.71f;
        add("B",MainActivity.SNES_B,0,ax,ay+spacing,size*1.22f,size*1.22f);
        add("Y",MainActivity.SNES_Y,0,ax-spacing,ay,size*1.08f,size*1.08f);
        add("X",MainActivity.SNES_X,0,ax,ay-spacing,size,size);
        add("A",MainActivity.SNES_A,0,ax+spacing,ay,size,size);
        add("L",MainActivity.SNES_L,0,cx,h*.25f,unit*.47f,unit*.23f);
        add("R",MainActivity.SNES_R,0,ax,h*.25f,unit*.47f,unit*.23f);
        add("SELECT",MainActivity.SELECT,0,w*.44f,h*.92f,unit*.46f,unit*.18f);
        add("START",MainActivity.START,0,w*.56f,h*.92f,unit*.46f,unit*.18f);
        add("ROM",0,4,w*.08f,h*.08f,unit*.53f,unit*.22f);
        add("SAVE",0,1,w*.19f,h*.08f,unit*.53f,unit*.22f);
        add("LOAD",0,2,w*.30f,h*.08f,unit*.53f,unit*.22f);
        add("RESET",0,3,w*.42f,h*.08f,unit*.53f,unit*.22f);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(BG);
        int vw=host.videoWidth(), vh=host.videoHeight();
        float w=getWidth(),h=getHeight();
        if (vw>0 && vh>0) {
            float availWidth=w*.65f, availHeight=h*.85f;
            // Native pixel aspect is near 4:3 even when the frame is 256×224.
            float aspect=4f/3f;
            float drawWidth=Math.min(availWidth,availHeight*aspect);
            float drawHeight=drawWidth/aspect;
            float x=w*.5f,y=h*.49f;
            screen.set(x-drawWidth/2,y-drawHeight/2,x+drawWidth/2,y+drawHeight/2);
            source.set(0,0,vw,vh);
            paint.setColor(Color.WHITE);
            paint.setFilterBitmap(false); // sharp pixel-art scaling
            host.drawBitmap(canvas,source,screen);
            paint.setFilterBitmap(true);
        } else {
            centered(canvas,"CHOPLIFTER III",w*.50f,h*.39f,Math.min(w,h)*.077f,0xFFFFC04A);
            centered(canvas,"MINIGUN EDITION",w*.50f,h*.49f,Math.min(w,h)*.045f,Color.WHITE);
            centered(canvas,"Dodirni ROM i odaberi originalni ili modificirani .smc",w*.50f,h*.59f,Math.min(w,h)*.027f,0xFFC3DAE7);
        }
        paint.setTextAlign(Paint.Align.CENTER);
        for (TouchButton button:buttons) {
            boolean down=button.mask!=0 && (held&button.mask)!=0;
            paint.setColor(button.action!=0 ? 0xD750657D : (down ? 0xEFFFBD42 : 0xA0466A86));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(button.rect,13,13,paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            paint.setColor(down ? Color.WHITE : 0xB3DAEDF5);
            canvas.drawRoundRect(button.rect,13,13,paint);
            paint.setStyle(Paint.Style.FILL);
            float textSize=Math.min(button.rect.height()*.49f,Math.min(w,h)*.044f);
            centered(canvas,button.label,button.rect.centerX(),button.rect.centerY()+textSize*.34f,textSize,
                down ? BG : Color.WHITE);
        }
        if (vw>0) {
            centered(canvas,host.notice(),w*.5f,h*.115f,Math.min(w,h)*.023f,0xFFC3DAE7);
        }
    }
    private void centered(Canvas canvas,String s,float x,float y,float textSize,int color) {
        paint.setColor(color);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.BOLD));
        paint.setTextSize(textSize);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(s,x,y,paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int eventType=event.getActionMasked();
        if (eventType==MotionEvent.ACTION_DOWN || eventType==MotionEvent.ACTION_POINTER_DOWN) {
            int active=event.getActionIndex();
            float x=event.getX(active),y=event.getY(active);
            for (TouchButton button:buttons) {
                if (button.action!=0 && button.rect.contains(x,y)) {
                    host.menuAction(button.action);
                    break;
                }
            }
        }
        int mask=0;
        if (eventType!=MotionEvent.ACTION_CANCEL) {
            for (int j=0;j<event.getPointerCount();j++) {
                if ((eventType==MotionEvent.ACTION_UP ||
                     eventType==MotionEvent.ACTION_POINTER_UP) && j==event.getActionIndex()) continue;
                float x=event.getX(j),y=event.getY(j);
                for (TouchButton button:buttons) {
                    if (button.mask!=0 && button.rect.contains(x,y)) mask|=button.mask;
                }
            }
        }
        held=mask;
        host.setTouchMask(mask);
        invalidate();
        return true;
    }
}
