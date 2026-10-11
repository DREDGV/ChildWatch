package ru.example.childwatch.designsystem;

import java.util.ArrayDeque;

/** Visits accepted cell centres in order. Never predicts a future cell. */
final class SnakeCellMotion {
    private static final int MAX_LEGS=3;
    private static final class Leg {
        final float x,y; final long duration;
        Leg(float x,float y,long duration){this.x=x;this.y=y;this.duration=duration;}
    }
    private final ArrayDeque<Leg> legs=new ArrayDeque<>();
    private float fromX,fromY,x,y,targetX,targetY;
    private long started;
    SnakeCellMotion(int cell){snap(cell);}
    SnakeCellMotion copy(){
        SnakeCellMotion result=new SnakeCellMotion(0);
        result.fromX=fromX;result.fromY=fromY;result.x=x;result.y=y;
        result.targetX=targetX;result.targetY=targetY;result.started=started;
        result.legs.addAll(legs);return result;
    }
    void snap(int cell){legs.clear();x=fromX=targetX=cell%20;y=fromY=targetY=cell/20;}
    void append(int cell,long now,int duration){
        advance(now);
        float nextX=cell%20,nextY=cell/20;
        if(nextX==targetX&&nextY==targetY)return;
        if(duration<=0||Math.abs(nextX-targetX)+Math.abs(nextY-targetY)>1||legs.size()>=MAX_LEGS){snap(cell);return;}
        if(legs.isEmpty()){fromX=x;fromY=y;started=now;}
        legs.addLast(new Leg(nextX,nextY,duration));targetX=nextX;targetY=nextY;
    }
    void advance(long now){
        while(!legs.isEmpty()){
            Leg leg=legs.peekFirst();
            long elapsed=Math.max(0,now-started);
            if(elapsed<leg.duration){float fraction=elapsed/(float)leg.duration;x=fromX+(leg.x-fromX)*fraction;y=fromY+(leg.y-fromY)*fraction;return;}
            x=fromX=leg.x;y=fromY=leg.y;started+=leg.duration;legs.removeFirst();
        }
    }
    float x(){return x;}
    float y(){return y;}
    boolean moving(){return !legs.isEmpty();}
    int buffered(){return legs.size();}
    float bendX(){return fromX;}
    float bendY(){return fromY;}
    boolean joinsViaBend(float tailX,float tailY){
        return (tailX==fromX||tailY==fromY)&&(x==fromX||y==fromY);
    }
    float axisX(){return legs.isEmpty()?0:Math.signum(legs.peekFirst().x-fromX);}
    float axisY(){return legs.isEmpty()?0:Math.signum(legs.peekFirst().y-fromY);}
}
