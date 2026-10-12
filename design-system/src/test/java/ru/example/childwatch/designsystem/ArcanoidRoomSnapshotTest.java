package ru.example.childwatch.designsystem;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ArcanoidRoomSnapshotTest {
    static JSONObject room() throws Exception {
        return new JSONObject().put("protocolVersion",1).put("gameId","game-one").put("epoch","epoch-one").put("revision",1)
            .put("phase","PAUSED").put("reason",JSONObject.NULL).put("ready",new JSONArray().put(false).put(false))
            .put("connected",new JSONArray().put(true).put(true)).put("countdownMs",0)
            .put("arcanoid",new JSONObject().put("tick",0).put("width",1000).put("height",1400).put("radius",20)
                .put("paddleWidth",190).put("paddleHeight",22).put("paddles",new JSONArray().put(500).put(700))
                .put("paddleY",new JSONArray().put(1250).put(150)).put("ball",new JSONObject().put("x",500).put("y",900).put("vx",200).put("vy",300))
                .put("bricks",0x3fffffff).put("level",1).put("levelCount",6).put("lives",3).put("score",0).put("state","READY"));
    }
    @Test public void defensivelyCopiesAndRejectsStaleOrMutatedRevision() throws Exception {
        JSONObject raw=room();ArcanoidRoomSnapshot base=ArcanoidRoomSnapshot.parse(raw);raw.getJSONObject("arcanoid").getJSONArray("paddles").put(0,900);
        assertEquals(500,base.paddle(0),0);base.json().put("revision",99);assertEquals(1,base.revision);
        assertTrue(ArcanoidRoomSnapshot.parse(room().put("revision",2)).follows(base));
        assertFalse(ArcanoidRoomSnapshot.parse(room().put("revision",0)).follows(base));
        assertFalse(ArcanoidRoomSnapshot.parse(room().put("epoch","epoch-two")).follows(base));
        JSONObject changed=room();changed.getJSONObject("arcanoid").getJSONObject("ball").put("x",501);
        assertFalse(ArcanoidRoomSnapshot.parse(changed).follows(base));
        JSONObject tick=room().put("revision",2);tick.getJSONObject("arcanoid").put("tick",1);
        assertFalse(base.follows(ArcanoidRoomSnapshot.parse(tick)));
    }
    @Test public void mirrorsBothAxesWithoutStretchingPaddleRange() throws Exception {
        ArcanoidRoomSnapshot state=ArcanoidRoomSnapshot.parse(room());assertEquals(300,state.localX(state.paddle(1),1),0);
        assertEquals(1250,state.localY(state.paddleY(1),1),0);assertEquals(.75,ArcanoidRoomSnapshot.serverPosition(.25,1),0);
        assertEquals(.25,ArcanoidRoomSnapshot.serverPosition(.25,0),0);assertEquals(0,ArcanoidRoomSnapshot.serverPosition(2,1),0);
        try{ArcanoidRoomSnapshot.serverPosition(Double.NaN,0);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void strictGeometryNumericBoundsAndPhase() throws Exception {
        JSONObject raw=room();raw.getJSONObject("arcanoid").put("width",999);reject(raw);
        raw=room();raw.getJSONObject("arcanoid").put("tick",.5);reject(raw);
        raw=room();raw.getJSONObject("arcanoid").getJSONArray("paddles").put(0,94);reject(raw);
        raw=room();raw.getJSONObject("arcanoid").getJSONObject("ball").put("y",1421);reject(raw);
        raw=room();raw.getJSONObject("arcanoid").put("bricks",0x40000000);reject(raw);
        raw=room();raw.getJSONArray("connected").put(0,"true");reject(raw);
        reject(room().put("phase","PLAYING"));reject(room().put("phase","FINISHED"));
    }
    @Test public void intermediateWinRequiresConsentAndFinalWinRequiresLastLevel() throws Exception {
        JSONObject raw=room();raw.getJSONObject("arcanoid").put("state","WON").put("bricks",0);
        assertEquals("WON",ArcanoidRoomSnapshot.parse(raw).engineState);
        reject(new JSONObject(raw.toString()).put("phase","FINISHED"));
        raw.getJSONObject("arcanoid").put("level",6);raw.put("phase","FINISHED");assertEquals(6,ArcanoidRoomSnapshot.parse(raw).level);
        raw=room().put("phase","FINISHED");raw.getJSONObject("arcanoid").put("state","GAME_OVER").put("lives",0);assertEquals(0,ArcanoidRoomSnapshot.parse(raw).lives);
        raw.getJSONObject("arcanoid").put("lives",1);reject(raw);
    }
    @Test public void moveRetriesKeepImmutableSequenceAndLatestInputCoalesces() throws Exception {
        ArcanoidRealtimeBridge.Move move=new ArcanoidRealtimeBridge.Move("epoch-one",7,.2,100);
        JSONObject first=move.body("game-one");first.put("position",.9);move.attempts++;
        JSONObject retry=move.body("game-one");assertEquals(.2,retry.getDouble("position"),0);assertEquals(7,retry.getLong("sequence"));
        assertFalse(retry.has("player"));assertFalse(retry.has("targetTick"));
        ArcanoidRealtimeBridge.LatestPosition latest=new ArcanoidRealtimeBridge.LatestPosition();latest.offer(.1);latest.offer(.8);latest.offer(Double.NaN);
        assertEquals(.8,latest.take(),0);assertNull(latest.take());latest.offer(2);assertEquals(1,latest.take(),0);latest.offer(.3);latest.clear();assertNull(latest.take());
    }
    private static void reject(JSONObject raw)throws Exception{try{ArcanoidRoomSnapshot.parse(raw);fail("Invalid projection accepted");}catch(JSONException expected){}}
}
