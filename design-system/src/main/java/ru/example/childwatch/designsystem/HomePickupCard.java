package ru.example.childwatch.designsystem;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.*;

/** Read-only home summary: same authenticated feed as the full pickup screen, never the watched person's identity. */
public final class HomePickupCard {
    public interface Host {
        String scope();
        void fetch(String expectedScope,FamilyPickupController.Callback callback);
        void open(String expectedScope);
    }
    private final Activity activity;
    private final View root,card,details;
    private final TextView title,summary,freshness;
    private final MaterialButton fold,reopen;
    private final Host host;
    private final SharedPreferences prefs;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String scope="";
    private final List<JSONObject> active=new ArrayList<>();
    private boolean resumed,disposed,busy,failed,denied;
    private long checkedAt;
    private int generation;
    private final Runnable poll=new Runnable() {
        @Override public void run() { if (!resumed || disposed) return; refresh(); handler.postDelayed(this,15000); }
    };
    public HomePickupCard(Activity activity,View root,Host host) {
        this.activity=activity; this.root=root; this.host=host;
        card=root.findViewById(R.id.pickupHomeCard); details=root.findViewById(R.id.pickupHomeDetails);
        title=root.findViewById(R.id.pickupHomeTitle); summary=root.findViewById(R.id.pickupHomeSummary);
        freshness=root.findViewById(R.id.pickupHomeFreshness); fold=root.findViewById(R.id.pickupHomeFold); reopen=root.findViewById(R.id.pickupHomeReopen);
        prefs=activity.getSharedPreferences("home_pickup_presentation",Context.MODE_PRIVATE);
        fold.setOnClickListener(v->{ if (!current()) return; prefs.edit().putBoolean(key("fold"),!prefs.getBoolean(key("fold"),false)).apply(); render(); });
        root.findViewById(R.id.pickupHomeHide).setOnClickListener(v->{ if (!current()) return; prefs.edit().putBoolean(key("hide"),true).apply(); render(); });
        reopen.setOnClickListener(v->{ if (!current()) return; prefs.edit().putBoolean(key("hide"),false).putBoolean(key("fold"),false).apply(); render(); });
        root.findViewById(R.id.pickupHomeOpen).setOnClickListener(v->{ if (current()) host.open(scope); });
    }
    private String key(String name) { return name+":"+FamilyPickupController.digest(scope); }
    private boolean current() { return !scope.isEmpty() && scope.equals(host.scope()) && !disposed; }
    public void resume() { resumed=true; handler.removeCallbacks(poll); handler.post(poll); }
    public void pause() { resumed=false; generation++; busy=false; handler.removeCallbacks(poll); }
    public void dispose() { disposed=true; pause(); active.clear(); root.setVisibility(View.GONE); }
    private void refresh() {
        String expected=host.scope();
        if (expected==null) expected="";
        if (!scope.equals(expected)) { generation++; busy=false; active.clear(); checkedAt=0; failed=false; denied=false; scope=expected; render(); }
        if (expected.isEmpty() || busy) return;
        final String requested=expected; final int token=++generation; busy=true;
        host.fetch(requested,(response,error)->handler.post(()->{
            if (disposed || !resumed || token!=generation) return;
            busy=false;
            if (!requested.equals(host.scope())) { active.clear(); root.setVisibility(View.GONE); return; }
            failed=error!=null;
            denied=error!=null && (error.startsWith("PICKUP_HTTP_401") || error.startsWith("PICKUP_HTTP_403") || error.contains("PICKUP_CONTEXT_CHANGED"));
            if (denied) { active.clear(); checkedAt=0; }
            else if (!failed) {
                active.clear(); JSONArray rows=response.optJSONArray("requests");
                if (rows!=null) for(int i=0;i<rows.length();i++) {
                    JSONObject row=rows.optJSONObject(i);
                    if (row!=null && Arrays.asList("REQUESTED","ACCEPTED","EN_ROUTE","ARRIVED","HANDOFF").contains(row.optString("status"))) active.add(row);
                }
                checkedAt=System.currentTimeMillis();
            }
            render();
        }));
    }
    private void render() {
        if (scope.isEmpty() || denied || active.isEmpty()) { root.setVisibility(View.GONE); return; }
        root.setVisibility(View.VISIBLE);
        boolean hidden=prefs.getBoolean(key("hide"),false), folded=prefs.getBoolean(key("fold"),false);
        card.setVisibility(hidden?View.GONE:View.VISIBLE); reopen.setVisibility(hidden?View.VISIBLE:View.GONE);
        String heading=activity.getString(R.string.pickup_badge,active.size());
        title.setText(heading); reopen.setText(heading);
        details.setVisibility(folded?View.GONE:View.VISIBLE);
        fold.setText(folded?R.string.pickup_home_expand:R.string.pickup_home_fold);
        freshness.setText(failed?activity.getString(R.string.pickup_home_stale):activity.getString(R.string.pickup_checked)+" "+DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(checkedAt)));
        StringBuilder text=new StringBuilder();
        for(int i=0;i<Math.min(1,active.size());i++) {
            JSONObject row=active.get(i);
            if (i>0) text.append("\n\n");
            text.append(row.optString("childName")).append(": ").append(activity.getString(FamilyPickupController.statusResource(row.optString("status"))));
            text.append("\n").append(row.isNull("adultMemberId")?activity.getString(R.string.pickup_home_waiting):activity.getString(R.string.pickup_who,row.optString("adultName")));
            text.append("\n").append(row.optString("place"));
        }
        if (active.size()>1) text.append("\n\n").append(activity.getString(R.string.pickup_home_more,active.size()-1));
        summary.setText(text);
    }
}
