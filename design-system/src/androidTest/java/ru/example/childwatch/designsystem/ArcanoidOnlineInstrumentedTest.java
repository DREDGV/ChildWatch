package ru.example.childwatch.designsystem;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.checkbox.MaterialCheckBox;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArcanoidOnlineInstrumentedTest {
    @Before public void ownerUnlocksPhone(){
        android.app.KeyguardManager keyguard=(android.app.KeyguardManager)InstrumentationRegistry.getInstrumentation().getTargetContext().getSystemService(android.content.Context.KEYGUARD_SERVICE);
        assertFalse("Owner unlocks the phone",keyguard.isKeyguardLocked());
    }
    @Test public void mirroredInputOnlyMovesAfterConfirmedSnapshotAndClosesInBackground() throws Exception {
        Instrumentation runner=InstrumentationRegistry.getInstrumentation();
        ArcanoidOnlineFixtureActivity.requestedFontScale=1f;
        ArcanoidOnlineFixtureActivity activity=launch(runner);
        try{
            runner.waitForIdleSync();Thread.sleep(150);
            assertEquals(300,activity.field.confirmedLocalPaddle(),0);
            runner.runOnMainSync(()->{
                ((MaterialCheckBox)activity.findViewById(R.id.arcanoidOnlineButtons)).setChecked(true);
                activity.findViewById(R.id.arcanoidOnlineReady).performClick();
            });
            assertEquals("READY",activity.fake.commands.get(0));
            assertFalse(activity.findViewById(R.id.arcanoidOnlineReady).isEnabled());
            runner.runOnMainSync(()->activity.fake.emit("PLAYING",1,activity.fake.epoch,true,1));
            runner.waitForIdleSync();Thread.sleep(150);
            runner.runOnMainSync(()->activity.findViewById(R.id.arcanoidOnlineRight).performClick());
            assertEquals("Guest right is decreasing server x",.58,activity.fake.moves.get(0),.00001);
            Thread.sleep(120);assertEquals("No optimistic platform",300,activity.field.confirmedLocalPaddle(),0);
            assertEquals("No predicted ball",510,activity.field.confirmedBallX(),0);
            target(activity.findViewById(R.id.arcanoidOnlinePause));field(activity);
            screenshot(runner,activity,"arcanoid-online-100-playing.png");
            runner.runOnMainSync(()->activity.findViewById(R.id.arcanoidOnlinePause).performClick());
            assertEquals("PAUSE",activity.fake.commands.get(1));
            assertFalse(activity.findViewById(R.id.arcanoidOnlineRight).isEnabled());
            String epoch=UUID.randomUUID().toString();
            runner.runOnMainSync(()->activity.fake.emit("PAUSED",1,epoch,false,1));
            assertEquals(epoch,activity.snapshot.epoch);
            runner.runOnMainSync(activity::finish);runner.waitForIdleSync();Thread.sleep(150);
            assertTrue("Leaving closes dedicated connection",activity.closed>0);
        }finally{runner.runOnMainSync(activity::finish);}
    }
    @Test public void twoHundredPercentDetailsRemainScrollableAndFieldUsable() throws Exception {
        Instrumentation runner=InstrumentationRegistry.getInstrumentation();
        ArcanoidOnlineFixtureActivity.requestedFontScale=2f;
        ArcanoidOnlineFixtureActivity.requestedPeerName="Александра Екатерина — участник семейной команды с очень длинным именем";
        ArcanoidOnlineFixtureActivity activity=launch(runner);
        try{
            runner.waitForIdleSync();Thread.sleep(150);
            runner.runOnMainSync(()->activity.findViewById(R.id.arcanoidOnlineDetails).performClick());
            runner.waitForIdleSync();Thread.sleep(100);
            androidx.core.widget.NestedScrollView panel=activity.findViewById(R.id.arcanoidOnlineDetailsPanel);
            assertTrue(panel.canScrollVertically(1));field(activity);
            assertTrue(((TextView)activity.findViewById(R.id.arcanoidOnlinePeer)).getText().toString().contains(ArcanoidOnlineFixtureActivity.requestedPeerName));
            runner.runOnMainSync(()->{panel.fullScroll(View.FOCUS_DOWN);((MaterialCheckBox)activity.findViewById(R.id.arcanoidOnlineButtons)).setChecked(true);});
            runner.waitForIdleSync();Thread.sleep(120);runner.runOnMainSync(()->panel.fullScroll(View.FOCUS_DOWN));runner.waitForIdleSync();
            for(int id:new int[]{R.id.arcanoidOnlineBack,R.id.arcanoidOnlineDetails,R.id.arcanoidOnlineReady,R.id.arcanoidOnlineLeft,R.id.arcanoidOnlineRight,R.id.arcanoidOnlineButtons})target(activity.findViewById(id));
            screenshot(runner,activity,"arcanoid-online-200-details.png");
            runner.runOnMainSync(()->{activity.findViewById(R.id.arcanoidOnlineDetails).performClick();activity.fake.emit("PLAYING",2,activity.fake.epoch,true,1);});
            runner.waitForIdleSync();Thread.sleep(120);field(activity);screenshot(runner,activity,"arcanoid-online-200-playing.png");
            runner.runOnMainSync(()->activity.fake.listener.onStatus("ARKANOID_UNSUPPORTED"));
            assertFalse(activity.findViewById(R.id.arcanoidOnlineRight).isEnabled());
            assertTrue(activity.findViewById(R.id.arcanoidOnlineStatus).getContentDescription().toString().contains("серверный выпуск"));
        }finally{runner.runOnMainSync(activity::finish);ArcanoidOnlineFixtureActivity.requestedFontScale=1f;ArcanoidOnlineFixtureActivity.requestedPeerName="Мама";}
    }
    private static ArcanoidOnlineFixtureActivity launch(Instrumentation runner){return (ArcanoidOnlineFixtureActivity)runner.startActivitySync(new Intent(runner.getTargetContext(),ArcanoidOnlineFixtureActivity.class).putExtra("gameId",UUID.randomUUID().toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    private static void field(ArcanoidOnlineActivity activity){assertTrue("Field keeps useful size",activity.findViewById(R.id.arcanoidOnlineField).getHeight()>=235*activity.getResources().getDisplayMetrics().density);}
    private static void target(View view){Rect visible=new Rect();assertTrue("Control visible",view.getGlobalVisibleRect(visible));float density=view.getResources().getDisplayMetrics().density;assertTrue(view.getHeight()>=48*density);assertTrue(view.getWidth()>=48*density);}
    private static void screenshot(Instrumentation runner,android.app.Activity activity,String name)throws Exception{Bitmap image=runner.getUiAutomation().takeScreenshot();try(FileOutputStream output=new FileOutputStream(new File(activity.getFilesDir(),name))){image.compress(Bitmap.CompressFormat.PNG,100,output);}finally{image.recycle();}}
}
