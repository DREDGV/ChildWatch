package ru.example.childwatch.designsystem;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Explicit invitations, no implicit acceptance and no background game polling. */
public class FamilyGameLobbyActivity extends GameScreen {
    public static final String EXTRA_GAME_TYPE="gameType";
    private String gameType="BATTLESHIP";
    private boolean snake,arcanoid;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private GameOnlineBridge.Client client,reader;
    private LinearLayout matches;
    private boolean active,busy,supported,showingActionError;
    private boolean reading,refreshAgain;
    private long actionGeneration;
    private JSONArray shownRows;
    private JSONArray members=new JSONArray();
    private String actor="";
    private com.google.android.material.button.MaterialButton invite,retry;
    private final Runnable tick=()->refresh();
    private final Runnable changed=()->{if(active){handler.removeCallbacks(tick);handler.postDelayed(tick,150);}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);String requested=getIntent().getStringExtra(EXTRA_GAME_TYPE);snake="SNAKE_COOP".equals(requested);arcanoid="ARKANOID_COOP".equals(requested);gameType=snake?"SNAKE_COOP":arcanoid?"ARKANOID_COOP":"BATTLESHIP";
        screen(snake?"Семейная Змейка":arcanoid?"Семейный Арканоид":"Семейный Морской бой");
        client=new GameOnlineBridge.Client(this);
        reader=new GameOnlineBridge.Client(this);
        notice(snake?getString(R.string.cw_snake_online_help):arcanoid?getString(R.string.cw_arcanoid_online_help):"Пригласите близкого. Каждый расставляет свой флот на своём телефоне; корабли соперника скрыты.");
        invite=addButton("Пригласить в игру",true,this::choose);
        retry=addButton("Повторить отправку",false,()->{
            if(busy)return;
            beginAction();
            client.retry(this::result);
        });
        addButton("Обновить партии",false,()->{
            showingActionError=false;
            if(supported&&!actor.isEmpty())refresh();else capabilities();
        });
        matches=new LinearLayout(this);matches.setOrientation(LinearLayout.VERTICAL);content.addView(matches);
        controls();
    }
    @Override protected void onResume(){super.onResume();active=true;GameOnlineBridge.observe(changed);capabilities();}
    @Override protected void onPause(){active=false;GameOnlineBridge.unobserve(changed);handler.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){reader.close();client.close();super.onDestroy();}
    private void controls(){invite.setEnabled(supported&&!busy&&!client.hasPending()&&members.length()>0);
        retry.setVisibility(client.hasPending()?android.view.View.VISIBLE:android.view.View.GONE);retry.setEnabled(!busy);}
    private void capabilities(){
        if(busy)return;if(!client.available()){notice("Откройте игры через главный экран своего профиля. Одиночная игра доступна без сети.");return;}
        busy=true;controls();client.read("/capabilities",(json,error)->{
            busy=false;supported=false;
            if(error!=null){notice(GameOnlineBridge.explain(error));controls();return;}
            JSONArray games=json.optJSONArray("games");if(games!=null)for(int i=0;i<games.length();i++)if(gameType.equals(games.optString(i)))supported=true;
            if(!supported){notice(snake?"Сервер пока не поддерживает семейную Змейку. Одиночная игра доступна без сети.":arcanoid?"Сервер пока не поддерживает семейный Арканоид. Одиночная игра доступна без сети.":"Сервер пока не поддерживает Морской бой. Можно играть против компьютера.");controls();return;}
            loadMembers();
        });
    }
    private void loadMembers(){busy=true;client.read("/members",(json,error)->{
        busy=false;if(error!=null){notice("Не удалось получить семью. Вернитесь на главный экран и проверьте связь.");controls();return;}
        actor=json.optString("actorMemberId");members=json.optJSONArray("members");if(members==null)members=new JSONArray();
        controls();if(active)refresh();
    });}
    private void choose(){
        if(!supported||busy||client.hasPending())return;
        java.util.List<JSONObject> others=new java.util.ArrayList<>();java.util.List<String> names=new java.util.ArrayList<>();
        for(int i=0;i<members.length();i++){JSONObject m=members.optJSONObject(i);if(m!=null&&!m.optString("memberId").equals(actor)){
            others.add(m);String name=m.optString("displayName");names.add(name.isEmpty()?"Участник семьи":name);}}
        if(others.isEmpty()){notice("Для совместной игры нужен ещё один участник семьи.");return;}
        new MaterialAlertDialogBuilder(this).setTitle(snake?"Пригласить в Змейку":arcanoid?"Пригласить в Арканоид":"Пригласить в Морской бой")
            .setItems(names.toArray(new String[0]),(d,index)->sendInvitation(others.get(index)))
            .setNegativeButton("Отмена",null).show();
    }
    private void sendInvitation(JSONObject member){
        if(busy||client.hasPending())return;
        try{JSONObject body=new JSONObject().put("gameId",UUID.randomUUID().toString()).put("type",gameType).put("targetMemberId",member.getString("memberId"));
            beginAction();client.write("/",body,this::result);
        }catch(Exception error){notice("Не удалось подготовить приглашение.");}
    }
    private void refresh(){
        handler.removeCallbacks(tick);
        if(!active||busy||!supported)return;
        if(reading){refreshAgain=true;return;}
        reading=true;
        long requestedBeforeAction=actionGeneration;
        reader.read("/",(json,error)->{
            reading=false;
            boolean refreshRequested=refreshAgain;
            refreshAgain=false;
            if(requestedBeforeAction!=actionGeneration){refresh();return;}
            if(!client.available()){matches.removeAllViews();shownRows=null;supported=false;notice("Профиль изменился. Откройте игры через главный экран.");controls();return;}
            if(error!=null){if(!showingActionError)notice("Партии не обновились. Проверьте связь и обновите список.");}
            else displayRows(json.optJSONArray("games"));
            controls();
            handler.removeCallbacks(tick);
            if(active)handler.postDelayed(tick,refreshRequested?0:4000);
        });
    }
    private void displayRows(JSONArray rows){
        JSONArray next=rows==null?new JSONArray():rows;
        if(shownRows!=null&&shownRows.toString().equals(next.toString()))return;
        shownRows=next;
        showMatches(next);
    }
    private void hideGame(String id){
        if(shownRows==null||id.isEmpty())return;
        JSONArray remaining=new JSONArray();
        for(int i=0;i<shownRows.length();i++){
            JSONObject game=shownRows.optJSONObject(i);
            if(game!=null&&!id.equals(game.optString("id")))remaining.put(game);
        }
        displayRows(remaining);
    }
    private void showMatches(JSONArray rows){
        matches.removeAllViews();int count=0;
        String focused=getIntent().getStringExtra("invitationId");
        if(rows!=null&&focused!=null){
            JSONArray ordered=new JSONArray();
            for(int i=0;i<rows.length();i++){JSONObject g=rows.optJSONObject(i);if(g!=null&&focused.equals(g.optString("id")))ordered.put(g);}
            if(ordered.length()==0)notice("Это приглашение уже недоступно. Можно отправить новое.");
            for(int i=0;i<rows.length();i++){JSONObject g=rows.optJSONObject(i);if(g!=null&&!focused.equals(g.optString("id")))ordered.put(g);}
            rows=ordered;
        }
        if(rows!=null)for(int i=0;i<rows.length();i++){
            JSONObject g=rows.optJSONObject(i);if(g==null||!gameType.equals(g.optString("type")))continue;
            String status=g.optString("status"),id=g.optString("id");boolean host="X".equals(g.optString("myMark"));
            JSONObject peer=g.optJSONObject(host?"guest":"host");String name=peer==null?"Участник семьи":peer.optString("displayName","Участник семьи");
            String state="INVITED".equals(status)?host?"Приглашение отправлено":"Приглашает сыграть":"SETUP".equals(status)?snake||arcanoid?"Ожидает готовности игроков":"Расстановка кораблей":"PLAYING".equals(status)?"Партия идёт":"Партия завершена";
            if("EXPIRED".equals(status))state="Приглашение или партия истекли";
            else if("DECLINED".equals(status))state="Приглашение отклонено";
            else if("CANCELLED".equals(status))state="Приглашение отменено";
            if(!java.util.Arrays.asList("INVITED","SETUP","PLAYING").contains(status))continue;
            com.google.android.material.card.MaterialCardView card=new com.google.android.material.card.MaterialCardView(this);
            card.setCardElevation(0);card.setRadius(dp(16));card.setStrokeWidth(dp(1));card.setStrokeColor(getColor(R.color.cw_color_outline_variant));card.setCardBackgroundColor(getColor(R.color.cw_color_surface));
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(16),dp(8),dp(16),dp(12));card.addView(row);
            LinearLayout identity=new LinearLayout(this);identity.setGravity(android.view.Gravity.CENTER_VERTICAL);row.addView(identity);
            android.widget.ImageView avatar=new android.widget.ImageView(this);android.graphics.drawable.Drawable image=AvatarPresetCatalog.createDrawable(this,peer==null?null:peer.optString("avatarKey"));
            avatar.setImageDrawable(image==null?LetterAvatarFactory.create(this,name):image);avatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);avatar.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            avatar.setOutlineProvider(new android.view.ViewOutlineProvider(){public void getOutline(android.view.View view,android.graphics.Outline outline){outline.setOval(0,0,view.getWidth(),view.getHeight());}});avatar.setClipToOutline(true);identity.addView(avatar,new LinearLayout.LayoutParams(dp(48),dp(48)));
            android.widget.TextView person=label(name,18);person.setTypeface(null,android.graphics.Typeface.BOLD);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,-2,1);np.leftMargin=dp(12);identity.addView(person,np);row.addView(label(state,16));
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=dp(16);matches.addView(card,cp);count++;
            if("INVITED".equals(status)){
                if(!host)row.addView(button("Принять приглашение",true,()->action(g,"ACCEPT")));
                row.addView(button(host?"Отменить приглашение":"Отклонить",false,()->action(g,host?"CANCEL":"DECLINE")));
            }else {
                if(!(snake||arcanoid)||realtimeCanOpen(status))row.addView(button("Продолжить партию",true,()->open(g)));
                row.addView(button("Завершить и убрать",false,()->remove(g)));
            }
        }
        if(count==0)matches.addView(label("Пока нет партий. Пригласите участника семьи, чтобы сыграть вместе.",16));
    }
    private void remove(JSONObject game){
        if(busy||client.hasPending())return;
        new MaterialAlertDialogBuilder(this).setTitle("Завершить и убрать?").setMessage("Партия завершится у обоих игроков.")
            .setNegativeButton("Продолжить играть",null).setPositiveButton("Завершить и убрать",(dialog,which)->{
                try{JSONObject body=new JSONObject().put("operationId",UUID.randomUUID().toString()).put("version",game.getLong("version")).put("confirmActive",true);
                    String path="/"+game.getString("id");
                    beginAction();client.delete(path,body,this::result);
                }catch(Exception invalid){busy=false;controls();notice("Не удалось подготовить завершение партии.");}
            }).show();
    }
    private void action(JSONObject g,String action){
        if(busy||client.hasPending())return;
        try{JSONObject body=new JSONObject().put("actionId",UUID.randomUUID().toString()).put("version",g.getLong("version")).put("action",action);
            String path="/"+g.getString("id")+"/actions";
            beginAction();client.write(path,body,this::result);
        }catch(Exception error){notice("Не удалось подготовить действие.");}
    }
    private void beginAction(){
        actionGeneration++;
        busy=true;
        controls();
    }
    private void result(JSONObject json,Exception error){
        busy=false;showingActionError=error!=null;controls();notice(error==null?"Готово.":GameOnlineBridge.explain(error));
        if(error==null&&json.optJSONObject("game")!=null){JSONObject g=json.optJSONObject("game");
            String status=g.optString("status");
            if("INVITED".equals(status)&&"X".equals(g.optString("myMark")))
                notice("Приглашение отправлено. Ждём ответа участника семьи.");
            if(active&&gameType.equals(g.optString("type"))&&("SETUP".equals(status)||"PLAYING".equals(status)))
                open(g);
            if(!java.util.Arrays.asList("INVITED","SETUP","PLAYING").contains(status))hideGame(g.optString("id"));
        }
        if(active)refresh();
    }
    private boolean realtimeCanOpen(String status){return "SETUP".equals(status)||"PLAYING".equals(status)||"WON".equals(status)||"LOST".equals(status);}
    private void open(JSONObject game){
        if(!gameType.equals(game.optString("type"))){notice("Эта партия относится к другой игре. Откройте её через каталог игр.");return;}
        if((snake||arcanoid)&&!realtimeCanOpen(game.optString("status"))){notice("Партия закрыта. Можно отправить новое приглашение.");return;}
        startActivity(new Intent(this,snake?SnakeOnlineActivity.class:arcanoid?ArcanoidOnlineActivity.class:BattleshipActivity.class).putExtra("gameId",game.optString("id")));
    }
}
