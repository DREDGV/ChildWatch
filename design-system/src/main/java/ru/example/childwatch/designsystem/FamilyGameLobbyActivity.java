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
    private final Handler handler=new Handler(Looper.getMainLooper());
    private GameOnlineBridge.Client client;
    private LinearLayout matches;
    private boolean active,busy,supported;
    private JSONArray members=new JSONArray();
    private String actor="";
    private com.google.android.material.button.MaterialButton invite,retry;
    private final Runnable tick=()->refresh();
    @Override public void onCreate(Bundle state){
        super.onCreate(state);screen("Семейный Морской бой");client=new GameOnlineBridge.Client(this);
        notice("Пригласите близкого. Каждый расставляет свой флот на своём телефоне; корабли соперника скрыты.");
        invite=addButton("Пригласить в игру",true,this::choose);
        retry=addButton("Повторить отправку",false,()->{
            if(busy)return;busy=true;controls();client.retry(this::result);
        });
        addButton("Обновить партии",false,this::refresh);
        matches=new LinearLayout(this);matches.setOrientation(LinearLayout.VERTICAL);content.addView(matches);
        controls();
    }
    @Override protected void onResume(){super.onResume();active=true;capabilities();}
    @Override protected void onPause(){active=false;handler.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){client.close();super.onDestroy();}
    private void controls(){invite.setEnabled(supported&&!busy&&!client.hasPending()&&members.length()>0);
        retry.setVisibility(client.hasPending()?android.view.View.VISIBLE:android.view.View.GONE);retry.setEnabled(!busy);}
    private void capabilities(){
        if(busy)return;if(!client.available()){notice("Откройте игры через главный экран своего профиля. Одиночная игра доступна без сети.");return;}
        busy=true;controls();client.read("/capabilities",(json,error)->{
            busy=false;supported=false;
            if(error!=null){notice(GameOnlineBridge.explain(error));controls();return;}
            JSONArray games=json.optJSONArray("games");if(games!=null)for(int i=0;i<games.length();i++)if("BATTLESHIP".equals(games.optString(i)))supported=true;
            if(!supported){notice("Сервер пока не поддерживает Морской бой. Можно играть против компьютера.");controls();return;}
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
        new MaterialAlertDialogBuilder(this).setTitle("Кого пригласить?").setItems(names.toArray(new String[0]),(d,index)->{
            new MaterialAlertDialogBuilder(this).setTitle("Пригласить "+names.get(index)+"?")
                .setMessage("Приглашение появится в разделе «Игры» на его телефоне и будет доступно 15 минут.")
                .setNegativeButton("Отмена",null).setPositiveButton("Пригласить",(confirm,w)->{
                    try{JSONObject body=new JSONObject().put("gameId",UUID.randomUUID().toString()).put("type","BATTLESHIP").put("targetMemberId",others.get(index).getString("memberId"));
                        busy=true;controls();client.write("/",body,this::result);
                    }catch(Exception error){notice("Не удалось подготовить приглашение.");}
                }).show();
        }).setNegativeButton("Отмена",null).show();
    }
    private void refresh(){
        handler.removeCallbacks(tick);if(!active||busy||!supported)return;
        busy=true;controls();client.read("/",(json,error)->{
            busy=false;
            if(!client.available()){matches.removeAllViews();supported=false;notice("Профиль изменился. Откройте игры через главный экран.");controls();return;}
            if(error!=null)notice("Партии не обновились. Сохранённые действия можно повторить после восстановления связи.");
            else showMatches(json.optJSONArray("games"));
            controls();if(active)handler.postDelayed(tick,4000);
        });
    }
    private void showMatches(JSONArray rows){
        matches.removeAllViews();int count=0;
        if(rows!=null)for(int i=0;i<rows.length();i++){
            JSONObject g=rows.optJSONObject(i);if(g==null||!"BATTLESHIP".equals(g.optString("type")))continue;
            String status=g.optString("status"),id=g.optString("id");boolean host="X".equals(g.optString("myMark"));
            JSONObject peer=g.optJSONObject(host?"guest":"host");String name=peer==null?"Участник семьи":peer.optString("displayName","Участник семьи");
            String state="INVITED".equals(status)?host?"Приглашение отправлено":"Приглашает сыграть":"SETUP".equals(status)?"Расстановка кораблей":"PLAYING".equals(status)?"Партия идёт":"Партия завершена";
            matches.addView(label(name+"\n"+state,18));count++;
            if("INVITED".equals(status)){
                if(!host)matches.addView(button("Принять приглашение",true,()->action(g,"ACCEPT")));
                matches.addView(button(host?"Отменить приглашение":"Отклонить",false,()->action(g,host?"CANCEL":"DECLINE")));
            }else matches.addView(button("Открыть поле",true,()->startActivity(new Intent(this,BattleshipActivity.class).putExtra("gameId",id))));
        }
        if(count==0)matches.addView(label("Пока нет партий. Пригласите участника семьи, чтобы сыграть вместе.",16));
    }
    private void action(JSONObject g,String action){
        if(busy||client.hasPending())return;
        try{JSONObject body=new JSONObject().put("actionId",UUID.randomUUID().toString()).put("version",g.getLong("version")).put("action",action);
            busy=true;controls();client.write("/"+g.getString("id")+"/actions",body,this::result);
        }catch(Exception error){notice("Не удалось подготовить действие.");}
    }
    private void result(JSONObject json,Exception error){
        busy=false;controls();notice(error==null?"Действие подтверждено.":GameOnlineBridge.explain(error));
        if(error==null&&json.optJSONObject("game")!=null){JSONObject g=json.optJSONObject("game");
            if(active&&("SETUP".equals(g.optString("status"))||"PLAYING".equals(g.optString("status"))))
                startActivity(new Intent(this,BattleshipActivity.class).putExtra("gameId",g.optString("id")));
        }
        if(active)refresh();
    }
}
