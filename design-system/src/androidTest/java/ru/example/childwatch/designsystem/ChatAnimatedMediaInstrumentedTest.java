package ru.example.childwatch.designsystem;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Animatable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.io.FileOutputStream;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ChatAnimatedMediaInstrumentedTest {
    @Test public void realGifChangesFramesAndStopsWhenHiddenOrRevoked() throws Exception {
        android.app.Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(runner.getTargetContext(), ChatMediaFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ChatMediaFixtureActivity activity = (ChatMediaFixtureActivity) runner.startActivitySync(intent);
        File file = new File(activity.getCacheDir(), "gif-test-" + UUID.randomUUID() + ".gif");
        CountDownLatch loaded = new CountDownLatch(1);
        boolean[] current = {true};
        try {
            try (InputStream input = runner.getContext().getAssets().open("hello-wave.gif")) {
                Files.copy(input, file.toPath());
            }
            runner.runOnMainSync(() -> ChatAnimatedMedia.load(activity.image, file,
                () -> current[0], () -> true, () -> { file.delete(); loaded.countDown(); }));
            assertTrue("native decoder completed", loaded.await(10, TimeUnit.SECONDS));
            runner.runOnMainSync(() -> {
                assertTrue("GIF decoded as animation", activity.image.getDrawable() instanceof Animatable);
                ChatAnimatedMedia.update(activity.image, true);
                assertTrue("visible GIF running", ((Animatable) activity.image.getDrawable()).isRunning());
            });
            Set<Integer> frames = new HashSet<>();
            for (int index=0;index<12;index++) {
                Thread.sleep(110);
                runner.runOnMainSync(() -> {
                    Bitmap bitmap = Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
                    activity.image.draw(new Canvas(bitmap));
                    int[] pixels = new int[128*128]; bitmap.getPixels(pixels,0,128,0,0,128,128);
                    frames.add(java.util.Arrays.hashCode(pixels)); bitmap.recycle();
                });
            }
            assertTrue("rendered pixels changed", frames.size()>1);
            runner.runOnMainSync(() -> {
                ChatAnimatedMedia.update(activity.image,false);
                assertFalse(((Animatable)activity.image.getDrawable()).isRunning());
                current[0]=false; ChatAnimatedMedia.update(activity.image,true);
                assertFalse("revoked owner cannot restart", ((Animatable)activity.image.getDrawable()).isRunning());
                ChatAnimatedMedia.clear(activity.image);
                assertNull(activity.image.getDrawable());
            });
        } finally {
            runner.runOnMainSync(() -> { ChatAnimatedMedia.clear(activity.image); activity.finish(); });
            file.delete();
        }
    }
    @Test public void filesAndEmotionsHaveSeparateNativePanels() throws Exception {
        android.app.Instrumentation runner=InstrumentationRegistry.getInstrumentation();
        ChatMediaFixtureActivity activity=(ChatMediaFixtureActivity)runner.startActivitySync(
            new Intent(runner.getTargetContext(),ChatMediaFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        com.google.android.material.bottomsheet.BottomSheetDialog[] files={null};
        ChatMediaCatalogDialog[] emotions={null};
        File file=new File(activity.getCacheDir(),"panel-gif-"+UUID.randomUUID()+".gif");
        try {
            runner.runOnMainSync(() -> {
                files[0]=ChatAttachmentPickerDialog.show(activity,true,type -> { });
                View decor=files[0].getWindow().getDecorView();
                assertNotNull(find(decor,activity.getString(R.string.chat_media_photo)));
                assertNotNull(find(decor,activity.getString(R.string.chat_media_audio)));
                assertNotNull(find(decor,activity.getString(R.string.chat_media_file)));
                assertNull(find(decor,activity.getString(R.string.chat_catalog_gifs)));
            });
            runner.waitForIdleSync(); Thread.sleep(400); screenshot(runner,activity,"attachment-panel.png");
            try(InputStream input=runner.getContext().getAssets().open("hello-wave.gif")) { Files.copy(input,file.toPath()); }
            runner.runOnMainSync(() -> {
                files[0].dismiss();
                emotions[0]=new ChatMediaCatalogDialog(activity,() -> true,new ChatMediaCatalogDialog.Actions() {
                    public void pick(String id) { } public void retry() { } public void motion(boolean enabled) { } public void closed() { }
                });
                emotions[0].show();
                emotions[0].items(java.util.Arrays.asList(new ChatMediaCatalogDialog.Entry("fixture","Привет","GIF")));
                emotions[0].preview("fixture",file);
            });
            java.lang.reflect.Field field=ChatMediaCatalogDialog.class.getDeclaredField("dialog"); field.setAccessible(true);
            android.app.Dialog sheet=(android.app.Dialog)field.get(emotions[0]);
            runner.runOnMainSync(() -> {
                View decor=sheet.getWindow().getDecorView();
                assertNotNull(find(decor,activity.getString(R.string.chat_catalog_emoji)));
                assertNotNull(find(decor,activity.getString(R.string.chat_catalog_stickers)));
                View gif=find(decor,activity.getString(R.string.chat_catalog_gifs)); assertNotNull(gif); gif.performClick();
            });
            runner.waitForIdleSync(); Thread.sleep(500); screenshot(runner,activity,"emotion-panel.png");
            runner.runOnMainSync(() -> { emotions[0].close(); assertFalse(sheet.isShowing()); });
        } finally {
            runner.runOnMainSync(() -> { if(files[0]!=null)files[0].dismiss(); if(emotions[0]!=null)emotions[0].close(); activity.finish(); });
            file.delete();
        }
    }
    private static View find(View view,String label) {
        if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int index=0;index<((ViewGroup)view).getChildCount();index++) {
            View found=find(((ViewGroup)view).getChildAt(index),label);if(found!=null)return found;
        }
        return null;
    }
    private static void screenshot(android.app.Instrumentation runner,android.app.Activity activity,String name) throws Exception {
        Bitmap bitmap=runner.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        try(FileOutputStream output=new FileOutputStream(new File(activity.getFilesDir(),name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));
        }finally{bitmap.recycle();}
    }
}
