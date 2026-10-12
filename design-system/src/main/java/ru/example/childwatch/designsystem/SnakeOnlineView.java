package ru.example.childwatch.designsystem;

import android.content.Context;
import android.graphics.Canvas;
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
    private final SnakeScene scene;
    private final Path path=new Path();
    private final int[][] cells={new int[0],new int[0]};
    private final SnakeCellMotion[][] motion={new SnakeCellMotion[0],new SnakeCellMotion[0]};
    private final String[] directions={"RIGHT","LEFT"};
    private String epoch="",tick="",phase="";
    private int own=-1,food=-1;
    private boolean active,canTurn;
    private float cell,left,top,touchX,touchY;
    public SnakeOnlineView(Context context,DirectionListener listener){super(context);scene=new SnakeScene(context);this.listener=listener;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    public void setPlayer(int player){own=player;invalidate();}
    public void setActive(boolean value){active=value;if(!value)freeze();invalidate();}
    public void setCanTurn(boolean value){canTurn=value;}
    public void freeze(){for(int p=0;p<2;p++)for(int i=0;i<motion[p].length;i++)motion[p][i].snap(cells[p][i]);canTurn=false;invalidate();}
    public void accept(JSONObject snapshot){
        JSONObject snake=snapshot.optJSONObject("snake");if(snake==null)return;
        String nextEpoch=snapshot.optString("epoch"),nextTick=snake.optString("tick"),nextPhase=snapshot.optString("phase");
        if(epoch.equals(nextEpoch)&&tick.equals(nextTick)&&phase.equals(nextPhase))return;
        boolean adjacent=false;try{adjacent=Long.parseLong(nextTick)==Long.parseLong(tick)+1;}catch(NumberFormatException ignored){}
        boolean animate=active&&adjacent&&epoch.equals(nextEpoch)&&"PLAYING".equals(phase)&&"PLAYING".equals(nextPhase)&&android.animation.ValueAnimator.areAnimatorsEnabled();
        long now=SystemClock.uptimeMillis();int duration=animate?Math.min(180,Math.max(0,snake.optInt("tickMs",180))):0;
        JSONArray players=snake.optJSONArray("players");if(players==null)return;
        for(int p=0;p<2;p++){
            JSONObject player=players.optJSONObject(p);if(player==null)return;
            JSONArray body=player.optJSONArray("body");if(body==null)return;
            int[] next=new int[body.length()];SnakeCellMotion[] transitions=new SnakeCellMotion[next.length];
            if(motion[p].length>0)motion[p][0].advance(now);
            boolean animatePlayer=animate&&(motion[p].length==0||motion[p][0].buffered()<3);
            SnakeCellMotion previousTail=motion[p].length==0?null:motion[p][motion[p].length-1].copy();
            for(int i=0;i<next.length;i++){
                next[i]=body.optInt(i);
                transitions[i]=i<motion[p].length?motion[p][i]:previousTail!=null?previousTail.copy():new SnakeCellMotion(next[i]);
                if(animatePlayer)transitions[i].append(next[i],now,duration);else transitions[i].snap(next[i]);
            }
            cells[p]=next;motion[p]=transitions;directions[p]=player.optString("direction");
        }
        JSONObject foodValue=snake.optJSONObject("food");food=foodValue==null||foodValue.optInt("x",-1)<0?-1:foodValue.optInt("y")*20+foodValue.optInt("x");
        epoch=nextEpoch;tick=nextTick;phase=nextPhase;
        invalidate();
    }
    private float visual(int p,int i,boolean horizontal){return horizontal?motion[p][i].x():motion[p][i].y();}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){float margin=12*getResources().getDisplayMetrics().density;cell=Math.max(0,Math.min((w-margin*2)/20,(h-margin*2)/36));left=(w-cell*20)/2;top=(h-cell*36)/2;}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);if(cell<=0)return;boolean moving=false;long now=SystemClock.uptimeMillis();
        for(SnakeCellMotion[] player:motion)for(SnakeCellMotion segment:player){segment.advance(now);moving|=segment.moving();}
        c.save();c.translate(left,top);c.scale(cell,cell);
        scene.board(c);
        if(food>=0)scene.apple(c,food%20+.5f,food/20+.5f);
        for(int p=0;p<2;p++){
            if(cells[p].length==0)continue;path.reset();
            float tailX=0,tailY=0;
            for(int i=cells[p].length-1;i>=0;i--){
                float x=visual(p,i,true),y=visual(p,i,false);
                if(i==cells[p].length-1)path.moveTo(x+.5f,y+.5f);
                else {
                    if(x!=tailX&&y!=tailY&&motion[p][i].joinsViaBend(tailX,tailY))path.lineTo(motion[p][i].bendX()+.5f,motion[p][i].bendY()+.5f);
                    path.lineTo(x+.5f,y+.5f);
                }
                tailX=x;tailY=y;
            }
            boolean mine=p==own;scene.spine(c,path,mine);
            float dx="LEFT".equals(directions[p])?-1:"RIGHT".equals(directions[p])?1:0,dy="UP".equals(directions[p])?-1:"DOWN".equals(directions[p])?1:0;
            if(motion[p][0].moving()){dx=motion[p][0].axisX();dy=motion[p][0].axisY();}
            for(int i=cells[p].length-1;i>=0;i--)
                scene.segment(c,visual(p,i,true)+.5f,visual(p,i,false)+.5f,mine,i==0,dx,dy);
        }
        c.restore();if(active&&moving)postInvalidateOnAnimation();
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
