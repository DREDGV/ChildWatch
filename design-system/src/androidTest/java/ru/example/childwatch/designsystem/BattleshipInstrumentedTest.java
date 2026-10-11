package ru.example.childwatch.designsystem;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BattleshipInstrumentedTest {
    private <T extends View> T find(View view,Class<T> type,String text){
        if(type.isInstance(view)&&(text==null||view instanceof TextView&&((TextView)view).getText().toString().equals(text)))return type.cast(view);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){T v=find(((ViewGroup)view).getChildAt(i),type,text);if(v!=null)return v;}return null;
    }
    @Test public void soloFieldConfirmsShotsAndRestoresAtLargeFont() throws Exception {
        Instrumentation runner=InstrumentationRegistry.getInstrumentation();Context context=runner.getTargetContext();
        assertFalse(((android.app.KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked());
        context.getSharedPreferences("battleship-local",0).edit().clear().commit();
        BattleshipFixtureActivity activity=(BattleshipFixtureActivity)runner.startActivitySync(new Intent(context,BattleshipFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            Thread.sleep(500);runner.waitForIdleSync();
            View root=activity.getWindow().getDecorView();
            BattleshipBoardView board=find(root,BattleshipBoardView.class,null);
            assertNotNull(board);assertTrue(board.getWidth()>context.getResources().getDisplayMetrics().widthPixels*.75);
            assertEquals(board.getWidth(),board.getHeight());
            screenshot(runner,context,"battleship-setup.png");
            runner.runOnMainSync(()->find(root,TextView.class,"Флот готов").performClick());
            runner.waitForIdleSync();
            String before=context.getSharedPreferences("battleship-local",0).getString("solo-match","");assertFalse(before.isEmpty());
            runner.runOnMainSync(()->{
                float step=board.getWidth()/11f;MotionEvent e=MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,step*1.5f,step*1.5f,0);
                board.dispatchTouchEvent(e);e.recycle();
            });
            assertEquals("Selecting a cell cannot fire",before,context.getSharedPreferences("battleship-local",0).getString("solo-match",""));
            runner.runOnMainSync(()->find(root,TextView.class,"Выстрелить в А1").performClick());
            Thread.sleep(1300);runner.waitForIdleSync();
            String saved=context.getSharedPreferences("battleship-local",0).getString("solo-match","");
            assertTrue(BattleshipEngine.restore(saved).shotsFired(0)==1);screenshot(runner,context,"battleship-play.png");
            runner.runOnMainSync(activity::finish);runner.waitForIdleSync();
            BattleshipFixtureActivity reopened=(BattleshipFixtureActivity)runner.startActivitySync(new Intent(context,BattleshipFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try{runner.waitForIdleSync();assertNotNull(find(reopened.getWindow().getDecorView(),TextView.class,"Мой флот"));
                assertEquals(1,BattleshipEngine.restore(context.getSharedPreferences("battleship-local",0).getString("solo-match","")).shotsFired(0));
            }finally{runner.runOnMainSync(reopened::finish);}
        }finally{runner.runOnMainSync(activity::finish);context.getSharedPreferences("battleship-local",0).edit().clear().commit();}
    }
    @Test public void ambiguousWriteSurvivesClientRestartAndRepeatsExactAction() throws Exception {
        Instrumentation runner=InstrumentationRegistry.getInstrumentation();Context context=runner.getTargetContext();
        GameOnlineBridge.Host previous=GameOnlineBridge.host;
        java.util.List<String> calls=new java.util.ArrayList<>();String scope="fixture-"+java.util.UUID.randomUUID();
        GameOnlineBridge.host=new GameOnlineBridge.Host(){
            public String scopeKey(){return scope;}
            public JSONObject request(String method,String path,JSONObject body)throws Exception{
                assertFalse(android.os.Looper.myLooper()==android.os.Looper.getMainLooper());
                calls.add(body.toString());if(calls.size()==1)throw new IOException("ACK_LOST");return new JSONObject().put("success",true);
            }
        };
        GameOnlineBridge.Client[] clients=new GameOnlineBridge.Client[2];
        try{
            CountDownLatch first=new CountDownLatch(1);JSONObject body=new JSONObject().put("actionId","fixed-action").put("version",4).put("action","FIRE").put("cell",9);
            runner.runOnMainSync(()->{clients[0]=new GameOnlineBridge.Client(context);clients[0].write("/fixture/actions",body,(v,e)->first.countDown());});
            body.put("cell",99);assertTrue(first.await(5,TimeUnit.SECONDS));assertTrue(clients[0].hasPending());
            runner.runOnMainSync(clients[0]::close);
            CountDownLatch second=new CountDownLatch(1);
            runner.runOnMainSync(()->{clients[1]=new GameOnlineBridge.Client(context);assertTrue(clients[1].hasPending());clients[1].retry((v,e)->second.countDown());});
            assertTrue(second.await(5,TimeUnit.SECONDS));assertFalse(clients[1].hasPending());
            assertEquals(2,calls.size());assertEquals(calls.get(0),calls.get(1));assertEquals(9,new JSONObject(calls.get(1)).getInt("cell"));
        }finally{runner.runOnMainSync(()->{for(GameOnlineBridge.Client client:clients)if(client!=null)client.close();});
            GameOnlineBridge.host=previous;context.getSharedPreferences("family-games-outbox",0).edit().remove("pending:"+scope).commit();}
    }
    private void screenshot(Instrumentation runner,Context context,String name)throws Exception{
        Bitmap b=runner.getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}finally{b.recycle();}
    }
    @Test public void onlineFieldRetriesUnconfirmedShot()throws Exception{
        Instrumentation runner=InstrumentationRegistry.getInstrumentation();Context context=runner.getTargetContext();
        assertFalse(((android.app.KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked());
        GameOnlineBridge.Host previous=GameOnlineBridge.host;String scope="online-fixture-"+java.util.UUID.randomUUID();
        JSONObject projected=new JSONObject("{id:'fixture-game',type:'BATTLESHIP',status:'PLAYING',version:1,myMark:'X',nextMark:'X',host:{memberId:'host'},guest:{memberId:'guest'},battleship:{myShips:[[11,12,13,14]],myShots:[],opponentShots:[],mySunkShips:[],opponentSunkShips:[]}}" );
        java.util.List<String> actions=new java.util.ArrayList<>();
        GameOnlineBridge.host=new GameOnlineBridge.Host(){
            public String scopeKey(){return scope;}
            public JSONObject request(String method,String path,JSONObject body)throws Exception{
                if("POST".equals(method)){
                    actions.add(body.toString());
                    if(actions.size()==1){projected.put("version",2).put("nextMark","O");
                        projected.getJSONObject("battleship").getJSONArray("myShots").put(new JSONObject().put("cell",0).put("result","MISS"));throw new IOException("ACK_LOST");}
                }
                return new JSONObject().put("game",new JSONObject(projected.toString()));
            }
        };
        BattleshipFixtureActivity activity=null;
        try{
            activity=(BattleshipFixtureActivity)runner.startActivitySync(new Intent(context,BattleshipFixtureActivity.class).putExtra("gameId","fixture-game").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(800);runner.waitForIdleSync();View root=activity.getWindow().getDecorView();
            assertNotNull(find(root,TextView.class,"Ваш ход"));
            BattleshipBoardView board=find(root,BattleshipBoardView.class,null);
            runner.runOnMainSync(()->{float s=board.getWidth()/11f;MotionEvent e=MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,s*1.5f,s*1.5f,0);board.dispatchTouchEvent(e);e.recycle();});
            runner.runOnMainSync(()->find(root,TextView.class,"Выстрелить в А1").performClick());
            Thread.sleep(500);runner.waitForIdleSync();
            TextView retry=find(root,TextView.class,"Повторить отправку");assertTrue(retry.isShown());
            android.graphics.Rect shown=new android.graphics.Rect();assertTrue("Retry is reachable without scrolling",retry.getGlobalVisibleRect(shown));
            runner.runOnMainSync(()->{float s=board.getWidth()/11f;MotionEvent e=MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,s*2.5f,s*1.5f,0);board.dispatchTouchEvent(e);e.recycle();});
            assertNotNull("Unconfirmed action keeps its original target",find(root,TextView.class,"Выстрелить в А1"));
            assertFalse(find(root,TextView.class,"Выстрелить в А1").isEnabled());
            screenshot(runner,context,"battleship-online-retry.png");
            runner.runOnMainSync(retry::performClick);Thread.sleep(500);runner.waitForIdleSync();
            assertEquals(2,actions.size());assertEquals(actions.get(0),actions.get(1));
            assertNotNull(find(root,TextView.class,"Ждём соперника"));
            screenshot(runner,context,"battleship-online-confirmed.png");
        }finally{
            BattleshipFixtureActivity opened=activity;if(opened!=null)runner.runOnMainSync(opened::finish);
            GameOnlineBridge.host=previous;context.getSharedPreferences("family-games-outbox",0).edit().remove("pending:"+scope).commit();
        }
    }
}
