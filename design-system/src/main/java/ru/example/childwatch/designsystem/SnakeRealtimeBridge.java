package ru.example.childwatch.designsystem;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.socket.client.Ack;
import io.socket.client.IO;
import io.socket.client.Socket;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URI;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dedicated foreground-only game transport. Never shares the monitoring socket or durable outbox. */
public final class SnakeRealtimeBridge {
    public interface Listener {
        void onState(JSONObject state);
        void onStatus(String code);
        void onPlayer(int player);
    }
    public static final class Credentials {
        public final String serverUrl, familyId, actorMemberId;
        private final String token;
        private final okhttp3.Call.Factory callFactory;
        private final okhttp3.WebSocket.Factory webSocketFactory;
        public Credentials(String serverUrl,String familyId,String actorMemberId,String token) {
            this(serverUrl,familyId,actorMemberId,token,null,null);
        }
        public Credentials(String serverUrl,String familyId,String actorMemberId,String token,
                           okhttp3.Call.Factory callFactory,okhttp3.WebSocket.Factory webSocketFactory) {
            if(serverUrl==null||familyId==null||actorMemberId==null||token==null
                    ||familyId.isEmpty()||actorMemberId.isEmpty()||token.isEmpty())throw new IllegalArgumentException("Game credentials unavailable");
            URI uri=URI.create(serverUrl);
            if(uri.getHost()==null||!("https".equals(uri.getScheme())||"http".equals(uri.getScheme()))
                    ||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("Game URL invalid");
            this.serverUrl=serverUrl.replaceAll("/+$","");this.familyId=familyId;this.actorMemberId=actorMemberId;this.token=token;
            this.callFactory=callFactory;this.webSocketFactory=webSocketFactory;
        }
        String authToken(){return token;}
        okhttp3.Call.Factory callFactory(){return callFactory;}
        okhttp3.WebSocket.Factory webSocketFactory(){return webSocketFactory;}
    }
    /** Pure input lifecycle, deliberately separate from Android scheduling for unit verification. */
    static final class Input {
        final String epoch; final long sequence,targetTick; final SnakeEngine.Direction direction;
        final long startedAt; int attempts;
        Input(String epoch,long sequence,long tick,SnakeEngine.Direction direction,long now){
            this(epoch,sequence,tick,direction,now,tick+2);
        }
        Input(String epoch,long sequence,long tick,SnakeEngine.Direction direction,long now,long target){
            this.epoch=epoch;this.sequence=sequence;targetTick=target;this.direction=direction;startedAt=now;
        }
        boolean valid(String epoch,long now){return this.epoch.equals(epoch)&&now>=startedAt&&now-startedAt<2000;}
        JSONObject body(String gameId)throws JSONException{return new JSONObject().put("gameId",gameId).put("epoch",epoch)
                .put("action","INPUT").put("sequence",sequence).put("targetTick",targetTick).put("direction",direction.name());}
    }
    /** Accepted future turns occupy server queue slots even after their acknowledgements arrive. */
    static final class TargetQueue {
        private final ArrayDeque<Long> targets=new ArrayDeque<>();
        void observe(long tick){while(!targets.isEmpty()&&targets.peekFirst()<=tick)targets.removeFirst();}
        void accepted(long target,long tick){
            observe(tick);
            if(target>tick&&!targets.contains(target))targets.addLast(target);
        }
        long next(long tick,long lastTarget){
            observe(tick);if(targets.size()>=2)return -1;
            long candidate=Math.max(tick+2,lastTarget+1);return candidate<=tick+3?candidate:-1;
        }
        void clear(){targets.clear();}
    }
    public static final class Connection {
        private final String gameId;
        private final Listener listener;
        private final GameOnlineBridge.Host host=GameOnlineBridge.host;
        private final String scope=host==null?"":host.scopeKey();
        private final Handler main=new Handler(Looper.getMainLooper());
        private final ExecutorService credentialsWorker=Executors.newSingleThreadExecutor();
        private final LinkedHashSet<String> retiredEpochs=new LinkedHashSet<>();
        private volatile boolean closed;
        private boolean started,joined,connecting;
        private Socket socket;
        private long generation,lastStateAt,lastPingAt,joinStarted,nextSequence=1;
        private int reconnectSeconds=1,player=-1;
        private SnakeRoomSnapshot accepted;
        private Input pending;
        private final TargetQueue acceptedTargets=new TargetQueue();
        private long lastInputTarget=-1;
        private SnakeEngine.Direction lastInputDirection, desiredDirection;
        private final Runnable heartbeat=this::pulse;
        private final Runnable reconnect=()->connect();
        public Connection(String gameId,Listener listener){
            if(gameId==null||gameId.isEmpty()||gameId.length()>128||listener==null)throw new IllegalArgumentException("Game connection");
            this.gameId=gameId;this.listener=listener;
        }
        public void start(){main.post(()->{if(closed||started)return;started=true;main.post(heartbeat);connect();});}
        public void command(String action){main.post(()->{
            if(!usable())return;
            if(!"READY".equals(action)&&!"PAUSE".equals(action)&&!"PING".equals(action)){status("SNAKE_INVALID_INPUT");return;}
            sendCommand(action);
        });}
        public void turn(SnakeEngine.Direction direction){main.post(()->{
            if(!usable()||direction==null||!"PLAYING".equals(accepted.phase))return;
            if(pending!=null){
                if(desiredDirection==null&&validTurn(pending.direction,direction))desiredDirection=direction;
                return;
            }
            if(desiredDirection!=null)return;
            issueDirection(direction);
        });}
        private static boolean validTurn(SnakeEngine.Direction previous,SnakeEngine.Direction next){
            return previous!=next&&(previous.ordinal()+2)%4!=next.ordinal();
        }
        private void issueDirection(SnakeEngine.Direction direction){
            if(!usable()||pending!=null||!"PLAYING".equals(accepted.phase))return;
            SnakeEngine.Direction previous=lastInputDirection!=null&&lastInputTarget>accepted.tick?lastInputDirection:accepted.direction(player);
            if(!validTurn(previous,direction))return;
            long target=acceptedTargets.next(accepted.tick,lastInputTarget);
            if(target<0){desiredDirection=direction;return;}
            pending=new Input(accepted.epoch,nextSequence,accepted.tick,direction,SystemClock.elapsedRealtime(),target);sendInput(pending);
        }
        private void flushDesired(){
            if(desiredDirection==null||pending!=null||!joined||accepted==null)return;
            SnakeEngine.Direction next=desiredDirection;desiredDirection=null;issueDirection(next);
        }
        private boolean scopeValid(){return host!=null&&!scope.isEmpty()&&scope.equals(host.scopeKey());}
        private boolean usable(){
            if(closed)return false;
            if(!scopeValid()){stop("GAME_CONTEXT_CHANGED");return false;}
            return joined&&socket!=null&&socket.connected()&&accepted!=null;
        }
        private boolean current(long attempt,Socket expected){
            if(closed||attempt!=generation||socket!=expected)return false;
            if(!scopeValid()){stop("GAME_CONTEXT_CHANGED");return false;}return true;
        }
        private void status(String code){if(!closed&&scopeValid())listener.onStatus(code);}
        private void connect(){
            if(closed||connecting)return;
            if(!scopeValid()){stop("GAME_CONTEXT_CHANGED");return;}
            main.removeCallbacks(reconnect); connecting=true; final long attempt=++generation;
            status("CONNECTING");
            credentialsWorker.execute(()->{
                Credentials credentials=null;Exception error=null;
                try{if(!scopeValid())throw new IllegalStateException("context");credentials=host.snakeCredentials();
                    if(!scopeValid())throw new IllegalStateException("context");}
                catch(Exception failure){error=failure;}
                Credentials fresh=credentials;Exception failure=error;
                main.post(()->{
                    if(closed||attempt!=generation)return;
                    connecting=false;
                    if(!scopeValid()){stop("GAME_CONTEXT_CHANGED");return;}
                    if(failure instanceof GameOnlineBridge.Failure){
                        int code=((GameOnlineBridge.Failure)failure).status;
                        if(code==501){stop("SNAKE_UNSUPPORTED");return;}
                        if(code==403||code==404){stop("GAME_PARTICIPANT_UNAVAILABLE");return;}
                    }
                    if(failure!=null||fresh==null){scheduleReconnect("SNAKE_UNAVAILABLE");return;}
                    open(attempt,fresh);
                });
            });
        }
        private void open(long attempt,Credentials credentials){
            try{
                IO.Options options=new IO.Options();options.forceNew=true;options.reconnection=false;options.timeout=8000;
                options.auth=Collections.singletonMap("token",credentials.token);
                options.transports=new String[]{"websocket"};
                if(credentials.callFactory!=null)options.callFactory=credentials.callFactory;
                if(credentials.webSocketFactory!=null)options.webSocketFactory=credentials.webSocketFactory;
                final Socket created=IO.socket(credentials.serverUrl+"/family-games",options);socket=created;
                created.on(Socket.EVENT_CONNECT,args->main.post(()->{
                    if(!current(attempt,created))return;
                    joinStarted=SystemClock.elapsedRealtime();
                    try{JSONObject body=new JSONObject().put("gameId",gameId).put("familyId",credentials.familyId).put("actorMemberId",credentials.actorMemberId);
                        created.emit("snake:join",body,(Ack)reply->main.post(()->joinAck(attempt,created,reply)));}
                    catch(JSONException invalid){scheduleReconnect("SNAKE_INVALID_STATE");}
                }));
                created.on("snake:state",args->main.post(()->{
                    if(!current(attempt,created))return;
                    JSONObject value=object(args);
                    if(value==null){scheduleReconnect("SNAKE_INVALID_STATE");return;}
                    if(value.has("code")){failure(value.optString("code","SNAKE_UNAVAILABLE"));return;}
                    accept(value);
                }));
                created.on(Socket.EVENT_DISCONNECT,args->main.post(()->{if(current(attempt,created))scheduleReconnect("DISCONNECTED");}));
                created.on(Socket.EVENT_CONNECT_ERROR,args->main.post(()->{if(current(attempt,created))scheduleReconnect("SNAKE_UNAVAILABLE");}));
                joinStarted=SystemClock.elapsedRealtime();created.connect();
            }catch(Exception error){scheduleReconnect("SNAKE_UNAVAILABLE");}
        }
        private void joinAck(long attempt,Socket created,Object[] args){
            if(!current(attempt,created))return;
            JSONObject value=object(args);
            if(value==null){scheduleReconnect("SNAKE_INVALID_STATE");return;}
            if(!value.optBoolean("success",false)){failure(value.optString("code","SNAKE_UNAVAILABLE"));return;}
            Object slot=value.opt("player");
            if(!(slot instanceof Number)||((Number)slot).intValue()!=((Number)slot).doubleValue()
                    ||((Number)slot).intValue()<0||((Number)slot).intValue()>1){scheduleReconnect("SNAKE_INVALID_STATE");return;}
            if(!accept(value)&&accepted==null)return;
            player=((Number)slot).intValue();joined=true;reconnectSeconds=1;lastPingAt=SystemClock.elapsedRealtime();
            listener.onPlayer(player);status("CONNECTED");
        }
        private boolean accept(JSONObject value){
            try{
                SnakeRoomSnapshot next=SnakeRoomSnapshot.parse(value);
                if(!gameId.equals(next.gameId))throw new JSONException("Wrong game");
                if(accepted!=null){
                    if(next.epoch.equals(accepted.epoch)){if(!next.follows(accepted))return false;}
                    else{
                        if(retiredEpochs.contains(next.epoch))return false;
                        retiredEpochs.add(accepted.epoch);while(retiredEpochs.size()>32)retiredEpochs.remove(retiredEpochs.iterator().next());
                        pending=null;nextSequence=1;lastInputTarget=-1;lastInputDirection=null;desiredDirection=null;acceptedTargets.clear();
                    }
                }
                accepted=next;acceptedTargets.observe(next.tick);lastStateAt=SystemClock.elapsedRealtime();
                listener.onState(next.json());flushDesired();return true;
            }catch(JSONException invalid){scheduleReconnect("SNAKE_INVALID_STATE");return false;}
        }
        private void sendCommand(String action){
            if(!usable())return;
            emitControl(action,accepted.epoch,generation,socket,SystemClock.elapsedRealtime()+2000,0);
        }
        private void emitControl(String action,String epoch,long attempt,Socket created,long deadline,int retries){
            if(!current(attempt,created)||!joined||accepted==null||!epoch.equals(accepted.epoch))return;
            try{
                JSONObject body=new JSONObject().put("gameId",gameId).put("epoch",epoch).put("action",action);
                created.emit("snake:command",body,(Ack)args->main.post(()->{
                    if(!current(attempt,created)||accepted==null||!epoch.equals(accepted.epoch))return;
                    JSONObject value=object(args);
                    if(value==null){status("SNAKE_INVALID_STATE");return;}
                    if(value.optBoolean("success",false)){accept(value);return;}
                    String code=value.optString("code","SNAKE_UNAVAILABLE");
                    if("SNAKE_BUSY".equals(code)){
                        if(retries<6&&SystemClock.elapsedRealtime()<deadline)
                            main.postDelayed(()->emitControl(action,epoch,attempt,created,deadline,retries+1),250);
                        else if(!"PING".equals(action))status("SNAKE_COMMAND_TIMEOUT");
                    }else failure(code);
                }));
            }catch(JSONException invalid){status("SNAKE_INVALID_INPUT");}
        }
        private void sendInput(Input input){
            if(!usable()||pending!=input||!input.valid(accepted.epoch,SystemClock.elapsedRealtime()))return;
            try{
                long attempt=generation;Socket created=socket;input.attempts++;
                created.emit("snake:command",input.body(gameId),(Ack)args->main.post(()->{
                    if(!current(attempt,created)||pending!=input)return;
                    JSONObject value=object(args);if(value==null)return;
                    if(value.optBoolean("success",false)){
                        accept(value);
                        if(pending==input&&input.epoch.equals(accepted.epoch)){
                            Object sequence=value.opt("sequence");
                            if(!(sequence instanceof Number)||((Number)sequence).longValue()!=input.sequence){scheduleReconnect("SNAKE_SEQUENCE_CONFLICT");return;}
                            pending=null;nextSequence=input.sequence+1;lastInputTarget=input.targetTick;lastInputDirection=input.direction;acceptedTargets.accepted(input.targetTick,accepted.tick);flushDesired();
                        }
                    }else{
                        String code=value.optString("code","SNAKE_UNAVAILABLE");
                        if("SNAKE_BUSY".equals(code)||"SNAKE_UNAVAILABLE".equals(code))return;
                        pending=null;
                        if("SNAKE_SEQUENCE_CONFLICT".equals(code)||"SNAKE_SEQUENCE_REUSED".equals(code)){scheduleReconnect(code);return;}
                        status(code);sendCommand("PING");
                    }
                }));
            }catch(JSONException invalid){pending=null;status("SNAKE_INVALID_INPUT");}
        }
        private void pulse(){
            if(closed)return;
            if(!scopeValid()){stop("GAME_CONTEXT_CHANGED");return;}
            long now=SystemClock.elapsedRealtime();
            if(socket!=null&&!joined&&now-joinStarted>=8000)scheduleReconnect("SNAKE_UNAVAILABLE");
            if(joined){
                if(now-lastStateAt>=12000){scheduleReconnect("STALE");}
                else{
                    if(now-lastPingAt>=5000){lastPingAt=now;sendCommand("PING");}
                    if(pending!=null){
                        if(!pending.valid(accepted.epoch,now)){pending=null;scheduleReconnect("SNAKE_INPUT_TIMEOUT");}
                        else if(pending.attempts<3&&now-pending.startedAt>=pending.attempts*500L)sendInput(pending);
                    }
                }
            }
            if(!closed)main.postDelayed(heartbeat,250);
        }
        private void failure(String code){
            if(code.equals("GAME_CONTEXT_CHANGED")||code.equals("SNAKE_CONNECTION_REPLACED")||code.equals("GAME_ACCESS_DENIED")
                    ||code.equals("GAME_FORBIDDEN")||code.equals("GAME_CLOSED")||code.equals("GAME_NOT_FOUND")
                    ||code.equals("GAME_PARTICIPANT_UNAVAILABLE")||code.equals("SNAKE_UNSUPPORTED")){stop(code);return;}
            scheduleReconnect(code);
        }
        private void scheduleReconnect(String code){
            if(closed)return;
            freeze();generation++;connecting=false;dispose(false);status(code);
            main.removeCallbacks(reconnect);main.postDelayed(reconnect,reconnectSeconds*1000L);reconnectSeconds=Math.min(8,reconnectSeconds*2);
        }
        private void freeze(){joined=false;accepted=null;pending=null;nextSequence=1;lastInputTarget=-1;lastInputDirection=null;desiredDirection=null;acceptedTargets.clear();player=-1;retiredEpochs.clear();lastStateAt=0;}
        private void dispose(boolean leave){
            Socket old=socket;socket=null;if(old==null)return;
            if(leave&&old.connected())old.emit("snake:leave",new JSONObject());
            old.off();old.disconnect();
        }
        private void stop(String code){
            if(closed)return;
            listener.onStatus(code);closed=true;generation++;main.removeCallbacksAndMessages(null);freeze();dispose(true);credentialsWorker.shutdownNow();
        }
        public void close(){
            closed=true;
            main.post(()->{generation++;main.removeCallbacksAndMessages(null);freeze();dispose(true);credentialsWorker.shutdownNow();});
        }
        private static JSONObject object(Object[] args){
            if(args==null||args.length==0)return null;
            try{return args[0] instanceof JSONObject?new JSONObject(args[0].toString()):args[0] instanceof String?new JSONObject((String)args[0]):null;}
            catch(JSONException invalid){return null;}
        }
    }
}
