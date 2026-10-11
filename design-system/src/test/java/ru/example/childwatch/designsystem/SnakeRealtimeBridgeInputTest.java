package ru.example.childwatch.designsystem;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SnakeRealtimeBridgeInputTest {
    @Test public void retryRetainsSequenceTickEpochAndDirection()throws Exception{
        SnakeRealtimeBridge.Input input=new SnakeRealtimeBridge.Input("first",7,25,SnakeEngine.Direction.UP,1000);
        JSONObject a=input.body("room"),b=input.body("room");
        assertEquals(7,a.getLong("sequence"));assertEquals(27,a.getLong("targetTick"));
        assertEquals(a.getString("epoch"),b.getString("epoch"));assertEquals(a.getLong("sequence"),b.getLong("sequence"));
        assertEquals(a.getLong("targetTick"),b.getLong("targetTick"));assertEquals("UP",b.getString("direction"));
        a.put("targetTick",1000);assertEquals(27,input.body("room").getLong("targetTick"));
    }
    @Test public void epochChangeOrTwoSecondsExpiresInputAndClockCannotGoBackwards(){
        SnakeRealtimeBridge.Input input=new SnakeRealtimeBridge.Input("first",1,1,SnakeEngine.Direction.DOWN,1000);
        assertTrue(input.valid("first",2999));assertFalse(input.valid("first",3000));
        assertFalse(input.valid("second",1500));assertFalse(input.valid("first",999));
    }
    @Test public void bufferedCornerCanUseNextDistinctTickWithoutChangingRetryBody()throws Exception{
        SnakeRealtimeBridge.Input input=new SnakeRealtimeBridge.Input("first",2,10,SnakeEngine.Direction.LEFT,1000,13);
        assertEquals(13,input.body("room").getLong("targetTick"));
        assertEquals(2,input.body("room").getLong("sequence"));
    }
    @Test public void threeRapidTurnsWaitForConsumedSlotNotMerelyTickWindow(){
        SnakeRealtimeBridge.TargetQueue queue=new SnakeRealtimeBridge.TargetQueue();
        assertEquals(2,queue.next(0,-1));queue.accepted(2,0);
        assertEquals(3,queue.next(0,2));queue.accepted(3,0);
        assertEquals(-1,queue.next(0,3));
        // Tick1 puts target4 inside the legal +3window, but both queue slots still belong to2/3.
        assertEquals(-1,queue.next(1,3));
        assertEquals(4,queue.next(2,3));queue.accepted(4,2);
        assertEquals(-1,queue.next(2,4));assertEquals(5,queue.next(3,4));
    }
    @Test public void duplicateLateReceiptCannotConsumeExtraSlotAndEpochClearsAll(){
        SnakeRealtimeBridge.TargetQueue queue=new SnakeRealtimeBridge.TargetQueue();
        queue.accepted(2,0);queue.accepted(2,0);assertEquals(3,queue.next(0,2));
        queue.accepted(2,3);assertEquals(5,queue.next(3,2));
        queue.accepted(5,3);queue.accepted(6,3);assertEquals(-1,queue.next(3,6));
        queue.clear();assertEquals(2,queue.next(0,-1));
    }
    @Test public void credentialsRejectEmbeddedAuthAndMalformedServerUrl(){
        for(String url:new String[]{"ftp://server.test","https://name:secret@server.test","https://server.test?token=secret","https://server.test#frag"}){
            try{new SnakeRealtimeBridge.Credentials(url,"family","actor","private");fail(url);}catch(IllegalArgumentException expected){}
        }
        SnakeRealtimeBridge.Credentials c=new SnakeRealtimeBridge.Credentials("https://server.test/","family","actor","private");
        assertEquals("https://server.test",c.serverUrl);assertEquals("actor",c.actorMemberId);
    }
}
