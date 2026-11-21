package ru.example.childwatch.designsystem;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class ArcanoidFrameBufferTest {
    private ArcanoidRoomSnapshot state(long tick,double x,String epoch,int lives)throws Exception{
        JSONObject raw=ArcanoidRoomSnapshotTest.room().put("epoch",epoch).put("phase","PLAYING").put("revision",tick+1);
        JSONObject game=raw.getJSONObject("arcanoid");game.put("tick",tick).put("state","PLAYING").put("lives",lives);game.getJSONObject("ball").put("x",x);
        return ArcanoidRoomSnapshot.parse(raw);
    }
    @Test public void interpolationUsesConfirmedFramesAndNeverExtrapolates()throws Exception{
        ArcanoidFrameBuffer buffer=new ArcanoidFrameBuffer();buffer.add(state(0,200,"one",3),0);buffer.add(state(3,400,"one",3),50);
        assertEquals(300,buffer.sample(75).ball(true),.0001);assertEquals(400,buffer.sample(10000).ball(true),0);
    }
    @Test public void jitterIsBoundedAndLifeLossOrNewEpochFlushesOldPhysics()throws Exception{
        ArcanoidFrameBuffer buffer=new ArcanoidFrameBuffer();buffer.add(state(0,200,"one",3),0);buffer.add(state(3,400,"one",3),150);
        assertTrue(buffer.delay()>=50);assertTrue(buffer.delay()<=150);
        buffer.add(state(4,300,"one",2),160);assertEquals(300,buffer.sample(160).ball(true),0);
        buffer.add(state(0,500,"two",2),170);assertEquals(500,buffer.sample(170).ball(true),0);
        buffer.clear();assertNull(buffer.sample(180));
    }
}
