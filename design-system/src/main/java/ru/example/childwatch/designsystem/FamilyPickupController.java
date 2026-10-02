package ru.example.childwatch.designsystem;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** Shared UI and durable explicit retry across both apps. Server is the authority for every transition. */
public final class FamilyPickupController {
    public interface Callback { void complete(JSONObject result, String error); }
    public interface Host {
        String scope();
        void request(String method, String suffix, JSONObject body, Callback callback);
        double[] meetingPoint();
        void focusPoint(double latitude,double longitude);
        void badge(int active);
    }
    private final Activity activity;
    private final Host host;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private JSONObject snapshot;
    private String scope = "", error = "";
    private long verifiedAt;
    private boolean busy, visible, disposed;
    private int generation;
    private AlertDialog dialog;
    private LinearLayout content;
    private String detailId;
    private int detailVersion;
    private String detailError="";
    private final java.util.Set<AlertDialog> confirmations=new java.util.HashSet<>();
    private final Runnable poll = new Runnable() {
        @Override public void run() { if (!visible || disposed) return; refresh(); handler.postDelayed(this, 15000); }
    };
    public FamilyPickupController(Activity activity, Host host) {
        this.activity = activity; this.host = host;
        prefs = activity.getSharedPreferences("family_pickup_pending", Context.MODE_PRIVATE);
    }
    private int dp(int n) { return Math.round(n * activity.getResources().getDisplayMetrics().density); }
    private String text(int id) { return activity.getString(id); }
    private String key() { return "operation:" + digest(scope); }
    public static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT,"%02x", b & 255)); return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public void resume() { visible = true; handler.removeCallbacks(poll); handler.post(poll); }
    public void pause() { visible = false; handler.removeCallbacks(poll); close(); }
    public void dispose() { disposed = true; generation++; pause(); }
    public void close() {
        for(AlertDialog d:new java.util.ArrayList<>(confirmations)) d.dismiss(); confirmations.clear();
        if (dialog != null) dialog.dismiss(); dialog = null; content = null;
    }
    private JSONObject pending() {
        try { return new JSONObject(prefs.getString(key(), "")); } catch (Exception e) { return null; }
    }
    public void show() {
        if (host.scope()==null || host.scope().isEmpty()) { Toast.makeText(activity,R.string.pickup_need_family,Toast.LENGTH_LONG).show(); return; }
        refresh(); list();
    }
    private void label(String value) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(16);
        view.setPadding(0,dp(8),0,dp(8)); content.addView(view);
    }
    private MaterialButton button(String value, Runnable action) {
        return button(value,action,false);
    }
    private MaterialButton button(String value, Runnable action, boolean primary) {
        MaterialButton view = new MaterialButton(activity, null, primary ? com.google.android.material.R.attr.materialButtonStyle : com.google.android.material.R.attr.materialButtonOutlinedStyle);
        view.setText(value); view.setAllCaps(false); view.setSingleLine(false); view.setMinHeight(dp(48));
        view.setEnabled(!busy); view.setOnClickListener(v -> action.run()); content.addView(view); return view;
    }
    private void open(String title) {
        close(); content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20),dp(8),dp(20),dp(16));
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        dialog = new MaterialAlertDialogBuilder(activity).setTitle(title).setView(scroll)
            .setNegativeButton(R.string.pickup_close, null).create();
        dialog.setOnDismissListener(d -> { if (dialog == d) { dialog=null; content=null; } });
        dialog.show();
        scroll.getLayoutParams().height = Math.min(dp(520), (int)(activity.getResources().getDisplayMetrics().heightPixels*.65f));
        scroll.requestLayout();
    }
    private void reconcile(JSONObject result) {
        JSONObject p=pending(); if (p==null) return;
        JSONObject body=p.optJSONObject("body"); if (body==null) return;
        String id=body.optString("actionId",body.optString("requestId"));
        JSONArray rows=result.optJSONArray("requests");
        if (rows==null) return;
        for (int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i); if (row==null) continue;
            JSONArray receipts=row.optJSONArray("ownActionIds");
            if (receipts!=null) for (int j=0;j<receipts.length();j++) if (id.equals(receipts.optString(j))) {
                SharedPreferences.Editor saved=prefs.edit().remove(key());
                if (p.optString("suffix").isEmpty()) saved.remove("draft:"+digest(scope));
                saved.commit(); return;
            }
        }
    }
    private void request(String method,String suffix,JSONObject body,boolean write) {
        if (disposed || busy || host.scope()==null || host.scope().isEmpty()) return;
        final String expected=host.scope();
        if (!expected.equals(scope)) { scope=expected; snapshot=null; error=""; generation++; }
        busy=true; final int token=++generation;
        host.request(method,suffix,body,(result,failure)->handler.post(()->{
            if (disposed || token!=generation) return;
            busy=false;
            if (!expected.equals(host.scope())) { snapshot=null; scope=""; close(); host.badge(0); return; }
            error=failure==null ? "" : failure;
            if (failure==null) {
                if (write) {
                    SharedPreferences.Editor saved=prefs.edit().remove(key());
                    if (suffix.isEmpty()) saved.remove("draft:"+digest(scope));
                    saved.commit();
                }
                else { snapshot=result; verifiedAt=System.currentTimeMillis(); reconcile(result); }
            } else if (failure.startsWith("PICKUP_HTTP_401") || failure.startsWith("PICKUP_HTTP_403")) {
                snapshot=null; close(); host.badge(0);
            } else if (write && (failure.startsWith("PICKUP_HTTP_409") || failure.startsWith("PICKUP_HTTP_400") || failure.startsWith("PICKUP_HTTP_429"))) {
                prefs.edit().remove(key()).commit(); // Rejected, not an unknown transport outcome.
            }
            updateBadge();
            boolean showing=dialog!=null && dialog.isShowing();
            if (write) { if (showing) list(); refresh(); }
            else if (showing && !editing) list();
            else if (showing && detailId!=null && confirmations.isEmpty()) {
                JSONObject current=find(detailId);
                if (current==null) list();
                else if (current.optInt("version")!=detailVersion || !error.equals(detailError)) details(current);
                else if (content!=null) for(int i=0;i<content.getChildCount();i++) {
                    View v=content.getChildAt(i); if(v instanceof MaterialButton) v.setEnabled(true);
                }
            }
        }));
    }
    private boolean editing;
    public void refresh() {
        String now=host.scope();
        if (now==null || now.isEmpty()) { snapshot=null; scope=""; host.badge(0); close(); return; }
        if (!now.equals(scope)) { generation++; busy=false; snapshot=null; scope=now; close(); }
        request("GET","",null,false);
    }
    private void updateBadge() {
        int count=0; JSONArray rows=snapshot==null?null:snapshot.optJSONArray("requests");
        if (rows!=null) for (int i=0;i<rows.length();i++) if (active(rows.optJSONObject(i))) count++;
        host.badge(count);
    }
    private boolean active(JSONObject row) {
        if (row==null) return false;
        String status=row.optString("status");
        return java.util.Arrays.asList("REQUESTED","ACCEPTED","EN_ROUTE","ARRIVED","HANDOFF").contains(status);
    }
    private JSONObject find(String id) {
        JSONArray rows=snapshot==null?null:snapshot.optJSONArray("requests");
        if (rows!=null) for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i); if(row!=null && id.equals(row.optString("id"))) return row;
        }
        return null;
    }
    private String self() { return snapshot==null?"":snapshot.optJSONObject("actor").optString("memberId"); }
    private boolean child() { return snapshot!=null && "CHILD".equals(snapshot.optJSONObject("actor").optString("role")); }
    public static int statusResource(String status) {
        switch(status) {
            case "REQUESTED": return R.string.pickup_requested;
            case "ACCEPTED": return R.string.pickup_accepted;
            case "EN_ROUTE": return R.string.pickup_en_route;
            case "ARRIVED": return R.string.pickup_arrived;
            case "HANDOFF": return R.string.pickup_handoff;
            case "COMPLETED": return R.string.pickup_completed;
            case "CANCELLED": return R.string.pickup_cancelled;
            default: return R.string.pickup_expired;
        }
    }
    private String message(String code) {
        if (code.contains("409")) return text(R.string.pickup_conflict);
        if (code.contains("401") || code.contains("403")) return text(R.string.pickup_denied);
        if (code.contains("404")) return text(R.string.pickup_server_old);
        if (code.contains("400") || code.contains("429")) return text(R.string.pickup_rejected);
        return text(R.string.pickup_network_error);
    }
    private void list() {
        editing=false; detailId=null; open(text(R.string.pickup_title));
        if (busy) label(text(R.string.pickup_loading));
        if (!error.isEmpty()) label(message(error));
        if (snapshot!=null) label(text(R.string.pickup_checked)+" "+DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(verifiedAt)));
        JSONObject p=pending();
        if (p!=null) {
            label(text(R.string.pickup_pending));
            button(text(R.string.pickup_retry_send),()-> request("POST",p.optString("suffix"),p.optJSONObject("body"),true));
            button(text(R.string.pickup_forget),()-> confirm(text(R.string.pickup_forget_hint),()->{prefs.edit().remove(key()).commit(); refresh(); list();}));
        }
        JSONArray rows=snapshot==null?null:snapshot.optJSONArray("requests");
        boolean ownActive=false;
        if (rows==null) label(text(R.string.pickup_unverified));
        else {
            if (rows.length()==0) label(text(R.string.pickup_empty));
            for (int i=0;i<rows.length();i++) {
                JSONObject row=rows.optJSONObject(i); if (row==null) continue;
                if (active(row) && self().equals(row.optString("childMemberId"))) ownActive=true;
                button(row.optString("childName")+" · "+text(statusResource(row.optString("status")))+"\n"+row.optString("place"),()->details(row));
            }
            if (snapshot.optBoolean("truncated")) label(text(R.string.pickup_truncated));
        }
        if (child() && !ownActive && p==null) button(text(R.string.pickup_create),this::create,true);
        button(text(R.string.pickup_refresh),this::refresh);
        button(text(R.string.pickup_notification_settings),()->activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.getPackageName())));
        label(text(R.string.pickup_delivery_hint));
    }
    private void confirm(String message,Runnable action) {
        AlertDialog confirmation=new MaterialAlertDialogBuilder(activity).setMessage(message).setNegativeButton(R.string.pickup_back,null)
            .setPositiveButton(R.string.pickup_confirm,(d,w)->action.run()).create();
        confirmations.add(confirmation); confirmation.setOnDismissListener(d->confirmations.remove(confirmation)); confirmation.show();
    }
    private void details(JSONObject row) {
        final String renderedScope=scope;
        detailVersion=row.optInt("version"); detailError=error;
        editing=true; detailId=row.optString("id"); open(row.optString("childName"));
        if (!error.isEmpty()) label(message(error));
        label(text(statusResource(row.optString("status")))); label(row.optString("place"));
        if (!row.optString("note").isEmpty()) label(row.optString("note"));
        label(text(R.string.pickup_manual_point)+"\n"+String.format(Locale.getDefault(),"%.5f, %.5f",row.optDouble("latitude"),row.optDouble("longitude")));
        button(text(R.string.pickup_show_point),()->{host.focusPoint(row.optDouble("latitude"),row.optDouble("longitude")); close();});
        label(text(R.string.pickup_updated)+" "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(row.optLong("updatedAt"))));
        if (verifiedAt>0) label(text(R.string.pickup_checked)+" "+DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(verifiedAt)));
        if (active(row)) label(text(R.string.pickup_deadline)+" "+DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(row.optLong("expiresAt"))));
        boolean mine=self().equals(row.optString("adultMemberId"));
        boolean ownChild=self().equals(row.optString("childMemberId"));
        if (!row.isNull("adultMemberId")) label(activity.getString(R.string.pickup_who,row.optString("adultName")));
        button(text(R.string.pickup_route),()->{
            Uri uri=Uri.parse("geo:"+row.optDouble("latitude")+","+row.optDouble("longitude")+"?q="+row.optDouble("latitude")+","+row.optDouble("longitude")+"("+Uri.encode(row.optString("place"))+")");
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW,uri)); }
            catch (android.content.ActivityNotFoundException e) { Toast.makeText(activity,R.string.pickup_no_maps,Toast.LENGTH_LONG).show(); }
        });
        if (pending()==null && active(row)) {
            String status=row.optString("status");
            if (!child() && "REQUESTED".equals(status)) actionButton(row,"ACCEPT",R.string.pickup_accept);
            if (mine && "ACCEPTED".equals(status)) actionButton(row,"DEPART",R.string.pickup_depart);
            if (mine && ("ACCEPTED".equals(status) || "EN_ROUTE".equals(status))) actionButton(row,"ARRIVE",R.string.pickup_arrive);
            if ((mine || ownChild) && ("ARRIVED".equals(status) || "HANDOFF".equals(status))) {
                boolean confirmed=ownChild?row.optBoolean("childConfirmed"):row.optBoolean("adultConfirmed");
                if (!confirmed) button(text(R.string.pickup_met),()->confirm(text(R.string.pickup_met_hint),()->sendAction(row,"CONFIRM",renderedScope)),true);
                else label(text(R.string.pickup_wait_confirmation));
            }
            if (mine && !"HANDOFF".equals(status)) button(text(R.string.pickup_release),()->confirm(text(R.string.pickup_release_hint),()->sendAction(row,"RELEASE",renderedScope)));
            if (mine || ownChild) button(text(R.string.pickup_cancel),()->confirm(text(R.string.pickup_cancel_hint),()->sendAction(row,"CANCEL",renderedScope)));
        }
        button(text(R.string.pickup_all),this::list);
        button(text(R.string.pickup_refresh),()->{editing=false; refresh();});
    }
    private void actionButton(JSONObject row,String action,int title) {
        final String renderedScope=scope; button(text(title),()->sendAction(row,action,renderedScope),true);
    }
    private void sendAction(JSONObject row,String action,String renderedScope) {
        if (!renderedScope.equals(host.scope())) { close(); refresh(); return; }
        try { send("/"+row.getString("id")+"/actions",new JSONObject().put("actionId",UUID.randomUUID().toString()).put("action",action).put("version",row.getInt("version"))); }
        catch(Exception e) { error="PICKUP_UNAVAILABLE"; list(); }
    }
    private void send(String suffix,JSONObject body) {
        if (!scope.equals(host.scope())) { close(); refresh(); return; }
        if (busy) { Toast.makeText(activity,R.string.pickup_loading,Toast.LENGTH_SHORT).show(); return; }
        if (pending()!=null) return;
        try {
            JSONObject envelope=new JSONObject().put("suffix",suffix).put("body",body);
            if (!prefs.edit().putString(key(),envelope.toString()).commit()) { error="PICKUP_UNAVAILABLE"; list(); return; }
            request("POST",suffix,body,true); list();
        } catch(Exception e) { error="PICKUP_UNAVAILABLE"; list(); }
    }
    private TextInputEditText input(int hint,int max) {
        TextInputLayout box=new TextInputLayout(activity); box.setHint(text(hint));
        TextInputEditText field=new TextInputEditText(activity); field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.addView(field); content.addView(box); return field;
    }
    private void create() {
        double[] selected=host.meetingPoint(); if (selected==null || selected.length!=2) return;
        JSONObject draft;
        try { draft=new JSONObject(prefs.getString("draft:"+digest(scope),"")); } catch(Exception e) { draft=new JSONObject(); }
        final double[] point=draft.has("latitude") && draft.has("longitude") ?
            new double[]{draft.optDouble("latitude"),draft.optDouble("longitude")} : selected;
        final String expected=scope; editing=true; detailId=null; open(text(R.string.pickup_create));
        label(text(R.string.pickup_point_hint));
        label(String.format(Locale.getDefault(),"%.5f, %.5f",point[0],point[1]));
        button(text(R.string.pickup_show_point),()->{host.focusPoint(point[0],point[1]); close();});
        button(text(R.string.pickup_choose_map),()->{
            try {
                JSONObject saved=new JSONObject(prefs.getString("draft:"+digest(scope),"{}"));
                saved.remove("latitude"); saved.remove("longitude"); prefs.edit().putString("draft:"+digest(scope),saved.toString()).commit();
            } catch(Exception ignored) { }
            editing=false; close(); Toast.makeText(activity,R.string.pickup_pan_hint,Toast.LENGTH_LONG).show();
        });
        TextInputEditText place=input(R.string.pickup_place,120), note=input(R.string.pickup_note,280);
        place.setText(draft.optString("place")); note.setText(draft.optString("note"));
        android.text.TextWatcher saveDraft=new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {
                if (!expected.equals(host.scope())) return;
                try { prefs.edit().putString("draft:"+digest(expected),new JSONObject().put("latitude",point[0]).put("longitude",point[1])
                    .put("place",place.getText()==null?"":place.getText().toString()).put("note",note.getText()==null?"":note.getText().toString()).toString()).apply(); }
                catch(Exception ignored) { }
            }
            @Override public void afterTextChanged(android.text.Editable e) { }
        };
        place.addTextChangedListener(saveDraft); note.addTextChangedListener(saveDraft);
        CheckBox check=new CheckBox(activity); check.setText(R.string.pickup_point_confirm); check.setMinHeight(dp(48)); content.addView(check);
        label(text(R.string.pickup_not_sos));
        button(text(R.string.pickup_send),()->{
            if (!expected.equals(host.scope())) { close(); refresh(); return; }
            if (!check.isChecked() || place.getText()==null || place.getText().toString().trim().isEmpty()) {
                Toast.makeText(activity,R.string.pickup_need_point,Toast.LENGTH_LONG).show(); return;
            }
            try { send("",new JSONObject().put("requestId",UUID.randomUUID().toString()).put("latitude",point[0]).put("longitude",point[1])
                .put("place",place.getText().toString()).put("note",note.getText()==null?"":note.getText().toString()).put("pointConfirmed",true)); }
            catch(Exception e) { error="PICKUP_UNAVAILABLE"; list(); }
        },true);
    }
}
