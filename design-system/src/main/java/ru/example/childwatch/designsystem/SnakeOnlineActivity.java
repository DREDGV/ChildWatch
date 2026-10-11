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
    private TextView status,detailedStatus,score,peer;
    private MaterialButton ready,pause;
    private MaterialCheckBox buttons;
    SnakeOnlineView field;
    JSONObject snapshot;
    private boolean active,connected,retry,localPause,detailsExpanded;
    private int player=-1,generation;
    private String gameId,peerName="Участник семьи";
    @Override public void onCreate(Bundle state){
        super.onCreate(state);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        gameId=getIntent().getStringExtra("gameId");
        if(gameId==null||!gameId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")){finish();return;}
        setContentView(R.layout.games_snake_online);
        status=findViewById(R.id.snakeOnlineStatus);detailedStatus=findViewById(R.id.snakeOnlineDetailedStatus);score=findViewById(R.id.snakeOnlineScore);peer=findViewById(R.id.snakeOnlinePeer);
        ready=findViewById(R.id.snakeOnlineReady);pause=findViewById(R.id.snakeOnlinePause);buttons=findViewById(R.id.snakeOnlineButtons);
        field=new SnakeOnlineView(this,this::turn);((FrameLayout)findViewById(R.id.snakeOnlineField)).addView(field,new FrameLayout.LayoutParams(-1,-1));
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.snakeOnlineRoot),(v,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });
        AccessibilityManager manager=(AccessibilityManager)getSystemService(ACCESSIBILITY_SERVICE);boolean accessible=manager!=null&&manager.isTouchExplorationEnabled();
        buttons.setChecked(accessible||getPreferences(MODE_PRIVATE).getBoolean("directions",false));buttons.setEnabled(!accessible);
        buttons.setOnCheckedChangeListener((b,value)->{getPreferences(MODE_PRIVATE).edit().putBoolean("directions",value).apply();refresh();});
        findViewById(R.id.snakeOnlineBack).setOnClickListener(v->finish());
        ready.setOnClickListener(v->{setDetails(false);if(retry)connect();else if(session!=null&&connected){ready.setEnabled(false);session.command("READY");}});
        pause.setOnClickListener(v->requestPause());
        findViewById(R.id.snakeOnlineDetails).setOnClickListener(v->{if(!detailsExpanded&&playing())requestPause();setDetails(!detailsExpanded);});
        icon(R.id.snakeOnlineBack,"back");icon(R.id.snakeOnlinePause,"pause");icon(R.id.snakeOnlineDetails,"details");
        icon(R.id.snakeOnlineLeft,"left");icon(R.id.snakeOnlineRight,"right");icon(R.id.snakeOnlineUp,"up");icon(R.id.snakeOnlineDown,"down");
        findViewById(R.id.snakeOnlineRoot).addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->sizeDetails());
        bind(R.id.snakeOnlineLeft,SnakeEngine.Direction.LEFT);bind(R.id.snakeOnlineRight,SnakeEngine.Direction.RIGHT);
        bind(R.id.snakeOnlineUp,SnakeEngine.Direction.UP);bind(R.id.snakeOnlineDown,SnakeEngine.Direction.DOWN);
        setStatus(R.string.cw_snake_online_loading);setDetails(false);refresh();
    }
    protected Session createSession(String id,SnakeRealtimeBridge.Listener listener){
        SnakeRealtimeBridge.Connection connection=new SnakeRealtimeBridge.Connection(id,listener);
        return new Session(){public void start(){connection.start();}public void command(String action){connection.command(action);}public void turn(SnakeEngine.Direction direction){connection.turn(direction);}public void close(){connection.close();}};
    }
    private void connect(){
        if(!active)return;if(session!=null)session.close();connected=false;retry=false;localPause=false;player=-1;field.setPlayer(-1);field.freeze();
        setStatus(R.string.cw_snake_online_loading);int current=++generation;
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
        if("SNAKE_COMMAND_TIMEOUT".equals(code)){localPause=false;setStatus(R.string.cw_snake_online_command_timeout);refresh();return;}
        int text=R.string.cw_snake_online_reconnecting;
        if("SNAKE_INPUT_PENDING".equals(code))return;
        boolean transientInput="SNAKE_INVALID_TURN".equals(code)||"SNAKE_INPUT_QUEUE".equals(code)||"SNAKE_TICK_WINDOW".equals(code)||"SNAKE_BUSY".equals(code);
        if(transientInput){setStatus(R.string.cw_snake_online_input_wait);refresh();return;}
        connected=false;field.freeze();retry=false;
        if("SNAKE_CONNECTION_REPLACED".equals(code)){text=R.string.cw_snake_online_replaced;retry=true;}
        else if("SNAKE_UNSUPPORTED".equals(code)){text=R.string.cw_snake_online_unsupported;}
        else if("GAME_CLOSED".equals(code)||"GAME_ACCESS_DENIED".equals(code)||"GAME_CONTEXT_CHANGED".equals(code)||"GAME_NOT_FOUND".equals(code)||"GAME_PARTICIPANT_UNAVAILABLE".equals(code)||"GAME_AUTH_REQUIRED".equals(code)||"GAME_FORBIDDEN".equals(code)){text=R.string.cw_snake_online_unavailable;}
        else if("CONNECTING".equals(code)){text=R.string.cw_snake_online_loading;}
        else retry=true;
        setStatus(text);refresh();
    }
    private void showState(){
        String phase=snapshot.optString("phase");JSONObject snake=snapshot.optJSONObject("snake");int result=snake==null?0:snake.optInt("score")/10;
        text(score,getString(R.string.cw_snake_online_score_short,result));score.setContentDescription(getString(R.string.cw_snake_online_score,result));
        if("PLAYING".equals(phase))setStatus(localPause?R.string.cw_snake_online_pausing:R.string.cw_snake_online_playing);
        else if("COUNTDOWN".equals(phase))setStatus(R.string.cw_snake_online_countdown,Math.max(1,(snapshot.optInt("countdownMs")+999)/1000));
        else if("FINISHED".equals(phase))setStatus(snake!=null&&"WON".equals(snake.optString("state"))?R.string.cw_snake_online_won:R.string.cw_snake_online_over,result);
        else {
            JSONArray online=snapshot.optJSONArray("connected"),readiness=snapshot.optJSONArray("ready");
            if("SERVER_DELAY".equals(snapshot.optString("reason")))setStatus(R.string.cw_snake_online_slow);
            else if(online==null||!online.optBoolean(0)||!online.optBoolean(1))setStatus(R.string.cw_snake_online_waiting_connection);
            else if(player>=0&&readiness!=null&&readiness.optBoolean(player))setStatus(R.string.cw_snake_online_waiting);
            else setStatus(R.string.cw_snake_online_paused);
        }
    }
    private boolean playing(){return active&&connected&&!localPause&&player>=0&&snapshot!=null&&"PLAYING".equals(snapshot.optString("phase"));}
    private void refresh(){
        if(field==null)return;boolean playing=playing();field.setCanTurn(playing);
        text(peer,getString(R.string.cw_snake_online_legend,peerName));
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
        sizeDetails();
    }
    private void requestPause(){if(session!=null&&playing()){localPause=true;field.freeze();setStatus(R.string.cw_snake_online_pausing);refresh();session.command("PAUSE");}}
    private void setDetails(boolean value){
        detailsExpanded=value;findViewById(R.id.snakeOnlineDetailsPanel).setVisibility(value?View.VISIBLE:View.GONE);
        View details=findViewById(R.id.snakeOnlineDetails);details.setContentDescription(getString(value?R.string.cw_snake_online_details_hide:R.string.cw_snake_online_details_show));
        ViewCompat.setStateDescription(details,getString(value?R.string.cw_snake_online_details_expanded:R.string.cw_snake_online_details_collapsed));sizeDetails();
        if(value)findViewById(R.id.snakeOnlineDetailsPanel).sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }
    private void sizeDetails(){
        View panel=findViewById(R.id.snakeOnlineDetailsPanel),root=findViewById(R.id.snakeOnlineRoot);if(panel==null||!detailsExpanded||root.getHeight()==0)return;
        int available=root.getHeight()-root.getPaddingTop()-root.getPaddingBottom()-findViewById(R.id.snakeOnlineHud).getHeight()-status.getHeight();
        if(ready.getVisibility()==View.VISIBLE)available-=ready.getHeight();if(findViewById(R.id.snakeOnlineControls).getVisibility()==View.VISIBLE)available-=findViewById(R.id.snakeOnlineControls).getHeight();
        int height=Math.max(dp(64),Math.min(dp(200),available-dp(240)));
        if(panel.getLayoutParams().height!=height){android.view.ViewGroup.LayoutParams params=panel.getLayoutParams();params.height=height;panel.setLayoutParams(params);}
    }
    private void setStatus(int resource,Object...args){
        int compact=resource;
        if(resource==R.string.cw_snake_online_loading)compact=R.string.cw_snake_online_short_loading;
        else if(resource==R.string.cw_snake_online_playing)compact=R.string.cw_snake_online_short_playing;
        else if(resource==R.string.cw_snake_online_pausing)compact=R.string.cw_snake_online_short_pausing;
        else if(resource==R.string.cw_snake_online_paused)compact=R.string.cw_snake_online_short_paused;
        else if(resource==R.string.cw_snake_online_waiting)compact=R.string.cw_snake_online_short_ready;
        else if(resource==R.string.cw_snake_online_waiting_connection)compact=R.string.cw_snake_online_short_peer;
        else if(resource==R.string.cw_snake_online_reconnecting)compact=R.string.cw_snake_online_short_reconnecting;
        else if(resource==R.string.cw_snake_online_countdown)compact=R.string.cw_snake_online_short_countdown;
        else if(resource==R.string.cw_snake_online_over)compact=R.string.cw_snake_online_short_over;
        else if(resource==R.string.cw_snake_online_won)compact=R.string.cw_snake_online_short_won;
        else if(resource==R.string.cw_snake_online_unavailable)compact=R.string.cw_snake_online_short_unavailable;
        else if(resource==R.string.cw_snake_online_unsupported)compact=R.string.cw_snake_online_short_unsupported;
        else if(resource==R.string.cw_snake_online_slow)compact=R.string.cw_snake_online_short_slow;
        else if(resource==R.string.cw_snake_online_replaced)compact=R.string.cw_snake_online_short_replaced;
        else if(resource==R.string.cw_snake_online_command_timeout)compact=R.string.cw_snake_online_short_timeout;
        else if(resource==R.string.cw_snake_online_input_wait)compact=R.string.cw_snake_online_short_turn;
        String full=getString(resource,args);text(status,getString(compact,args));status.setContentDescription(full);text(detailedStatus,full);
    }
    private void text(TextView view,String value){if(!value.contentEquals(view.getText()))view.setText(value);}
    protected void setPeerName(String value){if(value!=null&&!value.trim().isEmpty()){peerName=value;refresh();}}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void icon(int id,String kind){MaterialButton button=findViewById(id);button.setIcon(new HudIcon(kind));button.setIconSize(dp(24));button.setIconPadding(0);button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);}
    private static final class HudIcon extends android.graphics.drawable.Drawable {
        private final String kind;private final android.graphics.Paint ink=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        HudIcon(String kind){this.kind=kind;ink.setColor(android.graphics.Color.WHITE);ink.setStyle(android.graphics.Paint.Style.STROKE);ink.setStrokeWidth(2);ink.setStrokeCap(android.graphics.Paint.Cap.ROUND);ink.setStrokeJoin(android.graphics.Paint.Join.ROUND);}
        @Override public void draw(android.graphics.Canvas canvas){
            android.graphics.Rect bounds=getBounds();canvas.save();canvas.translate(bounds.left,bounds.top);canvas.scale(bounds.width()/24f,bounds.height()/24f);
            if("pause".equals(kind)){canvas.drawLine(8,5,8,19,ink);canvas.drawLine(16,5,16,19,ink);}
            else if("details".equals(kind)){canvas.drawCircle(12,12,9,ink);canvas.drawLine(12,11,12,17,ink);canvas.drawPoint(12,7,ink);}
            else{if("right".equals(kind))canvas.rotate(180,12,12);else if("up".equals(kind))canvas.rotate(90,12,12);else if("down".equals(kind))canvas.rotate(-90,12,12);canvas.drawLine(19,12,5,12,ink);canvas.drawLine(5,12,11,6,ink);canvas.drawLine(5,12,11,18,ink);}
            canvas.restore();
        }
        @Override public void setAlpha(int alpha){ink.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(android.graphics.ColorFilter filter){ink.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    private void bind(int id,SnakeEngine.Direction direction){findViewById(id).setOnClickListener(v->turn(direction));}
    private void turn(SnakeEngine.Direction direction){if(playing()&&session!=null)session.turn(direction);}
    @Override protected void onResume(){super.onResume();if(field==null)return;active=true;field.setActive(true);connect();}
    @Override protected void onPause(){
        active=false;generation++;connected=false;if(field!=null){field.setActive(false);refresh();}if(session!=null){session.close();session=null;}if(names!=null){names.close();names=null;}super.onPause();
    }
    @Override protected void onDestroy(){if(session!=null)session.close();if(names!=null)names.close();super.onDestroy();}
}
