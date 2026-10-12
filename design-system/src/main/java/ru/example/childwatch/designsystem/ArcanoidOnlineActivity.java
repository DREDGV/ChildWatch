package ru.example.childwatch.designsystem;

import android.os.Bundle;
import android.os.SystemClock;
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
import org.json.JSONObject;

/** Shared cooperative game. App monitoring and the local solo save remain untouched. */
public class ArcanoidOnlineActivity extends AppCompatActivity {
    protected interface Session {void start();void command(String action);void move(double normalized);void close();}
    private Session session;private GameOnlineBridge.Client names;
    private TextView status,fullStatus,score,peer;private MaterialButton ready,pause;private MaterialCheckBox buttons;
    ArcanoidOnlineView field;ArcanoidRoomSnapshot snapshot;
    private boolean active,connected,retry,localPause,detailsExpanded;private int player=-1,generation;
    private String gameId,peerName="Участник семьи";private Double requestedLocal;private long requestedAt;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);gameId=getIntent().getStringExtra("gameId");
        if(gameId==null||!gameId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")){finish();return;}
        setContentView(R.layout.games_arcanoid_online);status=findViewById(R.id.arcanoidOnlineStatus);fullStatus=findViewById(R.id.arcanoidOnlineDetailedStatus);score=findViewById(R.id.arcanoidOnlineScore);peer=findViewById(R.id.arcanoidOnlinePeer);
        ready=findViewById(R.id.arcanoidOnlineReady);pause=findViewById(R.id.arcanoidOnlinePause);buttons=findViewById(R.id.arcanoidOnlineButtons);
        field=new ArcanoidOnlineView(this,this::move);((FrameLayout)findViewById(R.id.arcanoidOnlineField)).addView(field,new FrameLayout.LayoutParams(-1,-1));
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.arcanoidOnlineRoot),(view,insets)->{androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        AccessibilityManager manager=(AccessibilityManager)getSystemService(ACCESSIBILITY_SERVICE);boolean accessible=manager!=null&&manager.isTouchExplorationEnabled();buttons.setChecked(accessible||getPreferences(MODE_PRIVATE).getBoolean("buttons",false));buttons.setEnabled(!accessible);
        buttons.setOnCheckedChangeListener((view,value)->{getPreferences(MODE_PRIVATE).edit().putBoolean("buttons",value).apply();refresh();});
        findViewById(R.id.arcanoidOnlineBack).setOnClickListener(v->finish());pause.setOnClickListener(v->requestPause());
        ready.setOnClickListener(v->{setDetails(false);if(retry)connect();else if(connected&&session!=null){ready.setEnabled(false);session.command("READY");}});
        findViewById(R.id.arcanoidOnlineDetails).setOnClickListener(v->{if(!detailsExpanded&&playing())requestPause();setDetails(!detailsExpanded);});
        findViewById(R.id.arcanoidOnlineLeft).setOnClickListener(v->step(-.12));findViewById(R.id.arcanoidOnlineRight).setOnClickListener(v->step(.12));
        icon(R.id.arcanoidOnlineBack,"back");icon(R.id.arcanoidOnlinePause,"pause");icon(R.id.arcanoidOnlineDetails,"details");icon(R.id.arcanoidOnlineLeft,"left");icon(R.id.arcanoidOnlineRight,"right");
        findViewById(R.id.arcanoidOnlineRoot).addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->sizeDetails());setStatus(R.string.cw_arcanoid_online_loading);setDetails(false);refresh();
    }
    protected Session createSession(String id,ArcanoidRealtimeBridge.Listener listener){ArcanoidRealtimeBridge.Connection connection=new ArcanoidRealtimeBridge.Connection(id,listener);return new Session(){public void start(){connection.start();}public void command(String action){connection.command(action);}public void move(double value){connection.move(value);}public void close(){connection.close();}};}
    private void connect(){
        if(!active)return;if(session!=null)session.close();connected=false;retry=false;localPause=false;player=-1;requestedLocal=null;field.setPlayer(-1);field.freeze();setStatus(R.string.cw_arcanoid_online_loading);int current=++generation;
        session=createSession(gameId,new ArcanoidRealtimeBridge.Listener(){
            public void onState(ArcanoidRoomSnapshot state){if(!active||current!=generation)return;if(snapshot==null||!snapshot.epoch.equals(state.epoch)||!"PLAYING".equals(state.phase))requestedLocal=null;connected=true;retry=false;snapshot=state;if(!"PLAYING".equals(state.phase))localPause=false;field.accept(state);showState();refresh();}
            public void onPlayer(int value){if(!active||current!=generation)return;player=value;field.setPlayer(value);refresh();}
            public void onStatus(String code){if(!active||current!=generation)return;showStatus(code);}
        });refresh();session.start();loadNames(current);
    }
    protected void loadNames(int current){
        if(names!=null)names.close();names=new GameOnlineBridge.Client(this);if(!names.available())return;
        names.read("/"+gameId,(json,error)->{if(!active||current!=generation||error!=null)return;JSONObject game=json.optJSONObject("game");if(game==null)game=json;JSONObject person=game.optJSONObject("X".equals(game.optString("myMark"))?"guest":"host");if(person!=null)setPeerName(person.optString("displayName"));});
    }
    protected void setPeerName(String value){if(value!=null&&!value.trim().isEmpty()){peerName=value;refresh();}}
    private void showStatus(String code){
        if("CONNECTED".equals(code)){connected=snapshot!=null;retry=false;if(snapshot!=null)showState();refresh();return;}
        if("ARKANOID_COMMAND_TIMEOUT".equals(code)){localPause=false;setStatus(R.string.cw_arcanoid_online_command_timeout);refresh();return;}
        connected=false;requestedLocal=null;field.freeze();retry=false;int text=R.string.cw_arcanoid_online_reconnecting;
        if("ARKANOID_UNSUPPORTED".equals(code))text=R.string.cw_arcanoid_online_unsupported;
        else if("ARKANOID_CONNECTION_REPLACED".equals(code)){text=R.string.cw_arcanoid_online_replaced;retry=true;}
        else if(code.startsWith("GAME_"))text=R.string.cw_arcanoid_online_unavailable;
        else if("CONNECTING".equals(code))text=R.string.cw_arcanoid_online_loading;
        else retry=true;setStatus(text);refresh();
    }
    private void showState(){
        String label=snapshot.score+"\n"+getString(R.string.cw_arcanoid_online_hud,snapshot.level,snapshot.lives);text(score,label);description(score,getString(R.string.cw_arcanoid_online_metrics,snapshot.level,snapshot.lives,snapshot.score));
        if("PLAYING".equals(snapshot.phase))setStatus(localPause?R.string.cw_arcanoid_online_pausing:R.string.cw_arcanoid_online_playing);
        else if("COUNTDOWN".equals(snapshot.phase))setStatus(R.string.cw_arcanoid_online_countdown,Math.max(1,(snapshot.countdownMs+999)/1000));
        else if("FINISHED".equals(snapshot.phase))setStatus("WON".equals(snapshot.engineState)?R.string.cw_arcanoid_online_won:R.string.cw_arcanoid_online_over,snapshot.score);
        else if("WON".equals(snapshot.engineState))setStatus(R.string.cw_arcanoid_online_level_complete);
        else if("SERVER_DELAY".equals(snapshot.reason))setStatus(R.string.cw_arcanoid_online_slow);
        else if(snapshot.reason!=null&&snapshot.reason.contains("LIFE"))setStatus(R.string.cw_arcanoid_online_life_lost);
        else if(!snapshot.connected(0)||!snapshot.connected(1))setStatus(R.string.cw_arcanoid_online_waiting_connection);
        else if(player>=0&&snapshot.ready(player))setStatus(R.string.cw_arcanoid_online_waiting);
        else setStatus(R.string.cw_arcanoid_online_paused);
    }
    private boolean playing(){return active&&connected&&!localPause&&player>=0&&snapshot!=null&&"PLAYING".equals(snapshot.phase);}
    private void move(double local){if(!playing()||session==null)return;requestedLocal=Math.max(0,Math.min(1,local));requestedAt=SystemClock.elapsedRealtime();session.move(ArcanoidRoomSnapshot.serverPosition(requestedLocal,player));}
    private void step(double value){if(!playing())return;double current=requestedLocal!=null&&SystemClock.elapsedRealtime()-requestedAt<500?requestedLocal:snapshot.localX(snapshot.paddle(player),player)/1000;move(current+value);}
    private void requestPause(){if(!playing()||session==null)return;localPause=true;requestedLocal=null;field.freeze();setStatus(R.string.cw_arcanoid_online_pausing);refresh();session.command("PAUSE");}
    private void refresh(){
        if(field==null)return;boolean playing=playing();field.setCanMove(playing);text(peer,getString(R.string.cw_arcanoid_online_legend,peerName));boolean paused=connected&&player>=0&&snapshot!=null&&"PAUSED".equals(snapshot.phase);
        ready.setText(retry?R.string.cw_arcanoid_online_retry:snapshot!=null&&"WON".equals(snapshot.engineState)?R.string.cw_arcanoid_online_next:R.string.cw_arcanoid_online_ready);ready.setVisibility(retry||paused?View.VISIBLE:View.GONE);ready.setEnabled(active&&(retry||paused&&!snapshot.ready(player)));
        pause.setVisibility(playing?View.VISIBLE:View.GONE);pause.setEnabled(playing);findViewById(R.id.arcanoidOnlineControls).setVisibility(buttons.isChecked()?View.VISIBLE:View.GONE);findViewById(R.id.arcanoidOnlineLeft).setEnabled(playing);findViewById(R.id.arcanoidOnlineRight).setEnabled(playing);
        if(playing)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowInsetsControllerCompat bars=WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView());bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);if(playing)bars.hide(WindowInsetsCompat.Type.systemBars());else bars.show(WindowInsetsCompat.Type.systemBars());sizeDetails();
    }
    private void setDetails(boolean value){detailsExpanded=value;findViewById(R.id.arcanoidOnlineDetailsPanel).setVisibility(value?View.VISIBLE:View.GONE);View button=findViewById(R.id.arcanoidOnlineDetails);button.setContentDescription(getString(value?R.string.cw_arcanoid_online_details_hide:R.string.cw_arcanoid_online_details_show));ViewCompat.setStateDescription(button,getString(value?R.string.cw_arcanoid_online_details_expanded:R.string.cw_arcanoid_online_details_collapsed));sizeDetails();}
    private void sizeDetails(){View root=findViewById(R.id.arcanoidOnlineRoot),panel=findViewById(R.id.arcanoidOnlineDetailsPanel);if(panel==null||!detailsExpanded||root.getHeight()==0)return;int available=root.getHeight()-root.getPaddingTop()-root.getPaddingBottom()-findViewById(R.id.arcanoidOnlineHud).getHeight()-status.getHeight();if(ready.getVisibility()==View.VISIBLE)available-=ready.getHeight();if(findViewById(R.id.arcanoidOnlineControls).getVisibility()==View.VISIBLE)available-=findViewById(R.id.arcanoidOnlineControls).getHeight();int height=Math.max(dp(64),Math.min(dp(200),available-dp(240)));if(panel.getLayoutParams().height!=height){android.view.ViewGroup.LayoutParams params=panel.getLayoutParams();params.height=height;panel.setLayoutParams(params);}}
    private void setStatus(int resource,Object...args){
        int compact=resource;
        if(resource==R.string.cw_arcanoid_online_loading)compact=R.string.cw_arcanoid_online_short_loading;
        else if(resource==R.string.cw_arcanoid_online_playing)compact=R.string.cw_arcanoid_online_short_playing;
        else if(resource==R.string.cw_arcanoid_online_pausing)compact=R.string.cw_arcanoid_online_short_pausing;
        else if(resource==R.string.cw_arcanoid_online_paused)compact=R.string.cw_arcanoid_online_short_paused;
        else if(resource==R.string.cw_arcanoid_online_waiting)compact=R.string.cw_arcanoid_online_short_ready;
        else if(resource==R.string.cw_arcanoid_online_waiting_connection)compact=R.string.cw_arcanoid_online_short_peer;
        else if(resource==R.string.cw_arcanoid_online_reconnecting)compact=R.string.cw_arcanoid_online_short_reconnecting;
        else if(resource==R.string.cw_arcanoid_online_countdown)compact=R.string.cw_arcanoid_online_short_countdown;
        else if(resource==R.string.cw_arcanoid_online_over)compact=R.string.cw_arcanoid_online_short_over;
        else if(resource==R.string.cw_arcanoid_online_won)compact=R.string.cw_arcanoid_online_short_won;
        else if(resource==R.string.cw_arcanoid_online_unavailable)compact=R.string.cw_arcanoid_online_short_unavailable;
        else if(resource==R.string.cw_arcanoid_online_unsupported)compact=R.string.cw_arcanoid_online_short_unsupported;
        else if(resource==R.string.cw_arcanoid_online_slow)compact=R.string.cw_arcanoid_online_short_slow;
        else if(resource==R.string.cw_arcanoid_online_replaced)compact=R.string.cw_arcanoid_online_short_replaced;
        else if(resource==R.string.cw_arcanoid_online_command_timeout)compact=R.string.cw_arcanoid_online_short_timeout;
        else if(resource==R.string.cw_arcanoid_online_level_complete)compact=R.string.cw_arcanoid_online_level_complete_short;
        else if(resource==R.string.cw_arcanoid_online_life_lost)compact=R.string.cw_arcanoid_online_life_lost_short;
        String full=getString(resource,args);text(status,getString(compact,args));description(status,full);String metrics=snapshot==null?"":"\n"+getString(R.string.cw_arcanoid_online_metrics,snapshot.level,snapshot.lives,snapshot.score);text(fullStatus,full+metrics);
    }
    private void text(TextView view,String value){if(!value.contentEquals(view.getText()))view.setText(value);}
    private void description(View view,String value){if(view.getContentDescription()==null||!value.contentEquals(view.getContentDescription()))view.setContentDescription(value);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void icon(int id,String kind){MaterialButton button=findViewById(id);button.setIcon(new Icon(kind));button.setIconSize(dp(24));button.setIconPadding(0);button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);}
    private static final class Icon extends android.graphics.drawable.Drawable{
        private final String kind;private final android.graphics.Paint ink=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        Icon(String kind){this.kind=kind;ink.setColor(android.graphics.Color.WHITE);ink.setStyle(android.graphics.Paint.Style.STROKE);ink.setStrokeWidth(2);ink.setStrokeCap(android.graphics.Paint.Cap.ROUND);ink.setStrokeJoin(android.graphics.Paint.Join.ROUND);}
        public void draw(android.graphics.Canvas c){android.graphics.Rect b=getBounds();c.save();c.translate(b.left,b.top);c.scale(b.width()/24f,b.height()/24f);if("pause".equals(kind)){c.drawLine(8,5,8,19,ink);c.drawLine(16,5,16,19,ink);}else if("details".equals(kind)){c.drawCircle(12,12,9,ink);c.drawLine(12,11,12,17,ink);c.drawPoint(12,7,ink);}else{if("right".equals(kind))c.rotate(180,12,12);c.drawLine(19,12,5,12,ink);c.drawLine(5,12,11,6,ink);c.drawLine(5,12,11,18,ink);}c.restore();}
        public void setAlpha(int value){ink.setAlpha(value);invalidateSelf();}public void setColorFilter(android.graphics.ColorFilter value){ink.setColorFilter(value);invalidateSelf();}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    @Override protected void onResume(){super.onResume();if(field==null)return;active=true;field.setActive(true);connect();}
    @Override protected void onPause(){active=false;generation++;connected=false;requestedLocal=null;if(field!=null){field.setActive(false);refresh();}if(session!=null){session.close();session=null;}if(names!=null){names.close();names=null;}super.onPause();}
    @Override protected void onDestroy(){if(session!=null)session.close();if(names!=null)names.close();super.onDestroy();}
}
