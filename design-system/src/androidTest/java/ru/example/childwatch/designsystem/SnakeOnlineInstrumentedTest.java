package ru.example.childwatch.designsystem;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.material.checkbox.MaterialCheckBox;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SnakeOnlineInstrumentedTest {
    @Before public void requiresUnlockedPhone(){
        android.app.KeyguardManager keyguard=(android.app.KeyguardManager)InstrumentationRegistry.getInstrumentation().getTargetContext().getSystemService(android.content.Context.KEYGUARD_SERVICE);
        assertFalse("Owner unlocks the phone",keyguard.isKeyguardLocked());
    }
    @Test public void confirmedFieldControlsPauseAndReconnectAtLargeType() throws Exception {
        Instrumentation runner=InstrumentationRegistry.getInstrumentation();
        SnakeOnlineFixtureActivity.requestedFontScale=1.5f;SnakeOnlineFixtureActivity.requestedPeerName="";
        SnakeOnlineFixtureActivity activity=(SnakeOnlineFixtureActivity)runner.startActivitySync(new Intent(runner.getTargetContext(),SnakeOnlineFixtureActivity.class).putExtra("gameId",UUID.randomUUID().toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            runner.waitForIdleSync();Thread.sleep(250);
            assertEquals("PAUSED",activity.snapshot.getString("phase"));assertEquals(354,activity.field.confirmedHead(1));
            assertTrue("CONNECTED notification retains ready state",activity.findViewById(R.id.snakeOnlineReady).isEnabled());
            runner.runOnMainSync(()->{((MaterialCheckBox)activity.findViewById(R.id.snakeOnlineButtons)).setChecked(true);activity.findViewById(R.id.snakeOnlineReady).performClick();});
            assertEquals("READY",activity.fake.commands.get(0));assertFalse(activity.findViewById(R.id.snakeOnlineReady).isEnabled());
            assertTrue(((TextView)activity.findViewById(R.id.snakeOnlineStatus)).getText().toString().contains("готовности"));
            runner.runOnMainSync(()->activity.fake.emit("COUNTDOWN",0,activity.fake.epoch,true));
            assertFalse(activity.findViewById(R.id.snakeOnlineRight).isEnabled());
            runner.runOnMainSync(()->activity.fake.emit("PLAYING",0,activity.fake.epoch,true));
            runner.waitForIdleSync();Thread.sleep(150);
            for(int id:new int[]{R.id.snakeOnlineLeft,R.id.snakeOnlineUp,R.id.snakeOnlineDown,R.id.snakeOnlineRight}){
                View button=activity.findViewById(id);Rect visible=new Rect();assertTrue(button.getGlobalVisibleRect(visible));assertTrue(button.isEnabled());
                assertTrue(button.getHeight()>=48*button.getResources().getDisplayMetrics().density);
            }
            runner.runOnMainSync(()->activity.findViewById(R.id.snakeOnlineDown).performClick());
            assertEquals(SnakeEngine.Direction.DOWN,activity.fake.turns.get(0));
            Thread.sleep(450);assertEquals("No prediction while waiting for server",354,activity.field.confirmedHead(1));assertEquals("0",activity.field.displayedTick());
            runner.runOnMainSync(()->activity.fake.emit("PLAYING",1,activity.fake.epoch,true));
            assertEquals(374,activity.field.confirmedHead(1));assertEquals("1",activity.field.displayedTick());
            runner.waitForIdleSync();Thread.sleep(200);
            screenshot(runner,activity,"snake-online-playing.png");
            runner.runOnMainSync(()->activity.findViewById(R.id.snakeOnlinePause).performClick());
            assertEquals("PAUSE",activity.fake.commands.get(1));assertFalse(activity.findViewById(R.id.snakeOnlineDown).isEnabled());
            runner.runOnMainSync(()->activity.fake.listener.onStatus("RECONNECTING"));
            assertFalse(activity.findViewById(R.id.snakeOnlineDown).isEnabled());assertEquals(374,activity.field.confirmedHead(1));
            String next=UUID.randomUUID().toString();runner.runOnMainSync(()->activity.fake.emit("PAUSED",1,next,false));
            assertEquals(next,activity.field.displayedEpoch());assertFalse(activity.findViewById(R.id.snakeOnlineDown).isEnabled());
            runner.waitForIdleSync();Thread.sleep(100);
            screenshot(runner,activity,"snake-online-paused.png");
            int before=activity.closed;runner.runOnMainSync(activity::finish);
            long deadline=android.os.SystemClock.elapsedRealtime()+3000;
            java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
            do { runner.waitForIdleSync();runner.runOnMainSync(()->closed.set(activity.closed>before));if(!closed.get())Thread.sleep(30); }
            while(!closed.get()&&android.os.SystemClock.elapsedRealtime()<deadline);
            assertTrue("Leaving the screen closes its game session",closed.get());
        }finally{runner.runOnMainSync(activity::finish);}
    }
    @Test public void expandedDetailsLongNameAndErrorAtTwoHundredPercent() throws Exception {
        Instrumentation runner=InstrumentationRegistry.getInstrumentation();
        SnakeOnlineFixtureActivity.requestedFontScale=2f;
        SnakeOnlineFixtureActivity.requestedPeerName="Александра Екатерина — участник семейной команды с очень длинным именем";
        SnakeOnlineFixtureActivity activity=launch(runner);
        try{
            runner.waitForIdleSync();Thread.sleep(250);
            assertEquals(2f,activity.getResources().getConfiguration().fontScale,0f);
            runner.runOnMainSync(()->activity.findViewById(R.id.snakeOnlineDetails).performClick());
            runner.waitForIdleSync();Thread.sleep(100);
            TextView fullName=activity.findViewById(R.id.snakeOnlinePeer);
            assertTrue(fullName.getText().toString().contains(SnakeOnlineFixtureActivity.requestedPeerName));
            androidx.core.widget.NestedScrollView details=activity.findViewById(R.id.snakeOnlineDetailsPanel);
            assertEquals(View.VISIBLE,details.getVisibility());assertTrue("Long details are scrollable",details.canScrollVertically(1));
            assertFieldHeight(activity);
            runner.runOnMainSync(()->{details.fullScroll(View.FOCUS_DOWN);((MaterialCheckBox)activity.findViewById(R.id.snakeOnlineButtons)).setChecked(true);});
            runner.waitForIdleSync();Thread.sleep(150);
            runner.runOnMainSync(()->details.fullScroll(View.FOCUS_DOWN));runner.waitForIdleSync();Thread.sleep(100);
            assertTarget(activity.findViewById(R.id.snakeOnlineButtons));
            for(int id:new int[]{R.id.snakeOnlineBack,R.id.snakeOnlineDetails,R.id.snakeOnlineReady,R.id.snakeOnlineLeft,R.id.snakeOnlineUp,R.id.snakeOnlineDown,R.id.snakeOnlineRight})assertTarget(activity.findViewById(id));
            screenshot(runner,activity,"snake-online-200-details.png");
            runner.runOnMainSync(()->{activity.findViewById(R.id.snakeOnlineDetails).performClick();activity.fake.emit("PLAYING",0,activity.fake.epoch,true);});
            runner.waitForIdleSync();Thread.sleep(100);
            assertTarget(activity.findViewById(R.id.snakeOnlinePause));assertFieldHeight(activity);
            screenshot(runner,activity,"snake-online-200-playing.png");
            runner.runOnMainSync(()->activity.fake.listener.onStatus("SNAKE_UNSUPPORTED"));
            runner.waitForIdleSync();Thread.sleep(100);
            assertTrue(((TextView)activity.findViewById(R.id.snakeOnlineStatus)).getText().toString().contains("сервер"));
            assertTrue(activity.findViewById(R.id.snakeOnlineStatus).getContentDescription().toString().contains("одиночная"));
            assertFieldHeight(activity);runner.runOnMainSync(()->activity.findViewById(R.id.snakeOnlineDetails).performClick());
            runner.waitForIdleSync();Thread.sleep(100);
            assertTrue(((TextView)activity.findViewById(R.id.snakeOnlineDetailedStatus)).getText().toString().contains("серверный выпуск"));
            assertFieldHeight(activity);screenshot(runner,activity,"snake-online-200-error-details.png");
        }finally{runner.runOnMainSync(activity::finish);SnakeOnlineFixtureActivity.requestedFontScale=1.5f;SnakeOnlineFixtureActivity.requestedPeerName="";}
    }
    @Test public void standardTypeCleanPlayingPreview() throws Exception {
        Instrumentation runner=InstrumentationRegistry.getInstrumentation();SnakeOnlineFixtureActivity.requestedFontScale=1f;SnakeOnlineFixtureActivity.requestedPeerName="Мама";
        SnakeOnlineFixtureActivity activity=launch(runner);
        try{
            runner.waitForIdleSync();runner.runOnMainSync(()->{((MaterialCheckBox)activity.findViewById(R.id.snakeOnlineButtons)).setChecked(false);activity.fake.emit("PLAYING",1,activity.fake.epoch,true);});
            runner.waitForIdleSync();Thread.sleep(200);assertFieldHeight(activity);
            assertEquals(View.GONE,activity.findViewById(R.id.snakeOnlineDetailsPanel).getVisibility());screenshot(runner,activity,"snake-online-100-playing.png");
        }finally{runner.runOnMainSync(activity::finish);SnakeOnlineFixtureActivity.requestedFontScale=1.5f;SnakeOnlineFixtureActivity.requestedPeerName="";}
    }
    private static SnakeOnlineFixtureActivity launch(Instrumentation runner){return (SnakeOnlineFixtureActivity)runner.startActivitySync(new Intent(runner.getTargetContext(),SnakeOnlineFixtureActivity.class).putExtra("gameId",UUID.randomUUID().toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    private static void assertFieldHeight(SnakeOnlineActivity activity){
        assertTrue("Twenty by thirty-six field retains useful height",activity.findViewById(R.id.snakeOnlineField).getHeight()>=235*activity.getResources().getDisplayMetrics().density);
    }
    private static void assertTarget(View view){Rect visible=new Rect();assertTrue("Control remains visible",view.getGlobalVisibleRect(visible));float density=view.getResources().getDisplayMetrics().density;assertTrue("Touch target height at least48dp",view.getHeight()>=48*density);assertTrue("Touch target width at least48dp",view.getWidth()>=48*density);}
    private static void screenshot(Instrumentation runner,android.app.Activity activity,String name)throws Exception{
        Bitmap image=runner.getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(activity.getFilesDir(),name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
    }
}
