package ru.example.childwatch.designsystem;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Arrays;

/** Immutable validated authoritative projection; never advances local game physics. */
public final class ArcanoidRoomSnapshot {
    public static final long MAX_SAFE_INTEGER=9007199254740991L;
    public final String gameId,epoch,phase,reason,engineState;
    public final long revision,tick,countdownMs;
    public final int bricks,level,levelCount,lives,score;
    public final double ballX,ballY,velocityX,velocityY;
    private final double[] paddles,paddleY;
    private final boolean[] ready,connected;
    private final JSONObject json;
    private ArcanoidRoomSnapshot(JSONObject value)throws JSONException{
        json=new JSONObject(value.toString());integer(json,"protocolVersion",1,1);
        gameId=text(json,"gameId",128);epoch=text(json,"epoch",128);revision=integer(json,"revision",0,MAX_SAFE_INTEGER);
        phase=choice(json,"phase","PAUSED","COUNTDOWN","PLAYING","FINISHED");
        reason=json.isNull("reason")?null:text(json,"reason",80);ready=pair(json,"ready");connected=pair(json,"connected");
        double countdown=number(json.get("countdownMs"),0,2500);if(!"COUNTDOWN".equals(phase)&&countdown!=0)throw invalid();countdownMs=(long)Math.ceil(countdown);
        JSONObject game=json.getJSONObject("arcanoid");
        integer(game,"width",1000,1000);integer(game,"height",1400,1400);integer(game,"radius",20,20);integer(game,"paddleWidth",190,190);integer(game,"paddleHeight",22,22);
        tick=integer(game,"tick",0,MAX_SAFE_INTEGER);bricks=(int)integer(game,"bricks",0,0x3fffffff);
        level=(int)integer(game,"level",1,6);levelCount=(int)integer(game,"levelCount",6,6);lives=(int)integer(game,"lives",0,3);score=(int)integer(game,"score",0,Integer.MAX_VALUE);
        engineState=choice(game,"state","READY","PLAYING","PAUSED","WON","GAME_OVER");
        paddles=numericPair(game,"paddles",95,905);paddleY=numericPair(game,"paddleY",0,1400);if(paddleY[0]!=1250||paddleY[1]!=150)throw invalid();
        JSONObject ball=game.getJSONObject("ball");ballX=number(ball.get("x"),0,1000);ballY=number(ball.get("y"),-20,1420);velocityX=number(ball.get("vx"),-5000,5000);velocityY=number(ball.get("vy"),-5000,5000);
        if("PLAYING".equals(phase)&&!"PLAYING".equals(engineState))throw invalid();
        if("FINISHED".equals(phase)&&!("GAME_OVER".equals(engineState)||"WON".equals(engineState)&&level==6))throw invalid();
        if(("PAUSED".equals(phase)||"COUNTDOWN".equals(phase))&&!("READY".equals(engineState)||"PAUSED".equals(engineState)||"WON".equals(engineState)&&level<6))throw invalid();
        if("GAME_OVER".equals(engineState)&&lives!=0||!"GAME_OVER".equals(engineState)&&lives==0||"WON".equals(engineState)&&bricks!=0)throw invalid();
    }
    public static ArcanoidRoomSnapshot parse(JSONObject input)throws JSONException{if(input==null||input.toString().length()>10000)throw invalid();return new ArcanoidRoomSnapshot(input);}
    public boolean follows(ArcanoidRoomSnapshot previous){
        if(previous==null)return true;if(!gameId.equals(previous.gameId)||!epoch.equals(previous.epoch)||revision<previous.revision||tick<previous.tick)return false;
        if(revision==previous.revision)return same(previous);return true;
    }
    private boolean same(ArcanoidRoomSnapshot old){return tick==old.tick&&bricks==old.bricks&&level==old.level&&lives==old.lives&&score==old.score&&phase.equals(old.phase)&&engineState.equals(old.engineState)
        &&ballX==old.ballX&&ballY==old.ballY&&velocityX==old.velocityX&&velocityY==old.velocityY&&Arrays.equals(paddles,old.paddles)&&Arrays.equals(ready,old.ready)&&Arrays.equals(connected,old.connected);}
    public double paddle(int player){return paddles[player];}
    public double paddleY(int player){return paddleY[player];}
    public boolean ready(int player){return ready[player];}
    public boolean connected(int player){return connected[player];}
    public JSONObject json()throws JSONException{return new JSONObject(json.toString());}
    public double localX(double x,int player){return player==1?1000-x:x;}
    public double localY(double y,int player){return player==1?1400-y:y;}
    public static double serverPosition(double local,int player){if(!Double.isFinite(local)||player<0||player>1)throw new IllegalArgumentException("Paddle position");double clamped=Math.max(0,Math.min(1,local));return player==1?1-clamped:clamped;}
    private static boolean[] pair(JSONObject value,String key)throws JSONException{JSONArray data=value.getJSONArray(key);if(data.length()!=2)throw invalid();boolean[] result=new boolean[2];for(int i=0;i<2;i++){if(!(data.get(i)instanceof Boolean))throw invalid();result[i]=data.getBoolean(i);}return result;}
    private static double[] numericPair(JSONObject value,String key,double min,double max)throws JSONException{JSONArray data=value.getJSONArray(key);if(data.length()!=2)throw invalid();return new double[]{number(data.get(0),min,max),number(data.get(1),min,max)};}
    private static String text(JSONObject value,String key,int max)throws JSONException{Object data=value.get(key);if(!(data instanceof String)||((String)data).isEmpty()||((String)data).length()>max)throw invalid();return(String)data;}
    private static String choice(JSONObject value,String key,String...options)throws JSONException{String data=text(value,key,40);if(!Arrays.asList(options).contains(data))throw invalid();return data;}
    private static long integer(JSONObject value,String key,long min,long max)throws JSONException{Object data=value.get(key);double number=number(data,min,max);long result=((Number)data).longValue();if(number!=result)throw invalid();return result;}
    private static double number(Object data,double min,double max)throws JSONException{if(!(data instanceof Number))throw invalid();double result=((Number)data).doubleValue();if(!Double.isFinite(result)||result<min||result>max)throw invalid();return result;}
    private static JSONException invalid(){return new JSONException("Invalid family Arkanoid snapshot");}
}
