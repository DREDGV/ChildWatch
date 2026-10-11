package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;

/** Displays only confirmed server cells. Interpolation never predicts a future tick. */
public final class SnakeOnlineView extends View {
    public interface DirectionListener { void turn(SnakeEngine.Direction direction); }
    private final DirectionListener listener;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final int[][] cells={new int[0],new int[0]};
    private final float[][] oldX={new float[0],new float[0]}, oldY={new float[0],new float[0]};
    private final String[] directions={"RIGHT","LEFT"};
    private String epoch="",tick="",phase="";
    private int own=-1,food=-1,duration;
    private boolean active,canTurn;
    private long started;
    private float cell,left,top,touchX,touchY;
    public SnakeOnlineView(Context context,DirectionListener listener){super(context);this.listener=listener;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    public void setPlayer(int player){own=player;invalidate();}
    public void setActive(boolean value){active=value;if(!value){duration=0;canTurn=false;}invalidate();}
    public void setCanTurn(boolean value){canTurn=value;}
    public void freeze(){duration=0;canTurn=false;invalidate();}
    public void accept(JSONObject snapshot){
        JSONObject snake=snapshot.optJSONObject("snake");if(snake==null)return;
        String nextEpoch=snapshot.optString("epoch"),nextTick=snake.optString("tick"),nextPhase=snapshot.optString("phase");
        if(epoch.equals(nextEpoch)&&tick.equals(nextTick)&&phase.equals(nextPhase))return;
        boolean adjacent=false;try{adjacent=Long.parseLong(nextTick)==Long.parseLong(tick)+1;}catch(NumberFormatException ignored){}
        boolean animate=active&&adjacent&&epoch.equals(nextEpoch)&&"PLAYING".equals(phase)&&"PLAYING".equals(nextPhase)&&android.animation.ValueAnimator.areAnimatorsEnabled();
        float amount=amount();JSONArray players=snake.optJSONArray("players");if(players==null)return;
        for(int p=0;p<2;p++){
            JSONObject player=players.optJSONObject(p);if(player==null)return;
            JSONArray body=player.optJSONArray("body");if(body==null)return;
            int[] next=new int[body.length()];float[] x=new float[next.length],y=new float[next.length];
            for(int i=0;i<next.length;i++){
                next[i]=body.optInt(i);int old=Math.min(i,cells[p].length-1);
                x[i]=animate&&old>=0?visual(p,old,amount,true):next[i]%20;
                y[i]=animate&&old>=0?visual(p,old,amount,false):next[i]/20;
            }
            cells[p]=next;oldX[p]=x;oldY[p]=y;directions[p]=player.optString("direction");
        }
        JSONObject foodValue=snake.optJSONObject("food");food=foodValue==null||foodValue.optInt("x",-1)<0?-1:foodValue.optInt("y")*20+foodValue.optInt("x");
        epoch=nextEpoch;tick=nextTick;phase=nextPhase;started=SystemClock.uptimeMillis();duration=animate?Math.min(180,Math.max(0,snake.optInt("tickMs",180))):0;
        invalidate();
    }
    private float amount(){return duration<=0?1:Math.min(1,(SystemClock.uptimeMillis()-started)/(float)duration);}
    private float visual(int p,int i,float amount,boolean horizontal){int value=cells[p][i];float destination=horizontal?value%20:value/20;float old=horizontal?oldX[p][i]:oldY[p][i];return old+(destination-old)*amount;}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){float margin=12*getResources().getDisplayMetrics().density;cell=Math.max(0,Math.min((w-margin*2)/20,(h-margin*2)/36));left=(w-cell*20)/2;top=(h-cell*36)/2;}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);if(cell<=0)return;float interpolation=amount();
        c.save();c.translate(left,top);c.scale(cell,cell);
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xff203e4c);c.drawRoundRect(-.25f,-.25f,20.25f,36.25f,.7f,.7f,paint);
        paint.setColor(0xff193541);c.drawRoundRect(0,0,20,36,.5f,.5f,paint);
        paint.setColor(0x063effff);for(int y=0;y<36;y++)for(int x=y%2;x<20;x+=2)c.drawRect(x,y,x+1,y+1,paint);
        if(food>=0){float x=food%20+.5f,y=food/20+.54f;paint.setColor(0xffff9c89);c.drawCircle(x-.13f,y,.29f,paint);c.drawCircle(x+.13f,y,.29f,paint);paint.setColor(0xffa2d8ad);c.drawOval(x,y-.48f,x+.27f,y-.3f,paint);}
        for(int p=0;p<2;p++){
            if(cells[p].length==0)continue;path.reset();
            for(int i=cells[p].length-1;i>=0;i--){float x=visual(p,i,interpolation,true)+.5f,y=visual(p,i,interpolation,false)+.5f;if(i==cells[p].length-1)path.moveTo(x,y);else path.lineTo(x,y);}
            boolean mine=p==own;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(.78f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setColor(mine?0xff64cfbc:0xffa8c5ff);c.drawPath(path,paint);
            paint.setStyle(Paint.Style.FILL);float x=visual(p,0,interpolation,true)+.5f,y=visual(p,0,interpolation,false)+.5f;paint.setColor(mine?0xffb2eed9:0xffd3e1ff);c.drawCircle(x,y,.41f,paint);
            float dx="LEFT".equals(directions[p])?-1:"RIGHT".equals(directions[p])?1:0,dy="UP".equals(directions[p])?-1:"DOWN".equals(directions[p])?1:0;
            for(int side:new int[]{-1,1}){float ex=x+dx*.14f-dy*side*.17f,ey=y+dy*.14f+dx*side*.17f;paint.setColor(0xffffffff);c.drawCircle(ex,ey,.115f,paint);paint.setColor(0xff17333d);c.drawCircle(ex+dx*.035f,ey+dy*.035f,.061f,paint);}
        }
        c.restore();if(active&&interpolation<1)postInvalidateOnAnimation();
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(!canTurn||own<0)return false;
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:touchX=event.getX();touchY=event.getY();getParent().requestDisallowInterceptTouchEvent(true);return true;
            case MotionEvent.ACTION_MOVE:
                float dx=event.getX()-touchX,dy=event.getY()-touchY;
                if(Math.max(Math.abs(dx),Math.abs(dy))>=18*getResources().getDisplayMetrics().density){listener.turn(Math.abs(dx)>Math.abs(dy)?dx>0?SnakeEngine.Direction.RIGHT:SnakeEngine.Direction.LEFT:dy>0?SnakeEngine.Direction.DOWN:SnakeEngine.Direction.UP);touchX=event.getX();touchY=event.getY();}return true;
            case MotionEvent.ACTION_UP:performClick();return true;
            case MotionEvent.ACTION_CANCEL:return true;
            default:return false;
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDetachedFromWindow(){setActive(false);super.onDetachedFromWindow();}
    String displayedTick(){return tick;}
    String displayedEpoch(){return epoch;}
    int confirmedHead(int player){return cells[player].length==0?-1:cells[player][0];}
}
