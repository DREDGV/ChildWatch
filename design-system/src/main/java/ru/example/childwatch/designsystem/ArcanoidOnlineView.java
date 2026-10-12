package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/** Two confirmed paddles and one ball. Guest sees a180° mirror with their paddle below. */
public final class ArcanoidOnlineView extends View {
    public interface PositionListener {void move(double normalized);}
    private final PositionListener listener;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] colors={0xff60c9c0,0xff9ac4ff,0xffbfabed,0xffffc17c,0xfff49caa};
    private ArcanoidRoomSnapshot state;private int player=-1;private boolean active,canMove,dragging;
    private float scale,left,top;private double oldBallX,oldBallY;private final double[] oldPaddles=new double[2];
    private long started;private int duration;
    public ArcanoidOnlineView(Context context,PositionListener listener){super(context);this.listener=listener;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    public void setPlayer(int value){player=value;duration=0;invalidate();}
    public void setActive(boolean value){active=value;if(!value)freeze();invalidate();}
    public void setCanMove(boolean value){canMove=value;if(!value)dragging=false;}
    public void freeze(){duration=0;canMove=false;dragging=false;invalidate();}
    public void accept(ArcanoidRoomSnapshot next){
        if(state!=null&&state.epoch.equals(next.epoch)&&state.revision==next.revision&&state.tick==next.tick)return;
        boolean animate=active&&state!=null&&state.epoch.equals(next.epoch)&&"PLAYING".equals(state.phase)&&"PLAYING".equals(next.phase)
            &&next.tick>=state.tick&&next.tick-state.tick<=6&&state.level==next.level&&state.lives==next.lives&&android.animation.ValueAnimator.areAnimatorsEnabled();
        double amount=amount();oldBallX=animate?ball(true,amount):next.ballX;oldBallY=animate?ball(false,amount):next.ballY;
        for(int p=0;p<2;p++)oldPaddles[p]=animate?paddle(p,amount):next.paddle(p);
        state=next;started=SystemClock.uptimeMillis();duration=animate?50:0;invalidate();
    }
    private double amount(){return duration==0?1:Math.min(1,(SystemClock.uptimeMillis()-started)/(double)duration);}
    private double ball(boolean x,double amount){double to=x?state.ballX:state.ballY,from=x?oldBallX:oldBallY;return from+(to-from)*amount;}
    private double paddle(int p,double amount){return oldPaddles[p]+(state.paddle(p)-oldPaddles[p])*amount;}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){float margin=8*getResources().getDisplayMetrics().density;scale=Math.max(0,Math.min((w-margin*2)/1000,(h-margin*2)/1400));left=(w-scale*1000)/2;top=(h-scale*1400)/2;}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);if(scale<=0)return;c.save();c.translate(left,top);c.scale(scale,scale);
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xff173541);c.drawRoundRect(0,0,1000,1400,28,28,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);paint.setColor(0xff375463);c.drawRoundRect(3,3,997,1397,28,28,paint);paint.setStyle(Paint.Style.FILL);
        if(state!=null){
            for(int i=0;i<30;i++)if((state.bricks&(1<<i))!=0){double x=66+i%6*146,y=420+i/6*72;
                float sx=(float)(player==1?1000-x-136:x),sy=(float)(player==1?1400-y-56:y);paint.setColor(colors[i/6]);c.drawRoundRect(sx,sy,sx+136,sy+56,12,12,paint);paint.setColor(0x30ffffff);c.drawRoundRect(sx+6,sy+5,sx+130,sy+12,3,3,paint);
            }
            double interpolation=amount();for(int p=0;p<2;p++){
                float x=(float)state.localX(paddle(p,interpolation),player),y=(float)(player==1?1400-state.paddleY(p)-22:state.paddleY(p));
                paint.setColor(p==player?0xff60d6bf:0xff9ac4ff);c.drawRoundRect(x-95,y,x+95,y+22,11,11,paint);
                if(p==player){paint.setColor(0xffd5fff0);c.drawRoundRect(x-76,y+5,x+76,y+9,2,2,paint);}
            }
            paint.setColor(0xffffffff);c.drawCircle((float)state.localX(ball(true,interpolation),player),(float)state.localY(ball(false,interpolation),player),20,paint);
            if(active&&interpolation<1)postInvalidateOnAnimation();
        }c.restore();
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(!canMove||player<0||scale<=0)return false;
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                if((event.getY()-top)/scale<700)return false;dragging=true;getParent().requestDisallowInterceptTouchEvent(true);move(event.getX());return true;
            case MotionEvent.ACTION_MOVE:if(!dragging)return false;move(event.getX());return true;
            case MotionEvent.ACTION_UP:if(!dragging)return false;move(event.getX());dragging=false;performClick();return true;
            case MotionEvent.ACTION_CANCEL:dragging=false;return true;
            default:return false;
        }
    }
    private void move(float screenX){listener.move(Math.max(0,Math.min(1,(screenX-left)/scale/1000)));}
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDetachedFromWindow(){setActive(false);super.onDetachedFromWindow();}
    double confirmedLocalPaddle(){return state==null||player<0?Double.NaN:state.localX(state.paddle(player),player);}
    double confirmedBallX(){return state==null?Double.NaN:state.ballX;}
    float inputScreenY(){return top+scale*1300;}
    float inputScreenX(double normalized){return left+scale*(float)(normalized*1000);}
}
