package ru.example.childwatch.designsystem;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Isolated test application only: no credentials, account or monitoring connection. */
public class ArcanoidOnlineFixtureActivity extends ArcanoidOnlineActivity {
    static volatile float requestedFontScale=1f;
    static volatile String requestedPeerName="Мама";
    FakeSession fake;int opened,closed;
    @Override protected void attachBaseContext(android.content.Context context){
        android.content.res.Configuration config=new android.content.res.Configuration(context.getResources().getConfiguration());config.fontScale=requestedFontScale;
        super.attachBaseContext(context.createConfigurationContext(config));
    }
    @Override protected Session createSession(String id,ArcanoidRealtimeBridge.Listener listener){opened++;fake=new FakeSession(id,listener);return fake;}
    @Override protected void loadNames(int generation){setPeerName(requestedPeerName);}
    final class FakeSession implements Session {
        final String id,epoch=UUID.randomUUID().toString();final ArcanoidRealtimeBridge.Listener listener;
        final List<String> commands=new ArrayList<>();final List<Double> moves=new ArrayList<>();long revision;
        FakeSession(String id,ArcanoidRealtimeBridge.Listener listener){this.id=id;this.listener=listener;}
        public void start(){listener.onPlayer(1);emit("PAUSED",0,epoch,false,1);listener.onStatus("CONNECTED");}
        public void command(String action){commands.add(action);if("READY".equals(action))emit("PAUSED",0,epoch,true,1);}
        public void move(double position){moves.add(position);}
        public void close(){closed++;}
        void emit(String phase,long tick,String epoch,boolean ready,int level){
            try{
                boolean won=level>1;
                JSONObject game=new JSONObject().put("tick",tick).put("width",1000).put("height",1400).put("radius",20)
                    .put("paddleWidth",190).put("paddleHeight",22).put("paddles",new JSONArray().put(500).put(700))
                    .put("paddleY",new JSONArray().put(1250).put(150)).put("ball",new JSONObject().put("x",500+tick*10).put("y",900).put("vx",200).put("vy",300))
                    .put("bricks",won?0:0x3fffffff).put("level",level).put("levelCount",6).put("lives",3).put("score",won?300:40)
                    .put("state",won?"WON":"PLAYING".equals(phase)?"PLAYING":"PAUSED".equals(phase)?"PAUSED":"READY");
                listener.onState(ArcanoidRoomSnapshot.parse(new JSONObject().put("protocolVersion",1).put("gameId",id).put("epoch",epoch).put("revision",++revision)
                    .put("phase",phase).put("reason",won?"LEVEL_COMPLETE":"PAUSED".equals(phase)?"PLAYER_PAUSED":JSONObject.NULL)
                    .put("countdownMs","COUNTDOWN".equals(phase)?2000:0).put("ready",new JSONArray().put(ready).put(ready))
                    .put("connected",new JSONArray().put(true).put(true)).put("arcanoid",game)));
            }catch(Exception invalid){throw new AssertionError(invalid);}
        }
    }
}
