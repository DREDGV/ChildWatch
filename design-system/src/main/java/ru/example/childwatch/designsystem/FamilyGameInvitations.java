package ru.example.childwatch.designsystem;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import androidx.core.app.NotificationCompat;
import com.google.android.material.snackbar.Snackbar;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** No background poll or extra socket: foreground refresh plus existing transport invalidation. */
public final class FamilyGameInvitations implements Application.ActivityLifecycleCallbacks {
    public interface Provider { GameOnlineBridge.Host create(); }
    private static FamilyGameInvitations instance;
    private static final String CHANNEL="family-game-invitations",TAG="family-game-invitation";
    private final Application app;
    private final Provider provider;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final NotificationManager notifications;
    private final Set<String> announced=new HashSet<>(),dismissed=new HashSet<>();
    private List<GameInvitationInbox.Invitation> invitations=new ArrayList<>();
    private Activity activity;
    private Snackbar banner;
    private Runnable bannerExpiry;
    private String scope="",bannerId="";
    private GameOnlineBridge.Client client;
    private boolean busy,queued;
    private long generation,lastFetch;
    private final Runnable poll=()->refresh(false);
    private FamilyGameInvitations(Application app,Provider provider) {
        this.app=app;this.provider=provider;
        notifications=(NotificationManager)app.getSystemService(Context.NOTIFICATION_SERVICE);
        if(notifications!=null)notifications.createNotificationChannel(new NotificationChannel(
            CHANNEL,app.getString(R.string.cw_game_invitation_channel),NotificationManager.IMPORTANCE_DEFAULT));
        app.registerActivityLifecycleCallbacks(this);
    }
    public static void install(Application app,Provider provider) {
        if(instance==null)instance=new FamilyGameInvitations(app,provider);
    }
    /** Wakeup contains no trusted name/state. Always re-authorize through HTTP. */
    public static void changed() { if(instance!=null)instance.main.post(()->instance.refresh(true)); }
    private GameOnlineBridge.Host host() {
        try { return provider.create(); } catch(RuntimeException invalid) { return null; }
    }
    private void reset(String next,GameOnlineBridge.Host host) {
        generation++;if(client!=null)client.close();client=null;busy=false;queued=false;
        hide();for(GameInvitationInbox.Invitation invite:invitations)cancel(invite.id);
        if(notifications!=null)for(android.service.notification.StatusBarNotification posted:notifications.getActiveNotifications())
            if(posted.getTag()!=null&&posted.getTag().startsWith(TAG))notifications.cancel(posted.getTag(),posted.getId());
        invitations=new ArrayList<>();announced.clear();dismissed.clear();scope=next;lastFetch=0;
        if(host!=null&&!scope.isEmpty()) { GameOnlineBridge.host=host;client=new GameOnlineBridge.Client(app); }
    }
    private void refresh(boolean event) {
        main.removeCallbacks(poll);
        GameOnlineBridge.Host fresh=host();String next=fresh==null?"":fresh.scopeKey();
        if(!next.equals(scope))reset(next,fresh);
        if(client==null||scope.isEmpty())return;
        show();
        if(busy){queued|=event;return;}
        long now=SystemClock.elapsedRealtime();
        if(lastFetch!=0&&now-lastFetch<2000) {
            if(activity!=null||event)main.postDelayed(poll,2000-(now-lastFetch));return;
        }
        busy=true;lastFetch=now;long attempt=generation;String captured=scope;
        client.read("/",(json,error)->{
            if(attempt!=generation)return;busy=false;
            GameOnlineBridge.Host current=host();
            if(current==null||!captured.equals(current.scopeKey())){reset("",null);return;}
            if(error==null) {
                List<GameInvitationInbox.Invitation> updated=GameInvitationInbox.parse(json,captured,SystemClock.elapsedRealtime());
                Set<String> ids=new HashSet<>();for(GameInvitationInbox.Invitation i:updated)ids.add(i.id);
                for(GameInvitationInbox.Invitation old:invitations)if(!ids.contains(old.id))cancel(old.id);
                invitations=updated;
                for(GameInvitationInbox.Invitation i:invitations)announce(i);
                show();
            } else if(error instanceof GameOnlineBridge.Failure &&
                (((GameOnlineBridge.Failure)error).status==401||((GameOnlineBridge.Failure)error).status==403)) {
                reset("",null);
            } else { hide(); }
            if(queued){queued=false;main.postDelayed(()->refresh(true),2000);}
            else if(activity!=null)main.postDelayed(poll,15_000);
        });
    }
    private void announce(GameInvitationInbox.Invitation i) {
        if(announced.contains(i.id)||notifications==null||!notifications.areNotificationsEnabled())return;
        String key=scope+":"+i.id;
        android.content.SharedPreferences prefs=app.getSharedPreferences("game-invite-announced",Context.MODE_PRIVATE);
        boolean known=prefs.contains(key);
        Intent intent=entry(app,scope,i);
        PendingIntent pending=PendingIntent.getActivity(app,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        try {
            notifications.notify(TAG+i.id,0,new NotificationCompat.Builder(app,CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_myplaces)
                .setContentTitle(app.getString(R.string.cw_game_invitation_title,i.name))
                .setContentText(app.getString(R.string.cw_game_invitation_body,gameName(i.type)))
                .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                .setSilent(activity!=null||known).setTimeoutAfter(Math.max(1,i.deadline-SystemClock.elapsedRealtime()))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build());
            announced.add(i.id);
            // Store IDs only, cap retained dedup entries; names never go into preferences.
            android.content.SharedPreferences.Editor edit=prefs.edit();
            if(prefs.getAll().size()>=128)edit.clear();edit.putBoolean(key,true).apply();
        } catch(SecurityException denied) { /* Foreground banner still works without notification permission. */ }
    }
    private String gameName(String type) { return app.getString("SNAKE_COOP".equals(type)?R.string.cw_catalog_snake_title:
        "ARKANOID_COOP".equals(type)?R.string.cw_arcanoid_title:R.string.cw_catalog_battleship_title); }
    private void cancel(String id) { if(notifications!=null)notifications.cancel(TAG+id,0); }
    private void hide() { if(bannerExpiry!=null){main.removeCallbacks(bannerExpiry);bannerExpiry=null;}
        if(banner!=null){banner.dismiss();banner=null;}bannerId=""; }
    private void show() {
        if(activity==null||activity.isFinishing()||!(activity instanceof androidx.appcompat.app.AppCompatActivity))return;
        GameInvitationInbox.Invitation selected=null;
        for(GameInvitationInbox.Invitation i:invitations)
            if(i.deadline>SystemClock.elapsedRealtime()&&!dismissed.contains(i.id)){selected=i;break;}
        if(selected==null){hide();return;}
        if(selected.id.equals(bannerId)&&banner!=null&&banner.isShownOrQueued())return;
        hide();GameInvitationInbox.Invitation invitation=selected;Activity owner=activity;String captured=scope;
        View root=owner.findViewById(android.R.id.content);if(root==null)return;
        bannerId=invitation.id;
        banner=Snackbar.make(root,app.getString(R.string.cw_game_invitation_banner,invitation.name,gameName(invitation.type)),Snackbar.LENGTH_INDEFINITE);
        Snackbar current=banner;
        android.view.ViewGroup container=(android.view.ViewGroup)current.getView();
        container.setBackgroundTintList(android.content.res.ColorStateList.valueOf(owner.getColor(R.color.cw_color_inverse_surface)));
        container.removeAllViews();
        View content=owner.getLayoutInflater().inflate(R.layout.game_invitation_banner,container,false);
        ((android.widget.TextView)content.findViewById(R.id.gameInviteMessage)).setText(
            app.getString(R.string.cw_game_invitation_banner,invitation.name,gameName(invitation.type)));
        content.findViewById(R.id.gameInviteLater).setOnClickListener(v->{dismissed.add(invitation.id);hide();});
        content.findViewById(R.id.gameInviteOpen).setOnClickListener(v->{dismissed.add(invitation.id);hide();owner.startActivity(entry(owner,captured,invitation));});
        if(owner.getResources().getConfiguration().fontScale>=1.5f||owner.getResources().getConfiguration().screenWidthDp<360)
            ((android.widget.LinearLayout)content.findViewById(R.id.gameInviteActions)).setOrientation(android.widget.LinearLayout.VERTICAL);
        container.addView(content);
        current.addCallback(new Snackbar.Callback(){@Override public void onDismissed(Snackbar b,int event){
            if(event==DISMISS_EVENT_SWIPE)dismissed.add(invitation.id);
        }});
        current.show();
        bannerExpiry=()->{if(current==banner&&invitation.deadline<=SystemClock.elapsedRealtime()){cancel(invitation.id);hide();}};
        main.postDelayed(bannerExpiry,Math.max(1,invitation.deadline-SystemClock.elapsedRealtime()));
    }
    private static Intent entry(Context context,String scope,GameInvitationInbox.Invitation invitation) {
        return new Intent(context,GameInvitationEntryActivity.class).setData(android.net.Uri.parse("childwatch-game:invite/"+invitation.id))
            .putExtra("scope",scope).putExtra("gameId",invitation.id).putExtra("gameType",invitation.type);
    }
    static boolean open(Activity activity,Intent intent) {
        if(instance==null)return false;
        GameOnlineBridge.Host host=instance.host();String scope=intent.getStringExtra("scope"),type=intent.getStringExtra("gameType");
        if(host==null||scope==null||scope.isEmpty()||!scope.equals(host.scopeKey())||!GameInvitationInbox.supported(type))return false;
        String id=intent.getStringExtra("gameId");
        if(id==null||!id.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))return false;
        instance.dismissed.add(id);instance.cancel(id);instance.hide();
        GameOnlineBridge.host=host;
        activity.startActivity(new Intent(activity,FamilyGameLobbyActivity.class).putExtra(FamilyGameLobbyActivity.EXTRA_GAME_TYPE,type)
            .putExtra("invitationId",intent.getStringExtra("gameId")));
        return true;
    }
    @Override public void onActivityResumed(Activity a) { activity=a;refresh(false); }
    @Override public void onActivityPaused(Activity a) { if(activity==a){activity=null;hide();main.removeCallbacks(poll);} }
    @Override public void onActivityCreated(Activity a,Bundle b) {}
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a,Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) { if(activity==a){activity=null;hide();} }
}
