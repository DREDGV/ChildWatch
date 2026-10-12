package ru.example.childwatch.designsystem;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Independent foreground-only socket; latest normalized paddle input, never a durable outbox. */
public final class ArcanoidRealtimeBridge {
    public interface Listener {void onState(ArcanoidRoomSnapshot state);void onStatus(String code);void onPlayer(int player);}
    static final class Move {
        final String epoch;final long sequence,started;final double position;int attempts;
        Move(String epoch,long sequence,double position,long started){this.epoch=epoch;this.sequence=sequence;this.position=position;this.started=started;}
        JSONObject body(String gameId)throws JSONException{return new JSONObject().put("gameId",gameId).put("epoch",epoch).put("action","MOVE").put("sequence",sequence).put("position",position);}
    }
    static final class LatestPosition {
        private Double latest;
        void offer(double position){if(Double.isFinite(position))latest=Math.max(0,Math.min(1,position));}
        Double take(){Double value=latest;latest=null;return value;}
        void clear(){latest=null;}
    }
    public static final class Connection {
        private final String gameId;private final Listener listener;private final GameOnlineBridge.Host host=GameOnlineBridge.host;
        private final String scope=host==null?"":host.scopeKey();private final Handler main=new Handler(Looper.getMainLooper());
        private final ExecutorService worker=Executors.newSingleThreadExecutor();private final LinkedHashSet<String> retired=new LinkedHashSet<>();
        private final LatestPosition desired=new LatestPosition();private volatile boolean closed;private boolean started,joined,connecting;
        private Socket socket;private ArcanoidRoomSnapshot accepted;private Move pending;
        private long generation,lastState,lastPing,joinStarted,nextSequence=1,lastMoveSent;private int reconnectSeconds=1;
        private final Runnable heartbeat=this::pulse,reconnect=this::connect;
        public Connection(String id,Listener listener){if(id==null||id.isEmpty()||id.length()>128||listener==null)throw new IllegalArgumentException("Game connection");gameId=id;this.listener=listener;}
        public void start(){main.post(()->{if(closed||started)return;started=true;main.post(heartbeat);connect();});}
        public void move(double normalized){main.post(()->{if(!usable()||!"PLAYING".equals(accepted.phase)||!Double.isFinite(normalized))return;desired.offer(normalized);flush();});}
        public void command(String action){main.post(()->{if(!usable())return;if(!"READY".equals(action)&&!"PAUSE".equals(action)&&!"PING".equals(action)){status("ARKANOID_INVALID_INPUT");return;}control(action);});}
        private boolean scopeValid(){return host!=null&&!scope.isEmpty()&&scope.equals(host.scopeKey());}
        private boolean current(long attempt,Socket expected){if(closed||attempt!=generation||socket!=expected)return false;if(!scopeValid()){stop("GAME_CONTEXT_CHANGED");return false;}return true;}
        private boolean usable(){if(closed)return false;if(!scopeValid()){stop("GAME_CONTEXT_CHANGED");return false;}return joined&&socket!=null&&socket.connected()&&accepted!=null;}
        private void status(String code){if(!closed&&scopeValid())listener.onStatus(code);}
        private void connect(){
            if(closed||connecting)return;if(!scopeValid()){stop("GAME_CONTEXT_CHANGED");return;}main.removeCallbacks(reconnect);connecting=true;long attempt=++generation;status("CONNECTING");
            worker.execute(()->{
                SnakeRealtimeBridge.Credentials value=null;Exception error=null;
                try{if(!scopeValid())throw new IllegalStateException("context");value=host.arcanoidCredentials();if(!scopeValid())throw new IllegalStateException("context");}catch(Exception failure){error=failure;}
                SnakeRealtimeBridge.Credentials credentials=value;Exception failure=error;
                main.post(()->{if(closed||attempt!=generation)return;connecting=false;if(!scopeValid()){stop("GAME_CONTEXT_CHANGED");return;}
                    if(failure instanceof GameOnlineBridge.Failure){int code=((GameOnlineBridge.Failure)failure).status;if(code==501){stop("ARKANOID_UNSUPPORTED");return;}if(code==403||code==404){stop("GAME_PARTICIPANT_UNAVAILABLE");return;}}
                    if(failure!=null||credentials==null){reconnect("ARKANOID_UNAVAILABLE");return;}open(attempt,credentials);
                });
            });
        }
        private void open(long attempt,SnakeRealtimeBridge.Credentials credentials){
            try{
                IO.Options options=new IO.Options();options.forceNew=true;options.reconnection=false;options.timeout=8000;options.transports=new String[]{"websocket"};options.auth=Collections.singletonMap("token",credentials.authToken());
                if(credentials.callFactory()!=null)options.callFactory=credentials.callFactory();if(credentials.webSocketFactory()!=null)options.webSocketFactory=credentials.webSocketFactory();
                Socket created=IO.socket(credentials.serverUrl+"/family-games",options);socket=created;joinStarted=SystemClock.elapsedRealtime();
                created.on(Socket.EVENT_CONNECT,args->main.post(()->{if(!current(attempt,created))return;joinStarted=SystemClock.elapsedRealtime();try{created.emit("arkanoid:join",new JSONObject().put("gameId",gameId).put("familyId",credentials.familyId).put("actorMemberId",credentials.actorMemberId),(Ack)reply->main.post(()->joinAck(attempt,created,reply)));}catch(JSONException error){reconnect("ARKANOID_INVALID_STATE");}}));
                created.on("arkanoid:state",args->main.post(()->{if(!current(attempt,created))return;JSONObject value=object(args);if(value==null){reconnect("ARKANOID_INVALID_STATE");return;}if(value.has("code")){failure(value.optString("code","ARKANOID_UNAVAILABLE"));return;}accept(value);}));
                created.on(Socket.EVENT_DISCONNECT,args->main.post(()->{if(current(attempt,created))reconnect("DISCONNECTED");}));
                created.on(Socket.EVENT_CONNECT_ERROR,args->main.post(()->{if(current(attempt,created))reconnect("ARKANOID_UNAVAILABLE");}));created.connect();
            }catch(Exception invalid){reconnect("ARKANOID_UNAVAILABLE");}
        }
        private void joinAck(long attempt,Socket created,Object[] args){
            if(!current(attempt,created))return;JSONObject value=object(args);if(value==null){reconnect("ARKANOID_INVALID_STATE");return;}if(!value.optBoolean("success")){failure(value.optString("code","ARKANOID_UNAVAILABLE"));return;}
            Object slot=value.opt("player");if(!(slot instanceof Number)||((Number)slot).doubleValue()!=((Number)slot).intValue()||((Number)slot).intValue()<0||((Number)slot).intValue()>1){reconnect("ARKANOID_INVALID_STATE");return;}
            if(!accept(value)&&accepted==null)return;joined=true;reconnectSeconds=1;lastPing=SystemClock.elapsedRealtime();listener.onPlayer(((Number)slot).intValue());status("CONNECTED");
        }
        private boolean accept(JSONObject value){
            try{
                ArcanoidRoomSnapshot next=ArcanoidRoomSnapshot.parse(value);if(!gameId.equals(next.gameId))throw new JSONException("Wrong game");
                if(accepted!=null){if(next.epoch.equals(accepted.epoch)){if(!next.follows(accepted))return false;}else{if(retired.contains(next.epoch))return false;retired.add(accepted.epoch);while(retired.size()>32)retired.remove(retired.iterator().next());pending=null;desired.clear();nextSequence=1;}}
                accepted=next;lastState=SystemClock.elapsedRealtime();if(!"PLAYING".equals(next.phase)){pending=null;desired.clear();}listener.onState(next);return true;
            }catch(JSONException invalid){reconnect("ARKANOID_INVALID_STATE");return false;}
        }
        private void flush(){
            if(!usable()||!"PLAYING".equals(accepted.phase)||pending!=null||SystemClock.elapsedRealtime()-lastMoveSent<50)return;Double position=desired.take();if(position==null)return;
            pending=new Move(accepted.epoch,nextSequence,position,SystemClock.elapsedRealtime());lastMoveSent=pending.started;sendMove(pending);
        }
        private void sendMove(Move move){
            if(!usable()||pending!=move||!accepted.epoch.equals(move.epoch))return;long attempt=generation;Socket created=socket;move.attempts++;
            try{created.emit("arkanoid:command",move.body(gameId),(Ack)args->main.post(()->{
                if(!current(attempt,created)||pending!=move)return;JSONObject result=object(args);if(result==null)return;
                if(result.optBoolean("success")){accept(result);if(pending==move&&accepted!=null&&move.epoch.equals(accepted.epoch)){
                    Object sequence=result.opt("sequence");if(!(sequence instanceof Number)||((Number)sequence).doubleValue()!=move.sequence){reconnect("ARKANOID_SEQUENCE_CONFLICT");return;}
                    pending=null;nextSequence=move.sequence+1;flush();
                }}else{String code=result.optString("code","ARKANOID_UNAVAILABLE");if("ARKANOID_BUSY".equals(code)||"ARKANOID_UNAVAILABLE".equals(code))return;pending=null;failure(code);}
            }));}catch(JSONException invalid){reconnect("ARKANOID_INVALID_INPUT");}
        }
        private void control(String action){long attempt=generation;Socket created=socket;String epoch=accepted.epoch;long deadline=SystemClock.elapsedRealtime()+2000;boolean[] done={false};
            main.postDelayed(()->{if(!done[0]&&current(attempt,created)&&accepted!=null&&epoch.equals(accepted.epoch)&&!"PING".equals(action)){done[0]=true;status("ARKANOID_COMMAND_TIMEOUT");}},2000);
            emitControl(action,epoch,attempt,created,deadline,0,done);
        }
        private void emitControl(String action,String epoch,long attempt,Socket created,long deadline,int retries,boolean[] done){
            if(done[0]||!current(attempt,created)||accepted==null||!epoch.equals(accepted.epoch))return;
            try{created.emit("arkanoid:command",new JSONObject().put("gameId",gameId).put("epoch",epoch).put("action",action),(Ack)args->main.post(()->{
                if(done[0]||!current(attempt,created)||accepted==null||!epoch.equals(accepted.epoch))return;JSONObject value=object(args);if(value==null)return;
                if(value.optBoolean("success")){done[0]=true;accept(value);return;}
                String code=value.optString("code","ARKANOID_UNAVAILABLE");
                if("ARKANOID_BUSY".equals(code)&&retries<6&&SystemClock.elapsedRealtime()<deadline){main.postDelayed(()->emitControl(action,epoch,attempt,created,deadline,retries+1,done),250);return;}
                done[0]=true;if("ARKANOID_BUSY".equals(code))status("ARKANOID_COMMAND_TIMEOUT");else failure(code);
            }));}catch(JSONException invalid){done[0]=true;reconnect("ARKANOID_INVALID_INPUT");}
        }
        private void pulse(){
            if(closed)return;if(!scopeValid()){stop("GAME_CONTEXT_CHANGED");return;}long now=SystemClock.elapsedRealtime();
            if(socket!=null&&!joined&&now-joinStarted>=8000)reconnect("ARKANOID_UNAVAILABLE");
            if(joined){if(now-lastState>=12000)reconnect("STALE");else{if(now-lastPing>=5000){lastPing=now;control("PING");}if(pending!=null){if(now-pending.started>=1200)reconnect("ARKANOID_MOVE_TIMEOUT");else if(pending.attempts<3&&now-pending.started>=pending.attempts*300L)sendMove(pending);}flush();}}
            if(!closed)main.postDelayed(heartbeat,25);
        }
        private void failure(String code){if(code.startsWith("GAME_")||"ARKANOID_CONNECTION_REPLACED".equals(code)||"ARKANOID_UNSUPPORTED".equals(code)){stop(code);return;}reconnect(code);}
        private void reconnect(String code){if(closed)return;generation++;connecting=false;freeze();dispose(false);status(code);main.removeCallbacks(reconnect);main.postDelayed(reconnect,reconnectSeconds*1000L);reconnectSeconds=Math.min(8,reconnectSeconds*2);}
        private void freeze(){joined=false;accepted=null;pending=null;desired.clear();nextSequence=1;lastState=0;lastMoveSent=0;retired.clear();}
        private void dispose(boolean leave){Socket old=socket;socket=null;if(old==null)return;if(leave&&old.connected())old.emit("arkanoid:leave",new JSONObject());old.off();old.disconnect();}
        private void stop(String code){if(closed)return;listener.onStatus(code);closed=true;generation++;main.removeCallbacksAndMessages(null);freeze();dispose(true);worker.shutdownNow();}
        public void close(){closed=true;main.post(()->{generation++;main.removeCallbacksAndMessages(null);freeze();dispose(true);worker.shutdownNow();});}
        private static JSONObject object(Object[] values){if(values==null||values.length==0)return null;try{return values[0]instanceof JSONObject?new JSONObject(values[0].toString()):values[0]instanceof String?new JSONObject((String)values[0]):null;}catch(JSONException invalid){return null;}}
    }
}
