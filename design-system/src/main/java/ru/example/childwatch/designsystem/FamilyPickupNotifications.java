package ru.example.childwatch.designsystem;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.*;
import java.util.*;

/** Version-based catch-up, scoped to server/family/self; notifications never prove delivery to another person. */
public final class FamilyPickupNotifications {
    private static final String CHANNEL="family_pickups";
    private FamilyPickupNotifications() {}
    public static void deliver(Context context,String scope,JSONObject response,Intent intent,int icon) {
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,context.getString(R.string.pickup_title),NotificationManager.IMPORTANCE_DEFAULT));
        String tag="pickup:"+FamilyPickupController.digest(scope);
        SharedPreferences prefs=context.getSharedPreferences("family_pickup_notifications",Context.MODE_PRIVATE);
        JSONArray rows=response.optJSONArray("requests"); if (rows==null) return;
        Set<String> current=new HashSet<>();
        for(int i=0;i<rows.length();i++) current.add(rows.optJSONObject(i).optString("id"));
        // Remove withdrawn/inaccessible entries, without cancelling another family's notifications.
        if(!response.optBoolean("truncated")) for(String key:prefs.getAll().keySet()) if(key.startsWith(tag+":")) {
            String id=key.substring(tag.length()+1);
            if(!current.contains(id)) { manager.cancel(tag,id.hashCode()); prefs.edit().remove(key).apply(); }
        }
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return;
        if(!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL).getImportance()==NotificationManager.IMPORTANCE_NONE) return;
        String self=response.optJSONObject("actor").optString("memberId");
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i); String id=row.optString("id");
            long version=row.optLong("version"), seen=prefs.getLong(tag+":"+id,0);
            if(version<=seen) continue;
            String status=row.optString("status");
            boolean terminal=Arrays.asList("COMPLETED","CANCELLED","EXPIRED").contains(status);
            JSONArray events=row.optJSONArray("events");
            JSONObject last=events==null||events.length()==0?null:events.optJSONObject(events.length()-1);
            boolean own=last!=null && self.equals(last.optString("actorMemberId")) && !"EXPIRED".equals(status);
            if(own || (seen==0 && terminal)) { prefs.edit().putLong(tag+":"+id,version).commit(); continue; }
            String message=row.optString("childName")+": "+context.getString(FamilyPickupController.statusResource(status));
            Intent open=new Intent(intent).setData(android.net.Uri.parse("childwatch://pickup/"+id))
                .putExtra("open_pickups",true).putExtra("pickup_scope",scope);
            PendingIntent pending=PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            manager.notify(tag,id.hashCode(),new Notification.Builder(context,CHANNEL).setSmallIcon(icon)
                .setContentTitle(context.getString(R.string.pickup_notification_title)).setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message)).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setWhen(row.optLong("updatedAt")).setShowWhen(true).setContentIntent(pending).setAutoCancel(true).build());
            if(!prefs.edit().putLong(tag+":"+id,version).commit()) return;
        }
    }
    public static void clear(Context context,String scope) {
        String tag="pickup:"+FamilyPickupController.digest(scope);
        SharedPreferences prefs=context.getSharedPreferences("family_pickup_notifications",Context.MODE_PRIVATE);
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        for(String key:prefs.getAll().keySet()) if(key.startsWith(tag+":")) {
            manager.cancel(tag,key.substring(tag.length()+1).hashCode()); prefs.edit().remove(key).apply();
        }
    }
}
