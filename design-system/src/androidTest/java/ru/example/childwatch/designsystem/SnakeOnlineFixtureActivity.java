package ru.example.childwatch.designsystem;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Test application only: protocol snapshots, no credentials or monitoring connection. */
public class SnakeOnlineFixtureActivity extends SnakeOnlineActivity {
    static volatile float requestedFontScale=1.5f;
    static volatile String requestedPeerName="";
    FakeSession fake;
    int opened,closed;
    @Override protected void attachBaseContext(android.content.Context context){
        android.content.res.Configuration config=new android.content.res.Configuration(context.getResources().getConfiguration());config.fontScale=requestedFontScale;
        super.attachBaseContext(context.createConfigurationContext(config));
    }
    @Override protected Session createSession(String id,SnakeRealtimeBridge.Listener listener){opened++;fake=new FakeSession(id,listener);return fake;}
    @Override protected void loadNames(int generation){setPeerName(requestedPeerName);}
    final class FakeSession implements Session {
        final String id,epoch=UUID.randomUUID().toString();final SnakeRealtimeBridge.Listener listener;
        final List<String> commands=new ArrayList<>();final List<SnakeEngine.Direction> turns=new ArrayList<>();
        FakeSession(String id,SnakeRealtimeBridge.Listener listener){this.id=id;this.listener=listener;}
        public void start(){listener.onPlayer(1);emit("PAUSED",0,epoch,false);listener.onStatus("CONNECTED");}
        public void command(String action){commands.add(action);if("READY".equals(action))emit("PAUSED",0,epoch,true);}
        public void turn(SnakeEngine.Direction direction){turns.add(direction);}
        public void close(){closed++;}
        void emit(String phase,long tick,String epoch,boolean ready){
            try{
                int a=tick==0?165:166,b=tick==0?354:374;
                JSONObject snake=new JSONObject().put("columns",20).put("rows",36).put("tick",Long.toString(tick)).put("score",0)
                    .put("state","PLAYING".equals(phase)?"PLAYING":"COUNTDOWN".equals(phase)?"READY":"PAUSED")
                    .put("difficulty","NORMAL").put("tickMs",180).put("food",new JSONObject().put("x",10).put("y",20))
                    .put("players",new JSONArray().put(new JSONObject().put("body",new JSONArray().put(a).put(tick==0?164:165).put(tick==0?163:164)).put("direction","RIGHT"))
                        .put(new JSONObject().put("body",new JSONArray().put(b).put(tick==0?355:354).put(tick==0?356:355)).put("direction",tick==0?"LEFT":"DOWN")));
                listener.onState(new JSONObject().put("protocolVersion",1).put("gameId",id).put("epoch",epoch).put("revision",tick+1)
                    .put("phase",phase).put("reason","PAUSED".equals(phase)?"PLAYER_PAUSED":JSONObject.NULL)
                    .put("countdownMs","COUNTDOWN".equals(phase)?2000:0).put("ready",new JSONArray().put(false).put(ready))
                    .put("connected",new JSONArray().put(true).put(true)).put("snake",snake));
            }catch(Exception invalid){throw new AssertionError(invalid);}
        }
    }
}
