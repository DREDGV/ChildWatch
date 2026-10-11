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
    private static void screenshot(Instrumentation runner,android.app.Activity activity,String name)throws Exception{
        Bitmap image=runner.getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(activity.getFilesDir(),name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
    }
}
