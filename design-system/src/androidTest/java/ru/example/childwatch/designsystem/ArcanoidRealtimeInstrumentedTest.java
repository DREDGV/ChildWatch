package ru.example.childwatch.designsystem;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.Assert.*;

/** Two actual Android Socket.IO clients; fixture memory data never uses working profiles. */
@RunWith(AndroidJUnit4.class)
public class ArcanoidRealtimeInstrumentedTest {
    private interface Condition { boolean ready() throws Exception; }
    private static final class Watch implements ArcanoidRealtimeBridge.Listener {
        final CopyOnWriteArrayList<ArcanoidRoomSnapshot> states = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<String> statuses = new CopyOnWriteArrayList<>();
        volatile ArcanoidRoomSnapshot latest; volatile int player = -1;
        public void onState(ArcanoidRoomSnapshot state) { states.add(state); latest = state; }
        public void onStatus(String code) { statuses.add(code); }
        public void onPlayer(int value) { player = value; }
        boolean phase(String phase) { return latest != null && phase.equals(latest.phase); }
        int count() { return states.size() + statuses.size(); }
    }
    private void until(long deadline,String description,Condition condition) throws Exception {
        while(SystemClock.elapsedRealtime()<deadline){if(condition.ready())return;Thread.sleep(20);}
        fail("Timed out: "+description);
    }
    private JSONObject fixture() throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:8880/fixture").openConnection();
        connection.setConnectTimeout(3000);connection.setReadTimeout(3000);
        try{
            assertEquals(200,connection.getResponseCode());
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
                return new JSONObject(output.toString("UTF-8"));
            }
        }finally{connection.disconnect();}
    }
    private GameOnlineBridge.Host host(JSONObject fixture,String role) throws Exception {
        JSONObject actor=fixture.getJSONObject(role);
        SnakeRealtimeBridge.Credentials credentials=new SnakeRealtimeBridge.Credentials(fixture.getString("url"),
                fixture.getString("familyId"),actor.getString("memberId"),actor.getString("token"));
        return new GameOnlineBridge.Host(){
            public String scopeKey(){return "arcanoid-native-"+role;}
            public JSONObject request(String method,String path,JSONObject body){throw new AssertionError("Paddle movement must not use durable game HTTP");}
            public SnakeRealtimeBridge.Credentials arcanoidCredentials(){return credentials;}
        };
    }
    private boolean coherentMovedPaddles(Watch first,Watch second) throws Exception {
        for(ArcanoidRoomSnapshot a:first.states){
            if(!"PLAYING".equals(a.phase)||a.tick<1||Math.abs(a.paddle(0)-200)>.01||Math.abs(a.paddle(1)-800)>.01)continue;
            for(ArcanoidRoomSnapshot b:second.states)if(a.epoch.equals(b.epoch)&&a.revision==b.revision){
                assertEquals(a.json().getJSONObject("arcanoid").toString(),b.json().getJSONObject("arcanoid").toString());
                assertTrue("Ball physics is advanced by the real server",Math.abs(a.ballY-1228)>1);
                assertEquals(a.localX(a.ballX,1),1000-a.ballX,0);
                assertEquals(a.localY(a.ballY,1),1400-a.ballY,0);
                return true;
            }
        }
        return false;
    }
    @Test public void realClientsSharePhysicsPaddlesPauseAndReconnect() throws Exception {
        long deadline=SystemClock.elapsedRealtime()+25000;
        Instrumentation runner=InstrumentationRegistry.getInstrumentation();Context context=runner.getTargetContext();
        JSONObject fixture=fixture();String id=fixture.getString("gameId");
        Map<String,?> outbox=context.getSharedPreferences("family-games-outbox",0).getAll();
        GameOnlineBridge.Host previous=GameOnlineBridge.host,host=host(fixture,"host"),guest=host(fixture,"guest");
        Watch h=new Watch(),g=new Watch(),replacement=new Watch();ArcanoidRealtimeBridge.Connection[] connections=new ArcanoidRealtimeBridge.Connection[3];
        try{
            runner.runOnMainSync(()->{GameOnlineBridge.host=host;connections[0]=new ArcanoidRealtimeBridge.Connection(id,h);
                GameOnlineBridge.host=guest;connections[1]=new ArcanoidRealtimeBridge.Connection(id,g);connections[0].start();connections[1].start();});
            until(deadline,"both authenticated slots",()->h.player==0&&g.player==1&&h.latest!=null&&h.latest.connected(0)&&h.latest.connected(1));
            connections[0].command("READY");connections[1].command("READY");
            until(deadline,"ready countdown and authoritative play",()->h.phase("PLAYING")&&g.phase("PLAYING"));
            assertTrue(h.states.stream().anyMatch(state->"COUNTDOWN".equals(state.phase)));
            connections[0].move(.2);connections[0].move(.2);connections[1].move(.8);
            until(deadline,"both observe actual moved paddles and shared ball",()->coherentMovedPaddles(h,g));
            assertFalse(h.statuses.toString(),h.statuses.contains("ARKANOID_INVALID_STATE"));
            assertFalse(g.statuses.toString(),g.statuses.contains("ARKANOID_INVALID_STATE"));
            String epoch=h.latest.epoch;connections[1].close();
            until(deadline,"remaining player paused after disconnect",()->h.phase("PAUSED")&&!epoch.equals(h.latest.epoch));
            long tick=h.latest.tick;double x=h.latest.ballX,y=h.latest.ballY;
            Thread.sleep(250);assertEquals(tick,h.latest.tick);assertEquals(x,h.latest.ballX,0);assertEquals(y,h.latest.ballY,0);
            int oldCount=g.count();Thread.sleep(100);assertEquals(oldCount,g.count());
            runner.runOnMainSync(()->{GameOnlineBridge.host=guest;connections[2]=new ArcanoidRealtimeBridge.Connection(id,replacement);connections[2].start();});
            until(deadline,"replacement joins",()->replacement.player==1&&h.latest.connected(1));
            assertNotEquals(epoch,h.latest.epoch);connections[0].command("READY");connections[2].command("READY");
            until(deadline,"consented resume",()->h.phase("PLAYING")&&replacement.phase("PLAYING")&&h.latest.tick>tick);
            connections[0].command("PAUSE");until(deadline,"foreground pause",()->h.phase("PAUSED")&&replacement.phase("PAUSED"));
            runner.runOnMainSync(()->{connections[0].close();connections[2].close();});runner.waitForIdleSync();
            int count0=h.count(),count1=replacement.count();Thread.sleep(350);assertEquals(count0,h.count());assertEquals(count1,replacement.count());
            assertEquals(outbox,context.getSharedPreferences("family-games-outbox",0).getAll());
        }finally{runner.runOnMainSync(()->{for(ArcanoidRealtimeBridge.Connection connection:connections)if(connection!=null)connection.close();GameOnlineBridge.host=previous;});}
    }
}
