package ru.example.childwatch.designsystem;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;
import static org.junit.Assert.*;

public class SnakeRoomSnapshotTest {
    static JSONObject room()throws Exception{
        return new JSONObject().put("protocolVersion",1).put("gameId","game-one").put("epoch","epoch-one").put("revision",1)
            .put("phase","PAUSED").put("reason",JSONObject.NULL).put("ready",new JSONArray().put(false).put(false))
            .put("connected",new JSONArray().put(true).put(true)).put("countdownMs",0)
            .put("snake",new JSONObject().put("columns",20).put("rows",36).put("tick","0").put("score",0)
                .put("state","READY").put("difficulty","NORMAL").put("tickMs",180)
                .put("food",new JSONObject().put("x",10).put("y",4))
                .put("players",new JSONArray().put(new JSONObject().put("direction","RIGHT").put("body",new JSONArray().put(165).put(164).put(163)))
                    .put(new JSONObject().put("direction","LEFT").put("body",new JSONArray().put(354).put(355).put(356)))));
    }
    @Test public void validatedProjectionDefensivelyCopiesCellsAndJson()throws Exception{
        JSONObject raw=room();SnakeRoomSnapshot parsed=SnakeRoomSnapshot.parse(raw);
        raw.put("revision",99);assertEquals(1,parsed.revision);
        int[] body=parsed.body(0);body[0]=0;assertEquals(165,parsed.body(0)[0]);
        JSONObject copy=parsed.json();copy.put("phase","FINISHED");assertEquals("PAUSED",parsed.phase);
    }
    @Test public void orderingRejectsOlderRevisionTickAndUnchangedRevisionMutations()throws Exception{
        SnakeRoomSnapshot base=SnakeRoomSnapshot.parse(room());
        JSONObject newer=room().put("revision",2);assertTrue(SnakeRoomSnapshot.parse(newer).follows(base));
        JSONObject older=room().put("revision",0);assertFalse(SnakeRoomSnapshot.parse(older).follows(base));
        JSONObject changed=room();changed.getJSONArray("ready").put(0,true);assertFalse(SnakeRoomSnapshot.parse(changed).follows(base));
        JSONObject epoch=room().put("epoch","epoch-two");assertFalse(SnakeRoomSnapshot.parse(epoch).follows(base));
        JSONObject laterTick=room().put("revision",2);laterTick.getJSONObject("snake").put("tick","1");
        assertFalse(base.follows(SnakeRoomSnapshot.parse(laterTick)));
    }
    @Test public void fractionalCountdownIsAllowedButBoardNumbersAreStrict()throws Exception{
        JSONObject raw=room().put("phase","COUNTDOWN").put("countdownMs",1530.2);
        assertEquals(1531,SnakeRoomSnapshot.parse(raw).countdownMs);
        raw.put("revision",1.5);reject(raw);
        raw=room();raw.getJSONObject("snake").put("tick",1);reject(raw);
        raw=room();raw.getJSONObject("snake").put("tick","9007199254740991");reject(raw);
    }
    @Test public void rejectsWrongShapeOverlapBrokenBodyAndReverseHead()throws Exception{
        JSONObject raw=room();raw.getJSONObject("snake").put("rows",26);reject(raw);
        raw=room();raw.getJSONObject("snake").getJSONArray("players").getJSONObject(0).getJSONArray("body").put(1,165);reject(raw);
        raw=room();raw.getJSONObject("snake").getJSONArray("players").getJSONObject(0).getJSONArray("body").put(2,100);reject(raw);
        raw=room();raw.getJSONObject("snake").getJSONArray("players").getJSONObject(0).put("direction","LEFT");reject(raw);
    }
    @Test public void rejectsImaginaryScoreOccupiedFoodWrongRadioTypesAndPhase()throws Exception{
        JSONObject raw=room();raw.getJSONObject("snake").put("score",10).put("tickMs",177);reject(raw);
        raw=room();raw.getJSONObject("snake").getJSONObject("food").put("x",5).put("y",8);reject(raw);
        raw=room();raw.getJSONArray("connected").put(0,"true");reject(raw);
        raw=room().put("phase","PLAYING");reject(raw);
        raw=room().put("phase","FINISHED");reject(raw);
    }
    @Test public void duplicateCountdownMayDecreaseWithoutInventedMovement()throws Exception{
        JSONObject one=room().put("phase","COUNTDOWN").put("countdownMs",1900);
        JSONObject two=room().put("phase","COUNTDOWN").put("countdownMs",900);
        assertTrue(SnakeRoomSnapshot.parse(two).follows(SnakeRoomSnapshot.parse(one)));
    }
    private static void reject(JSONObject raw)throws Exception{
        try{SnakeRoomSnapshot.parse(raw);fail("Invalid projection accepted");}catch(JSONException expected){}
    }
}
