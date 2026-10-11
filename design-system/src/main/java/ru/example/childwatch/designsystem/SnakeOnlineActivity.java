package ru.example.childwatch.designsystem;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import org.json.JSONArray;
import org.json.JSONObject;

/** Foreground game connection; never touches the application's monitoring channel. */
public class SnakeOnlineActivity extends AppCompatActivity {
    protected interface Session { void start(); void command(String action); void turn(SnakeEngine.Direction direction); void close(); }
    private Session session;
    private GameOnlineBridge.Client names;
    private TextView status,score,peer;
    private MaterialButton ready,pause;
    private MaterialCheckBox buttons;
    SnakeOnlineView field;
    JSONObject snapshot;
    private boolean active,connected,retry,localPause;
    private int player=-1,generation;
    private String gameId,peerName="Участник семьи";
    @Override public void onCreate(Bundle state){
        super.onCreate(state);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        gameId=getIntent().getStringExtra("gameId");
        if(gameId==null||!gameId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")){finish();return;}
        setContentView(R.layout.games_snake_online);
        status=findViewById(R.id.snakeOnlineStatus);score=findViewById(R.id.snakeOnlineScore);peer=findViewById(R.id.snakeOnlinePeer);
        ready=findViewById(R.id.snakeOnlineReady);pause=findViewById(R.id.snakeOnlinePause);buttons=findViewById(R.id.snakeOnlineButtons);
        field=new SnakeOnlineView(this,this::turn);((FrameLayout)findViewById(R.id.snakeOnlineField)).addView(field,new FrameLayout.LayoutParams(-1,-1));
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.snakeOnlineRoot),(v,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });
        AccessibilityManager manager=(AccessibilityManager)getSystemService(ACCESSIBILITY_SERVICE);boolean accessible=manager!=null&&manager.isTouchExplorationEnabled();
        buttons.setChecked(accessible||getPreferences(MODE_PRIVATE).getBoolean("directions",false));buttons.setEnabled(!accessible);
        buttons.setOnCheckedChangeListener((b,value)->{getPreferences(MODE_PRIVATE).edit().putBoolean("directions",value).apply();refresh();});
        findViewById(R.id.snakeOnlineBack).setOnClickListener(v->finish());
        ready.setOnClickListener(v->{if(retry)connect();else if(session!=null&&connected){ready.setEnabled(false);session.command("READY");}});
        pause.setOnClickListener(v->{if(session!=null&&playing()){localPause=true;field.freeze();status.setText(R.string.cw_snake_online_pausing);refresh();session.command("PAUSE");}});
        bind(R.id.snakeOnlineLeft,SnakeEngine.Direction.LEFT);bind(R.id.snakeOnlineRight,SnakeEngine.Direction.RIGHT);
        bind(R.id.snakeOnlineUp,SnakeEngine.Direction.UP);bind(R.id.snakeOnlineDown,SnakeEngine.Direction.DOWN);
        status.setText(R.string.cw_snake_online_loading);refresh();
    }
    protected Session createSession(String id,SnakeRealtimeBridge.Listener listener){
        SnakeRealtimeBridge.Connection connection=new SnakeRealtimeBridge.Connection(id,listener);
        return new Session(){public void start(){connection.start();}public void command(String action){connection.command(action);}public void turn(SnakeEngine.Direction direction){connection.turn(direction);}public void close(){connection.close();}};
    }
    private void connect(){
        if(!active)return;if(session!=null)session.close();connected=false;retry=false;localPause=false;player=-1;field.setPlayer(-1);field.freeze();
        status.setText(R.string.cw_snake_online_loading);int current=++generation;
        session=createSession(gameId,new SnakeRealtimeBridge.Listener(){
            public void onState(JSONObject state){if(!active||current!=generation)return;connected=true;retry=false;snapshot=state;if(!"PLAYING".equals(state.optString("phase")))localPause=false;field.accept(state);showState();refresh();}
            public void onPlayer(int own){if(!active||current!=generation)return;player=own;field.setPlayer(own);refresh();}
            public void onStatus(String code){if(!active||current!=generation)return;showStatus(code);}
        });
        refresh();session.start();loadNames(current);
    }
    protected void loadNames(int current){
        if(names!=null)names.close();names=new GameOnlineBridge.Client(this);
        if(!names.available())return;
        names.read("/"+gameId,(json,error)->{
            if(!active||current!=generation||error!=null)return;
            JSONObject game=json.optJSONObject("game");if(game==null)game=json;
            boolean host="X".equals(game.optString("myMark"));JSONObject value=game.optJSONObject(host?"guest":"host");
            if(value!=null&&!value.optString("displayName").trim().isEmpty())peerName=value.optString("displayName");refresh();
        });
    }
    private void showStatus(String code){
        if("CONNECTED".equals(code)){connected=snapshot!=null;retry=false;if(snapshot!=null)showState();refresh();return;}
        if("SNAKE_COMMAND_TIMEOUT".equals(code)){localPause=false;status.setText(R.string.cw_snake_online_command_timeout);refresh();return;}
        int text=R.string.cw_snake_online_reconnecting;
        if("SNAKE_INPUT_PENDING".equals(code))return;
        boolean transientInput="SNAKE_INVALID_TURN".equals(code)||"SNAKE_INPUT_QUEUE".equals(code)||"SNAKE_TICK_WINDOW".equals(code)||"SNAKE_BUSY".equals(code);
        if(transientInput){status.setText(R.string.cw_snake_online_input_wait);refresh();return;}
        connected=false;field.freeze();retry=false;
        if("SNAKE_CONNECTION_REPLACED".equals(code)){text=R.string.cw_snake_online_replaced;retry=true;}
        else if("SNAKE_UNSUPPORTED".equals(code)){text=R.string.cw_snake_online_unsupported;}
        else if("GAME_CLOSED".equals(code)||"GAME_ACCESS_DENIED".equals(code)||"GAME_CONTEXT_CHANGED".equals(code)||"GAME_NOT_FOUND".equals(code)||"GAME_PARTICIPANT_UNAVAILABLE".equals(code)||"GAME_AUTH_REQUIRED".equals(code)||"GAME_FORBIDDEN".equals(code)){text=R.string.cw_snake_online_unavailable;}
        else if("CONNECTING".equals(code)){text=R.string.cw_snake_online_loading;}
        else retry=true;
        status.setText(text);refresh();
    }
    private void showState(){
        String phase=snapshot.optString("phase");JSONObject snake=snapshot.optJSONObject("snake");int result=snake==null?0:snake.optInt("score")/10;
        score.setText(getString(R.string.cw_snake_online_score,result));
        if("PLAYING".equals(phase))status.setText(localPause?R.string.cw_snake_online_pausing:R.string.cw_snake_online_playing);
        else if("COUNTDOWN".equals(phase))status.setText(getString(R.string.cw_snake_online_countdown,Math.max(1,(snapshot.optInt("countdownMs")+999)/1000)));
        else if("FINISHED".equals(phase))status.setText(getString(snake!=null&&"WON".equals(snake.optString("state"))?R.string.cw_snake_online_won:R.string.cw_snake_online_over,result));
        else {
            JSONArray online=snapshot.optJSONArray("connected"),readiness=snapshot.optJSONArray("ready");
            if("SERVER_DELAY".equals(snapshot.optString("reason")))status.setText(R.string.cw_snake_online_slow);
            else if(online==null||!online.optBoolean(0)||!online.optBoolean(1))status.setText(R.string.cw_snake_online_waiting_connection);
            else if(player>=0&&readiness!=null&&readiness.optBoolean(player))status.setText(R.string.cw_snake_online_waiting);
            else status.setText(R.string.cw_snake_online_paused);
        }
    }
    private boolean playing(){return active&&connected&&!localPause&&player>=0&&snapshot!=null&&"PLAYING".equals(snapshot.optString("phase"));}
    private void refresh(){
        if(field==null)return;boolean playing=playing();field.setCanTurn(playing);
        peer.setText(getString(R.string.cw_snake_online_legend,peerName));
        boolean paused=connected&&player>=0&&snapshot!=null&&"PAUSED".equals(snapshot.optString("phase"));
        JSONArray readiness=snapshot==null?null:snapshot.optJSONArray("ready");
        ready.setText(retry?R.string.cw_snake_online_retry:R.string.cw_snake_online_ready);
        ready.setVisibility(retry||paused?View.VISIBLE:View.GONE);ready.setEnabled(active&&(retry||paused&&(readiness==null||!readiness.optBoolean(player))));
        pause.setEnabled(playing);pause.setVisibility(playing?View.VISIBLE:View.GONE);
        findViewById(R.id.snakeOnlineControls).setVisibility(buttons.isChecked()?View.VISIBLE:View.GONE);
        for(int id:new int[]{R.id.snakeOnlineLeft,R.id.snakeOnlineRight,R.id.snakeOnlineUp,R.id.snakeOnlineDown})findViewById(id).setEnabled(playing);
        if(playing)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowInsetsControllerCompat bars=WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView());bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if(playing)bars.hide(WindowInsetsCompat.Type.systemBars());else bars.show(WindowInsetsCompat.Type.systemBars());
    }
    private void bind(int id,SnakeEngine.Direction direction){findViewById(id).setOnClickListener(v->turn(direction));}
    private void turn(SnakeEngine.Direction direction){if(playing()&&session!=null)session.turn(direction);}
    @Override protected void onResume(){super.onResume();if(field==null)return;active=true;field.setActive(true);connect();}
    @Override protected void onPause(){
        active=false;generation++;connected=false;if(field!=null){field.setActive(false);refresh();}if(session!=null){session.close();session=null;}if(names!=null){names.close();names=null;}super.onPause();
    }
    @Override protected void onDestroy(){if(session!=null)session.close();if(names!=null)names.close();super.onDestroy();}
}
