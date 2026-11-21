package ru.example.childwatch.designsystem;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;
import com.google.android.material.button.MaterialButton;
import org.json.JSONObject;
import java.util.UUID;

/** A rematch always creates a fresh invitation. Never automatically accepts for the peer. */
final class GameResultActions {
    private final Activity activity;private final String gameId;
    private final LinearLayout panel;private final MaterialButton again;
    private final GameOnlineBridge.Client client;
    GameResultActions(Activity activity,LinearLayout parent,String gameId){
        this.activity=activity;this.gameId=gameId;client=new GameOnlineBridge.Client(activity);
        panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        int padding=Math.round(16*activity.getResources().getDisplayMetrics().density);panel.setPadding(padding,0,padding,padding);
        again=new MaterialButton(activity);again.setText("Сыграть ещё");again.setAllCaps(false);
        panel.addView(again,new LinearLayout.LayoutParams(-1,-2));
        MaterialButton back=new MaterialButton(activity);back.setText("К играм");back.setAllCaps(false);
        panel.addView(back,new LinearLayout.LayoutParams(-1,-2));
        again.setOnClickListener(v->invite());back.setOnClickListener(v->{activity.startActivity(new Intent(activity,GamesActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));activity.finish();});
        panel.setVisibility(View.GONE);parent.addView(panel,new LinearLayout.LayoutParams(-1,-2));
    }
    void show(boolean finished){panel.setVisibility(finished?View.VISIBLE:View.GONE);}
    private void invite(){
        if(client.hasPending()){notice("Повторите неподтверждённое приглашение в списке партий.");return;}
        again.setEnabled(false);
        client.read("/"+gameId,(value,error)->{
            if(error!=null){again.setEnabled(true);notice(GameOnlineBridge.explain(error));return;}
            try{
                JSONObject game=value.getJSONObject("game");String type=game.getString("type");
                JSONObject peer=game.getJSONObject("X".equals(game.getString("myMark"))?"guest":"host");
                JSONObject body=new JSONObject().put("gameId",UUID.randomUUID().toString()).put("type",type).put("targetMemberId",peer.getString("memberId"));
                client.write("/",body,(result,failure)->{
                    again.setEnabled(true);if(failure!=null){notice(GameOnlineBridge.explain(failure));return;}
                    JSONObject invited=result.optJSONObject("game");
                    activity.startActivity(new Intent(activity,FamilyGameLobbyActivity.class).putExtra("gameType",type).putExtra("invitationId",invited==null?"":invited.optString("id")));
                    activity.finish();
                });
            }catch(Exception invalid){again.setEnabled(true);notice("Не удалось подготовить новую партию.");}
        });
    }
    private void notice(String text){Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
    void close(){client.close();}
}
