package ru.example.childwatch.designsystem;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Strict server projection. No predicted cells, Java simulation or wall-clock extrapolation. */
public final class SnakeRoomSnapshot {
    public static final long MAX_SAFE_INTEGER = 9007199254740991L;
    public final String gameId, epoch, phase, reason, difficulty, engineState;
    public final long revision, tick, countdownMs;
    public final int score, tickMs, foodX, foodY;
    private final boolean[] ready, connected;
    private final int[][] bodies;
    private final SnakeEngine.Direction[] directions;
    private final JSONObject json;
    private SnakeRoomSnapshot(JSONObject input) throws JSONException {
        json = new JSONObject(input.toString());
        if (integer(json,"protocolVersion",1,1) != 1) throw invalid();
        gameId = text(json,"gameId",128); epoch = text(json,"epoch",128);
        revision = integer(json,"revision",0,MAX_SAFE_INTEGER);
        phase = choice(json,"phase",new String[]{"PAUSED","COUNTDOWN","PLAYING","FINISHED"});
        Object cause = json.opt("reason");
        reason = cause == null || cause == JSONObject.NULL ? null : text(json,"reason",80);
        ready = pair(json,"ready"); connected = pair(json,"connected");
        Object countdown = json.get("countdownMs");
        if (!(countdown instanceof Number)) throw invalid();
        double duration = ((Number)countdown).doubleValue();
        if (!Double.isFinite(duration) || duration < 0 || duration > 2500 || (!phase.equals("COUNTDOWN") && duration != 0)) throw invalid();
        countdownMs = (long)Math.ceil(duration);
        JSONObject snake = json.getJSONObject("snake");
        integer(snake,"columns",SnakeEngine.COLUMNS,SnakeEngine.COLUMNS);
        integer(snake,"rows",SnakeEngine.ROWS,SnakeEngine.ROWS);
        Object rawTick = snake.get("tick");
        if (!(rawTick instanceof String) || !((String)rawTick).matches("0|[1-9][0-9]{0,15}")) throw invalid();
        try { tick = Long.parseLong((String)rawTick); } catch (NumberFormatException error) { throw invalid(); }
        if (tick > MAX_SAFE_INTEGER - 3) throw invalid();
        score = (int)integer(snake,"score",0,(SnakeEngine.COLUMNS*SnakeEngine.ROWS-6)*10);
        difficulty = choice(snake,"difficulty",new String[]{"CALM","NORMAL","FAST"});
        engineState = choice(snake,"state",new String[]{"READY","PLAYING","PAUSED","WON","GAME_OVER"});
        tickMs = (int)integer(snake,"tickMs",75,240);
        int expected = Math.max(75,(difficulty.equals("CALM")?240:difficulty.equals("FAST")?130:180)-Math.min(score/10,40)*3);
        if (tickMs != expected || score % 10 != 0) throw invalid();
        if ((phase.equals("PLAYING") && !engineState.equals("PLAYING"))
                || (phase.equals("FINISHED") && !(engineState.equals("GAME_OVER") || engineState.equals("WON")))
                || ((phase.equals("PAUSED") || phase.equals("COUNTDOWN")) && !(engineState.equals("READY") || engineState.equals("PAUSED")))) throw invalid();
        JSONObject food = snake.getJSONObject("food");
        foodX = (int)integer(food,"x",-1,SnakeEngine.COLUMNS-1);
        foodY = (int)integer(food,"y",-1,SnakeEngine.ROWS-1);
        if ((foodX == -1) != (foodY == -1)) throw invalid();
        JSONArray players = snake.getJSONArray("players"); if (players.length()!=2) throw invalid();
        bodies = new int[2][]; directions = new SnakeEngine.Direction[2];
        Set<Integer> occupied = new HashSet<>();
        for(int p=0;p<2;p++) {
            JSONObject player = players.getJSONObject(p);
            directions[p] = SnakeEngine.Direction.valueOf(choice(player,"direction",new String[]{"UP","RIGHT","DOWN","LEFT"}));
            JSONArray body = player.getJSONArray("body");
            if(body.length()<3 || body.length()>SnakeEngine.COLUMNS*SnakeEngine.ROWS) throw invalid();
            bodies[p]=new int[body.length()];
            for(int i=0;i<body.length();i++) {
                int cell=(int)integer(body.get(i),0,SnakeEngine.COLUMNS*SnakeEngine.ROWS-1);
                if(!occupied.add(cell)) throw invalid(); bodies[p][i]=cell;
                if(i>0 && distance(cell,bodies[p][i-1])!=1) throw invalid();
            }
            int head=bodies[p][0], neck=bodies[p][1];
            if(head%SnakeEngine.COLUMNS-neck%SnakeEngine.COLUMNS!=directions[p].dx
                    ||head/SnakeEngine.COLUMNS-neck/SnakeEngine.COLUMNS!=directions[p].dy) throw invalid();
        }
        if(occupied.size()!=6+score/10 || (foodX>=0 && occupied.contains(foodY*SnakeEngine.COLUMNS+foodX))
                || (engineState.equals("WON") ? occupied.size()!=SnakeEngine.COLUMNS*SnakeEngine.ROWS || foodX!=-1 : foodX<0)) throw invalid();
    }
    public static SnakeRoomSnapshot parse(JSONObject input) throws JSONException {
        if(input==null || input.toString().length()>30000) throw invalid(); return new SnakeRoomSnapshot(input);
    }
    /** Epoch ordering belongs to the live connection, not UUID lexical ordering. */
    public boolean follows(SnakeRoomSnapshot previous) {
        if(previous==null) return true;
        if(!gameId.equals(previous.gameId) || !epoch.equals(previous.epoch)) return false;
        if(revision<previous.revision || tick<previous.tick) return false;
        if(revision==previous.revision) return samePosition(previous);
        return true;
    }
    private boolean samePosition(SnakeRoomSnapshot old) {
        return tick==old.tick && score==old.score && phase.equals(old.phase) && engineState.equals(old.engineState)
                && difficulty.equals(old.difficulty) && foodX==old.foodX && foodY==old.foodY
                && Arrays.equals(ready,old.ready) && Arrays.equals(connected,old.connected)
                && Arrays.deepEquals(bodies,old.bodies) && Arrays.equals(directions,old.directions);
    }
    public boolean ready(int player){ return ready[player]; }
    public boolean connected(int player){ return connected[player]; }
    public int[] body(int player){ return bodies[player].clone(); }
    public SnakeEngine.Direction direction(int player){ return directions[player]; }
    public JSONObject json() throws JSONException { return new JSONObject(json.toString()); }
    private static int distance(int a,int b){return Math.abs(a%SnakeEngine.COLUMNS-b%SnakeEngine.COLUMNS)+Math.abs(a/SnakeEngine.COLUMNS-b/SnakeEngine.COLUMNS);}
    private static boolean[] pair(JSONObject data,String key)throws JSONException {
        JSONArray values=data.getJSONArray(key);if(values.length()!=2)throw invalid();
        boolean[] result=new boolean[2];for(int i=0;i<2;i++){Object value=values.get(i);if(!(value instanceof Boolean))throw invalid();result[i]=(Boolean)value;}return result;
    }
    private static String text(JSONObject data,String key,int max)throws JSONException {
        Object value=data.get(key);if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>max)throw invalid();return (String)value;
    }
    private static String choice(JSONObject data,String key,String[] values)throws JSONException {
        String value=text(data,key,40);if(!Arrays.asList(values).contains(value))throw invalid();return value;
    }
    private static long integer(JSONObject data,String key,long min,long max)throws JSONException {return integer(data.get(key),min,max);}
    private static long integer(Object value,long min,long max)throws JSONException {
        if(!(value instanceof Number))throw invalid();double number=((Number)value).doubleValue();long whole=((Number)value).longValue();
        if(!Double.isFinite(number)||number!=whole||whole<min||whole>max)throw invalid();return whole;
    }
    private static JSONException invalid(){return new JSONException("Invalid family Snake snapshot");}
}
