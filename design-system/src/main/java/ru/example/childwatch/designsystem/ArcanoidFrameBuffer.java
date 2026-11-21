package ru.example.childwatch.designsystem;

import java.util.ArrayDeque;

/** Bounded arrival-time buffer. Holds the newest confirmed state rather than extrapolating physics. */
final class ArcanoidFrameBuffer {
    static final class Frame {
        final ArcanoidRoomSnapshot state;final long at;
        Frame(ArcanoidRoomSnapshot state,long at){this.state=state;this.at=at;}
    }
    static final class Sample {
        final ArcanoidRoomSnapshot from,to;final double amount;
        Sample(ArcanoidRoomSnapshot from,ArcanoidRoomSnapshot to,double amount){this.from=from;this.to=to;this.amount=amount;}
        double ball(boolean x){return lerp(x?from.ballX:from.ballY,x?to.ballX:to.ballY);}
        double paddle(int player){return lerp(from.paddle(player),to.paddle(player));}
        private double lerp(double from,double to){return from+(to-from)*amount;}
    }
    private final ArrayDeque<Frame> frames=new ArrayDeque<>();
    private double jitter;private long previousInterval=50;
    void clear(){frames.clear();jitter=0;previousInterval=50;}
    void add(ArcanoidRoomSnapshot state,long now){
        Frame last=frames.peekLast();
        if(last!=null&&(!last.state.epoch.equals(state.epoch)||!"PLAYING".equals(state.phase)||!"PLAYING".equals(last.state.phase)||last.state.level!=state.level||last.state.lives!=state.lives))clear();
        last=frames.peekLast();
        if(last!=null){long interval=Math.max(0,now-last.at);jitter=.8*jitter+.2*Math.abs(interval-previousInterval);previousInterval=interval;}
        frames.addLast(new Frame(state,now));while(frames.size()>32)frames.removeFirst();
    }
    int delay(){return(int)Math.max(50,Math.min(150,50+2*jitter));}
    Sample sample(long now){
        if(frames.isEmpty())return null;
        long target=now-delay();
        while(frames.size()>2){java.util.Iterator<Frame> it=frames.iterator();it.next();if(it.next().at<=target)frames.removeFirst();else break;}
        Frame a=frames.peekFirst(),b=a;
        for(Frame frame:frames){if(frame.at>target){b=frame;break;}a=b=frame;}
        double fraction=a==b?1:Math.max(0,Math.min(1,(target-a.at)/(double)Math.max(1,b.at-a.at)));
        return new Sample(a.state,b.state,fraction);
    }
}
