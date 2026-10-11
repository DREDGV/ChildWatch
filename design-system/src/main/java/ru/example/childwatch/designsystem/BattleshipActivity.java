package ru.example.childwatch.designsystem;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.Nullable;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

/** Local solo game and actor-filtered server game share the same selection/confirmation UX. */
public class BattleshipActivity extends GameScreen {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private int[] fleet;
    private int selected=-1,ship=0;
    private boolean vertical,own,active,busy,blocked,setup=true;
    private BattleshipEngine engine;
    private BattleshipBoardView board;
    private TextView status,details;
    private MaterialButton primary,random,rotate,toggle,retry,resign;
    private GameOnlineBridge.Client online;
    private String gameId;
    private JSONObject game;
    private String draftKey;
    private final Runnable tick=()->poll();
    private final Runnable ai=()->{
        if(!active||engine==null||engine.currentPlayer()!=1||engine.state()!=BattleshipEngine.State.PLAYING)return;
        BattleshipEngine.Shot shot=engine.aiMove();
        notice(shot==BattleshipEngine.Shot.MISS?"Компьютер промахнулся.":shot==BattleshipEngine.Shot.SUNK?"Компьютер потопил корабль.":shot==BattleshipEngine.Shot.WIN?"Компьютер потопил весь флот.":"Компьютер попал в корабль.");
        save();render();scheduleAi();
    };
    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);screen("Морской бой");
        prefs=getSharedPreferences("battleship-local",MODE_PRIVATE);
        gameId=getIntent().getStringExtra("gameId");
        draftKey=gameId==null?"solo-draft":"fleet:"+gameId;
        fleet=BattleshipEngine.randomFleet(new Random());
        try {
            JSONArray saved=new JSONArray(prefs.getString(draftKey,"[]"));int[] restored=new int[100];
            if(saved.length()==100) { for(int i=0;i<100;i++)restored[i]=saved.getInt(i);if(BattleshipEngine.validateFleet(restored))fleet=restored; }
            if(gameId==null&&!getIntent().getBooleanExtra("new",false)&&prefs.contains("solo-match")) {
                engine=BattleshipEngine.restore(prefs.getString("solo-match",""));setup=false;
            }
        } catch(Exception badSave) { notice("Сохранённая партия недоступна. Можно начать новую."); }
        if(gameId!=null)online=new GameOnlineBridge.Client(this);
        status=label("Расставьте корабли",22);content.addView(status);
        details=label("",14);content.addView(details);
        board=new BattleshipBoardView(this);content.addView(board,new android.widget.LinearLayout.LayoutParams(-1,-2));
        board.setOnCellSelected(this::select);
        primary=addButton("Флот готов",true,this::confirm);
        toggle=addButton("Мой флот",false,()->{own=!own;selected=-1;render();});
        rotate=addButton("Повернуть выбранный корабль",false,this::rotate);
        random=addButton("Расставить заново",false,()->{fleet=BattleshipEngine.randomFleet(new Random());ship=0;selected=-1;saveDraft();render();});
        retry=addButton("Повторить отправку",true,()->{
            if(online==null||busy)return;busy=true;render();online.retry(this::response);
        });
        content.removeView(retry);content.addView(retry,1);
        addButton(gameId==null?"Новая партия":"Обновить поле",false,()->{
            if(gameId!=null){poll();return;}
            new MaterialAlertDialogBuilder(this).setTitle("Начать заново?").setMessage("Текущая одиночная партия будет заменена.")
                .setNegativeButton("Продолжить",null).setPositiveButton("Новая партия",(d,w)->{
                    handler.removeCallbacks(ai);engine=null;setup=true;own=false;selected=-1;ship=0;
                    prefs.edit().remove("solo-match").apply();render();
                }).show();
        });
        if(gameId!=null)resign=addButton("Завершить партию",false,()->{
            if(game==null||busy)return;
            new MaterialAlertDialogBuilder(this).setTitle("Завершить партию?").setMessage("Сопернику будет засчитана победа.")
                .setNegativeButton("Продолжить",null).setPositiveButton("Завершить",(d,w)->action("RESIGN",null,null)).show();
        });
        render();
    }
    @Override protected void onResume(){super.onResume();active=true;if(online!=null)poll();else scheduleAi();}
    @Override protected void onPause(){active=false;handler.removeCallbacks(tick);handler.removeCallbacks(ai);super.onPause();}
    @Override protected void onDestroy(){if(online!=null)online.close();super.onDestroy();}
    private void saveDraft(){JSONArray a=new JSONArray();for(int n:fleet)a.put(n);prefs.edit().putString(draftKey,a.toString()).apply();}
    private void save(){if(engine!=null)prefs.edit().putString("solo-match",engine.snapshot()).apply();}
    private void select(int cell){
        if(busy||(online!=null&&online.hasPending()))return;
        if(setup){
            if(ship!=0&&fleet[cell]==0){int[] moved=BattleshipEngine.moveShip(fleet,ship,cell,vertical);
                if(moved==null){notice("Корабль не помещается или касается другого. Выберите другое место.");return;}
                fleet=moved;saveDraft();
            } else if(fleet[cell]!=0){ship=fleet[cell];vertical=orientation(ship);notice("Выбран корабль. Нажмите на новое начало или поверните его.");}
        }
        selected=cell;render();
    }
    private boolean orientation(int id){int first=-1;for(int i=0;i<100;i++)if(fleet[i]==id){if(first<0)first=i;else return i-first==10;}return false;}
    private void rotate(){if(ship==0)return;int start=0;while(fleet[start]!=ship)start++;
        int[] moved=BattleshipEngine.moveShip(fleet,ship,start,!vertical);
        if(moved==null){notice("Здесь корабль нельзя повернуть: нужен свободный промежуток.");return;}
        fleet=moved;vertical=!vertical;saveDraft();render();}
    private JSONArray ships(){JSONArray all=new JSONArray();for(int id=1;id<=10;id++){JSONArray row=new JSONArray();for(int i=0;i<100;i++)if(fleet[i]==id)row.put(i);all.put(row);}return all;}
    private void confirm(){
        if(busy)return;
        if(setup){
            if(online!=null){action("PLACE_FLEET","ships",ships());return;}
            engine=new BattleshipEngine(BattleshipEngine.Mode.SOLO,new Random().nextLong(),fleet,BattleshipEngine.randomFleet(new Random()));
            setup=false;own=false;selected=-1;save();render();return;
        }
        if(selected<0||own)return;
        if(online!=null){action("FIRE","cell",selected);return;}
        if(engine.currentPlayer()!=0)return;
        BattleshipEngine.Shot result=engine.shoot(0,selected/10,selected%10);
        notice(result==BattleshipEngine.Shot.MISS?"Промах. Ход компьютера.":result==BattleshipEngine.Shot.SUNK?"Корабль потоплен! Стреляйте ещё.":result==BattleshipEngine.Shot.WIN?"Все корабли соперника потоплены!":"Попадание! Стреляйте ещё.");
        selected=-1;save();render();scheduleAi();
    }
    private void scheduleAi(){handler.removeCallbacks(ai);if(active&&engine!=null&&engine.currentPlayer()==1&&engine.state()==BattleshipEngine.State.PLAYING)handler.postDelayed(ai,650);}
    private void poll(){
        handler.removeCallbacks(tick);if(!active||busy||blocked||online==null)return;
        busy=true;render();online.read("/"+gameId,this::response);
    }
    private void response(JSONObject value,Exception error){
        busy=false;
        if(!online.available()){game=null;selected=-1;render();notice("Профиль изменился. Вернитесь на главный экран и откройте игры заново.");return;}
        if(error instanceof GameOnlineBridge.Failure){int code=((GameOnlineBridge.Failure)error).status;
            if(code==409||code==401||code==403){game=null;selected=-1;}
            if(code==401||code==403)blocked=true;
        }
        if(error!=null)notice(online.hasPending()?"Ответ не пришёл. Можно безопасно повторить отправку.":GameOnlineBridge.explain(error));
        else {JSONObject next=value.optJSONObject("game");if(next!=null&&gameId.equals(next.optString("id"))){boolean changed=game==null||next.optLong("version")!=game.optLong("version");game=next;if(changed){selected=-1;notice("");}}else notice("Не удалось подтвердить выбранную партию. Обновите поле.");}
        render();if(active&&!blocked&&!terminal())handler.postDelayed(tick,3000);
    }
    private void action(String kind,String key,Object value){
        if(game==null||busy||online.hasPending())return;
        try{JSONObject body=new JSONObject().put("actionId",UUID.randomUUID().toString()).put("version",game.getLong("version")).put("action",kind);
            if(key!=null)body.put(key,value);busy=true;render();online.write("/"+gameId+"/actions",body,this::response);
        }catch(Exception error){busy=false;notice("Не удалось подготовить действие.");render();}
    }
    private boolean terminal(){return game!=null&&!Arrays.asList("INVITED","SETUP","PLAYING").contains(game.optString("status"));}
    private BattleshipEngine.Cell[] onlineCells(boolean ownField){
        BattleshipEngine.Cell[] cells=new BattleshipEngine.Cell[100];Arrays.fill(cells,BattleshipEngine.Cell.UNKNOWN);
        JSONObject b=game.optJSONObject("battleship");if(b==null)return cells;
        markShips(cells,b.optJSONArray(ownField?"myShips":"opponentShips"),BattleshipEngine.Cell.SHIP);
        JSONArray shots=b.optJSONArray(ownField?"opponentShots":"myShots");
        if(shots!=null)for(int i=0;i<shots.length();i++){JSONObject shot=shots.optJSONObject(i);if(shot==null)continue;int cell=shot.optInt("cell",-1);if(cell>=0&&cell<100)cells[cell]="MISS".equals(shot.optString("result"))?BattleshipEngine.Cell.MISS:BattleshipEngine.Cell.HIT;}
        markShips(cells,b.optJSONArray(ownField?"mySunkShips":"opponentSunkShips"),BattleshipEngine.Cell.SUNK);return cells;
    }
    private void markShips(BattleshipEngine.Cell[] cells,JSONArray ships,BattleshipEngine.Cell mark){if(ships==null)return;
        for(int i=0;i<ships.length();i++){JSONArray s=ships.optJSONArray(i);if(s==null)continue;for(int n=0;n<s.length();n++){int cell=s.optInt(n,-1);if(cell>=0&&cell<100)cells[cell]=mark;}}}
    private void render(){
        if(board==null)return;
        boolean turn=false,done=false;
        if(online!=null){
            JSONObject b=game==null?null:game.optJSONObject("battleship");
            setup=game!=null&&"SETUP".equals(game.optString("status"))&&b!=null&&!b.optBoolean("myReady");
            done=terminal();turn=game!=null&&"PLAYING".equals(game.optString("status"))&&game.optString("myMark").equals(game.optString("nextMark"));
            status.setText(blocked?"Нет доступа к партии":game==null?"Подключаемся к партии…":setup?"Расставьте свой флот":done?"Партия завершена":turn?"Ваш ход":"Ждём соперника");
            if(done)notice("WON".equals(game.optString("status"))||"RESIGNED".equals(game.optString("status"))?
                (game.optJSONObject("X".equals(game.optString("myMark"))?"host":"guest").optString("memberId").equals(game.optString("winnerMemberId"))?"Вы победили!":"Партия закончена. Можно сыграть ещё раз."):"Срок партии истёк или приглашение отменено.");
        }else if(engine!=null){done=engine.state()==BattleshipEngine.State.FINISHED;turn=engine.currentPlayer()==0&&!done;
            status.setText(done?(engine.winner()==0?"Вы победили!":"Компьютер победил"):turn?"Ваш ход":"Ход компьютера");}
        else status.setText("Расставьте свой флот");
        BattleshipEngine.Cell[] cells=new BattleshipEngine.Cell[100];
        if(setup){for(int i=0;i<100;i++)cells[i]=fleet[i]==0?BattleshipEngine.Cell.UNKNOWN:BattleshipEngine.Cell.SHIP;}
        else if(online!=null&&game!=null)cells=onlineCells(own);
        else if(engine!=null)for(int i=0;i<100;i++)cells[i]=engine.cell(own?0:1,i/10,i%10,own||done);
        else Arrays.fill(cells,BattleshipEngine.Cell.UNKNOWN);
        board.setBoard(cells,selected);
        details.setText(setup?"10 кораблей · не должны соприкасаться. Выберите корабль, затем новое начало.":
            (own?"Мой флот":"Поле соперника")+" · × попадание, ● промах"+(engine!=null?"\nОсталось кораблей: у вас "+engine.remainingShips(0)+", у соперника "+engine.remainingShips(1):""));
        boolean pending=online!=null&&online.hasPending();
        if(pending)status.setText(busy?"Отправляем действие…":"Действие ждёт подтверждения");
        primary.setText(setup?"Флот готов":selected<0?"Выберите клетку":"Выстрелить в "+BattleshipBoardView.coordinate(selected));
        primary.setEnabled(!busy&&!pending&&(setup||(turn&&!own&&selected>=0&&cells[selected]==BattleshipEngine.Cell.UNKNOWN)));
        primary.setVisibility(done?View.GONE:View.VISIBLE);
        random.setVisibility(setup?View.VISIBLE:View.GONE);rotate.setVisibility(setup?View.VISIBLE:View.GONE);rotate.setEnabled(ship!=0&&!busy&&!pending);random.setEnabled(!busy&&!pending);
        toggle.setVisibility(setup?View.GONE:View.VISIBLE);toggle.setText(own?"Поле соперника":"Мой флот");
        retry.setVisibility(pending?View.VISIBLE:View.GONE);retry.setEnabled(!busy);
        if(resign!=null){resign.setVisibility(game!=null&&!done?View.VISIBLE:View.GONE);resign.setEnabled(!busy&&!pending);}
    }
}
