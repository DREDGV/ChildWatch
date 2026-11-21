package ru.example.childwatch.designsystem;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GamesV2InstrumentedTest {
    @Test public void lobbyConfirmsDurableDeleteAndClearsCardAtTwoHundredPercent()throws Exception{
        Instrumentation runner=InstrumentationRegistry.getInstrumentation();
        GameOnlineBridge.Host previous=GameOnlineBridge.host;
        java.util.concurrent.atomic.AtomicReference<org.json.JSONObject> deletion=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean delayNextRead=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CountDownLatch readStarted=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseRead=new java.util.concurrent.CountDownLatch(1);
        String id=UUID.randomUUID().toString();
        org.json.JSONObject peer=new org.json.JSONObject().put("memberId","guest").put("displayName","Близкий").put("avatarKey","preset:sky");
        org.json.JSONObject game=new org.json.JSONObject().put("id",id).put("type","SNAKE_COOP").put("status","SETUP").put("myMark","X").put("version",1).put("guest",peer);
        GameOnlineBridge.host=new GameOnlineBridge.Host(){
            public String scopeKey(){return "isolated-lobby-v2";}
            public org.json.JSONObject request(String method,String path,org.json.JSONObject body)throws Exception{
                if("DELETE".equals(method)){deletion.set(new org.json.JSONObject(body.toString()));return new org.json.JSONObject().put("success",true).put("game",new org.json.JSONObject(game.toString()).put("status","CANCELLED"));}
                org.json.JSONObject result=new org.json.JSONObject().put("success",true);
                if("/capabilities".equals(path))return result.put("games",new org.json.JSONArray().put("SNAKE_COOP"));
                if("/members".equals(path))return result.put("actorMemberId","host").put("members",new org.json.JSONArray().put(peer));
                result.put("games",deletion.get()==null?new org.json.JSONArray().put(game):new org.json.JSONArray());
                if(delayNextRead.getAndSet(false)){
                    readStarted.countDown();
                    if(!releaseRead.await(8,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("READ_TIMEOUT");
                }
                return result;
            }
        };
        FamilyGameLobbyFixtureActivity activity=(FamilyGameLobbyFixtureActivity)runner.startActivitySync(new Intent(runner.getTargetContext(),FamilyGameLobbyFixtureActivity.class).putExtra("gameType","SNAKE_COOP").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            long deadline=android.os.SystemClock.elapsedRealtime()+5000;
            while(find(activity.getWindow().getDecorView(),"Завершить и убрать")==null&&android.os.SystemClock.elapsedRealtime()<deadline){runner.waitForIdleSync();Thread.sleep(50);}
            View remove=find(activity.getWindow().getDecorView(),"Завершить и убрать");assertNotNull(remove);
            screenshot(runner,activity,"games-v2-lobby-200.png");
            delayNextRead.set(true);
            View refresh=find(activity.getWindow().getDecorView(),"Обновить партии");
            assertNotNull(refresh);
            runner.runOnMainSync(refresh::performClick);
            assertTrue("Lobby refresh is in flight",readStarted.await(3,java.util.concurrent.TimeUnit.SECONDS));
            runner.runOnMainSync(remove::performClick);runner.waitForIdleSync();
            android.view.accessibility.AccessibilityNodeInfo root=runner.getUiAutomation().getRootInActiveWindow();assertNotNull(root);
            assertEquals(runner.getTargetContext().getPackageName(),root.getPackageName().toString());
            boolean clicked=false;for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("Завершить и убрать"))
                if(node.isClickable())clicked|=node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            assertTrue(clicked);
            deadline=android.os.SystemClock.elapsedRealtime()+5000;
            while(deletion.get()==null&&android.os.SystemClock.elapsedRealtime()<deadline){runner.waitForIdleSync();Thread.sleep(50);}
            assertNotNull(deletion.get());assertTrue(deletion.get().getBoolean("confirmActive"));assertEquals(1,deletion.get().getInt("version"));assertNotNull(UUID.fromString(deletion.get().getString("operationId")));
            Thread.sleep(150);runner.waitForIdleSync();
            assertNull("Confirmed closure clears the card before polling completes",find(activity.getWindow().getDecorView(),"Завершить и убрать"));
            releaseRead.countDown();Thread.sleep(250);runner.waitForIdleSync();
            assertNull("Late refresh cannot restore the closed card",find(activity.getWindow().getDecorView(),"Завершить и убрать"));
        }finally{releaseRead.countDown();runner.runOnMainSync(activity::finish);GameOnlineBridge.host=previous;}
    }
    @Test public void resultAndExternalClosureAreUsableAtTwoHundredPercent()throws Exception{
        Instrumentation runner=InstrumentationRegistry.getInstrumentation();
        SnakeOnlineFixtureActivity.requestedFontScale=2f;
        SnakeOnlineFixtureActivity activity=(SnakeOnlineFixtureActivity)runner.startActivitySync(new Intent(runner.getTargetContext(),SnakeOnlineFixtureActivity.class).putExtra("gameId",UUID.randomUUID().toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            runner.waitForIdleSync();runner.runOnMainSync(()->activity.fake.listener.onStatus("GAME_CLOSED"));
            runner.waitForIdleSync();Thread.sleep(150);
            assertTrue(((TextView)activity.findViewById(R.id.snakeOnlineStatus)).getText().toString().contains("завершена"));
            View again=find(activity.getWindow().getDecorView(),"Сыграть ещё"),back=find(activity.getWindow().getDecorView(),"К играм");
            assertNotNull(again);assertNotNull(back);assertTrue(again.isShown());assertTrue(back.isShown());
            assertTrue(again.getHeight()>=48*activity.getResources().getDisplayMetrics().density);
            runner.runOnMainSync(()->activity.fake.listener.onStatus("DISCONNECTED"));
            assertTrue(((TextView)activity.findViewById(R.id.snakeOnlineStatus)).getText().toString().contains("завершена"));
            Bitmap image=runner.getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(activity.getFilesDir(),"games-v2-result-200.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
        }finally{runner.runOnMainSync(activity::finish);SnakeOnlineFixtureActivity.requestedFontScale=1.5f;}
    }
    private static View find(View view,String text){
        if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}
        return null;
    }
    private static void screenshot(Instrumentation runner,android.app.Activity activity,String name)throws Exception{
        Bitmap image=runner.getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(activity.getFilesDir(),name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
    }
}
