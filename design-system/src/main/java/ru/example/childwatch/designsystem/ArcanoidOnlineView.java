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
    private final PositionListener listener;
    private final ArcanoidScene scene;private long hitAt;
    private ArcanoidRoomSnapshot state;private int player=-1;private boolean active,canMove,dragging;
    private float scale,left,top;
    private long started,touchRenderAt;
    private final ArcanoidFrameBuffer buffer=new ArcanoidFrameBuffer();
    private ArcanoidFrameBuffer.Sample displayed;
    private double predicted=Double.NaN;private final LocalPaddleMotion localMotion=new LocalPaddleMotion();private long lastFrame;
    public void requestPosition(double local){if(!canMove||player<0||state==null)return;
        touchRenderAt=SystemClock.uptimeMillis();localMotion.request(local,state.localX(state.paddle(player),player));invalidate();}

    public ArcanoidOnlineView(Context context,PositionListener listener){super(context);this.listener=listener;scene=new ArcanoidScene(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    public void setPlayer(int value){player=value;predicted=Double.NaN;localMotion.reset();buffer.clear();invalidate();}
    public void setActive(boolean value){active=value;if(!value)freeze();invalidate();}
    public void setCanMove(boolean value){canMove=value;if(!value)dragging=false;}
    public void freeze(){canMove=false;dragging=false;buffer.clear();displayed=null;predicted=Double.NaN;localMotion.reset();lastFrame=0;invalidate();}
    public void accept(ArcanoidRoomSnapshot next){
        if(state!=null&&state.epoch.equals(next.epoch)&&state.revision==next.revision&&state.tick==next.tick)return;
        if(state!=null&&state.epoch.equals(next.epoch)&&next.score>state.score)hitAt=SystemClock.uptimeMillis();
        if(state==null||!state.epoch.equals(next.epoch)||!"PLAYING".equals(next.phase)){predicted=Double.NaN;localMotion.reset();buffer.clear();}
        state=next;started=SystemClock.uptimeMillis();buffer.add(next,started);invalidate();
    }
    private double ball(boolean x){return displayed==null?(x?state.ballX:state.ballY):displayed.ball(x);}
    private double paddle(int p){return displayed==null?state.paddle(p):displayed.paddle(p);}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){float margin=8*getResources().getDisplayMetrics().density;scale=Math.max(0,Math.min((w-margin*2)/1000,(h-margin*2)/1400));left=(w-scale*1000)/2;top=(h-scale*1400)/2;}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);if(scale<=0)return;
        long now=SystemClock.uptimeMillis();if(lastFrame>0&&active&&canMove)GameTiming.sample("Arcanoid.frameInterval",now-lastFrame);
        double elapsed=lastFrame==0?0:Math.min(.05,Math.max(0,(now-lastFrame)/1000d));lastFrame=canMove?now:0;
        displayed=active&&android.animation.ValueAnimator.areAnimatorsEnabled()?buffer.sample(now):null;
        if(state!=null&&player>=0&&canMove){
            double confirmed=state.localX(state.paddle(player),player);
            predicted=localMotion.step(confirmed,elapsed);
            if(touchRenderAt>0&&elapsed>0){GameTiming.sample("Arcanoid.touchRender",now-touchRenderAt);touchRenderAt=0;}
        }
c.save();c.translate(left,top);c.scale(scale,scale);
        scene.board(c,1400);
        if(state!=null){
            for(int i=0;i<30;i++)if((state.bricks&(1<<i))!=0){double x=66+i%6*146,y=420+i/6*72;
                float sx=(float)(player==1?1000-x-136:x),sy=(float)(player==1?1400-y-56:y);scene.brick(c,sx,sy,56,i/6);
            }
            for(int p=0;p<2;p++){
                float x=(float)(p==player&&canMove&&Double.isFinite(predicted)?predicted:state.localX(paddle(p),player)),y=(float)(player==1?1400-state.paddleY(p)-22:state.paddleY(p));
                scene.paddle(c,x,y,p==player);
            }
            scene.ball(c,(float)state.localX(ball(true),player),(float)state.localY(ball(false),player),Math.max(0,1-(now-hitAt)/180f));
            if(active&&"PLAYING".equals(state.phase))postInvalidateOnAnimation();
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
    double displayedLocalPaddle(){return predicted;}
    double confirmedLocalPaddle(){return state==null||player<0?Double.NaN:state.localX(state.paddle(player),player);}
    double confirmedBallX(){return state==null?Double.NaN:state.ballX;}
    float inputScreenY(){return top+scale*1300;}
    float inputScreenX(double normalized){return left+scale*(float)(normalized*1000);}
}
