package ru.example.childwatch.designsystem;

import android.util.Log;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Enable with adb shell setprop log.tag.CWGameTiming DEBUG. Durations only. */
final class GameTiming {
    private static final Map<String,long[]> samples=new HashMap<>();
    private static final Map<String,Integer> counts=new HashMap<>();
    static void requestFailed(String method,Exception error) {
        if(!Log.isLoggable("CWGameTiming",Log.DEBUG))return;
        if(error instanceof GameOnlineBridge.Failure){
            GameOnlineBridge.Failure failure=(GameOnlineBridge.Failure)error;
            String code=failure.getMessage();
            Log.d("CWGameTiming","HTTP "+method+" status="+failure.status
                    +(code!=null&&code.matches("GAME_[A-Z_]+")?" code="+code:""));
        }else Log.d("CWGameTiming","HTTP "+method+" transport="+error.getClass().getSimpleName());
    }
    static synchronized void sample(String name,long ms) {
        if(!Log.isLoggable("CWGameTiming",Log.DEBUG)||ms<0)return;
        long[] values=samples.get(name);if(values==null){values=new long[60];samples.put(name,values);}
        int count=counts.containsKey(name)?counts.get(name):0;values[count++]=ms;
        if(count==60){Arrays.sort(values);Log.d("CWGameTiming",name+" count=60 p50="+values[29]+" p95="+values[56]+" max="+values[59]+"ms");count=0;}
        counts.put(name,count);
    }
}
