package ru.example.childwatch.designsystem;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Nonmodal history controls: the map remains interactive behind this view. */
public final class MapHistoryPanel {
    public interface Listener {
        void onPoint(MapRouteSegments.Fix point);
        void onLive();
        void onRetry();
        void onEvents();
    }
    private final Context context;
    private final ViewGroup root;
    private final LinearLayout panel, details, events;
    private final TextView status, time;
    private MaterialButton reopen;
    private final MaterialButton retry;
    private MaterialButton fold;
    private final SeekBar slider;
    private final Listener listener;
    private final long from, to;
    private final SimpleDateFormat format = new SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault());
    private List<MapRouteSegments.Fix> points = new ArrayList<>();
    private boolean complete, folded;
    private Long selectedTime;
    private int eventLimit = 100;

    public MapHistoryPanel(Context context, ViewGroup root, String person, long from, long to, Listener listener) {
        this.context = context; this.root = root; this.from = from; this.to = to; this.listener = listener;
        details = column();
        panel = column(); panel.setPadding(dp(12), dp(8), dp(12), dp(8));
        panel.setBackgroundColor(ContextCompat.getColor(context, R.color.cw_color_surface));
        panel.setElevation(dp(8));
        LinearLayout header = new LinearLayout(context); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(person + "\n" + format.format(new Date(from)) + "\n" + java.util.TimeZone.getDefault().getID(), 16);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        fold = button(R.string.cw_history_fold, () -> {
            folded = !folded; details.setVisibility(folded ? View.GONE : View.VISIBLE);
            fold.setText(folded ? R.string.cw_history_expand : R.string.cw_history_fold);
        });
        header.addView(fold); header.addView(button(R.string.cw_history_hide, this::hide)); panel.addView(header);
        time = text("", 18); panel.addView(time);
        status = text(context.getString(R.string.cw_history_loading), 13); panel.addView(status);
        panel.addView(details);
        slider = new SeekBar(context); slider.setMax(10000);
        slider.setContentDescription(context.getString(R.string.cw_history_time)); details.addView(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean user) { if (user) select(from + (to - from) * progress / 10000); }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        ScrollView scroll = new ScrollView(context); events = column(); scroll.addView(events);
        details.addView(scroll, new LinearLayout.LayoutParams(-1, dp(110)));
        LinearLayout actions = new LinearLayout(context);
        actions.addView(button(R.string.cw_history_now, listener::onLive));
        actions.addView(button(R.string.cw_history_events, listener::onEvents));
        retry = button(R.string.cw_history_retry, listener::onRetry); retry.setVisibility(View.GONE); actions.addView(retry);
        panel.addView(actions);
        reopen = button(R.string.cw_history_reopen, () -> { reopen.setVisibility(View.GONE); panel.setVisibility(View.VISIBLE); });
        reopen.setVisibility(View.GONE);
        root.addView(panel, bottom()); root.addView(reopen, bottom());
    }
    public boolean isVisible() { return panel.getVisibility() == View.VISIBLE; }
    public void hide() { panel.setVisibility(View.GONE); reopen.setVisibility(View.VISIBLE); }
    public void dispose() { root.removeView(panel); root.removeView(reopen); }
    public void limited(boolean legacy) {
        status.setText(legacy ? R.string.cw_history_limited : R.string.cw_history_size_limit);
        retry.setVisibility(View.GONE);
    }
    public void update(List<MapRouteSegments.Fix> fixes, boolean finished) {
        points = new ArrayList<>(fixes); complete = finished;
        status.setText(context.getString(finished ? R.string.cw_history_complete : R.string.cw_history_partial, points.size()));
        retry.setVisibility(View.GONE); buildEvents();
        if (selectedTime == null && !points.isEmpty()) {
            long first = points.get(0).timestampMs;
            slider.setProgress((int) ((first - from) * 10000 / Math.max(1, to - from)));
            select(first);
        } else if (selectedTime != null) select(selectedTime);
        if (finished && points.isEmpty()) status.setText(R.string.cw_history_empty);
    }
    public void error(boolean denied, boolean changed) {
        if (denied || changed) { points.clear(); events.removeAllViews(); time.setText(""); listener.onPoint(null); }
        status.setText(denied ? R.string.cw_history_denied : changed ? R.string.cw_history_changed : points.isEmpty() ? R.string.cw_history_unavailable : R.string.cw_history_partial_error);
        retry.setVisibility(denied ? View.GONE : View.VISIBLE);
    }
    private void select(long selected) {
        selectedTime = selected;
        int left = -1, right = points.size();
        while (right - left > 1) { int middle = (left + right) / 2;
            if (points.get(middle).timestampMs <= selected) left = middle; else right = middle; }
        MapRouteSegments.Fix chosen = null;
        if (left >= 0 && points.get(left).timestampMs == selected) chosen = points.get(left);
        else if (left >= 0 && right < points.size()) {
            List<MapRouteSegments.Fix> pair = java.util.Arrays.asList(points.get(left), points.get(right));
            if (MapRouteSegments.split(pair).stream().anyMatch(segment -> segment.size() == 2))
                chosen = selected - pair.get(0).timestampMs <= pair.get(1).timestampMs - selected ? pair.get(0) : pair.get(1);
        }
        time.setText(format.format(new Date(selected)));
        if (chosen == null || MapRouteSegments.split(java.util.Collections.singletonList(chosen)).isEmpty()) {
            listener.onPoint(null);
            status.setText(!complete && right == points.size() ? R.string.cw_history_waiting_part : R.string.cw_history_gap);
        } else {
            time.setText(format.format(new Date(selected)) + "\n" + context.getString(R.string.cw_history_measured_at, format.format(new Date(chosen.timestampMs))));
            time.append(" · ±" + Math.max(1, Math.round(chosen.accuracyMeters)) + " м");
            listener.onPoint(chosen);
        }
    }
    private void buildEvents() {
        events.removeAllViews(); List<List<MapRouteSegments.Fix>> segments = MapRouteSegments.split(points);
        for (int i = 0; i < Math.min(eventLimit, segments.size()); i++) {
            List<MapRouteSegments.Fix> segment = segments.get(i);
            MapRouteSegments.Fix first = segment.get(0), last = segment.get(segment.size()-1);
            if (i > 0) events.addView(text(context.getString(R.string.cw_history_gap_between,
                format.format(new Date(segments.get(i-1).get(segments.get(i-1).size()-1).timestampMs)), format.format(new Date(first.timestampMs))), 13));
            MaterialButton event = new MaterialButton(context);
            event.setText(format.format(new Date(first.timestampMs)) + " — " + format.format(new Date(last.timestampMs)));
            event.setOnClickListener(view -> { slider.setProgress((int)((first.timestampMs-from)*10000/Math.max(1,to-from))); select(first.timestampMs); });
            events.addView(event);
        }
        if (segments.size() > eventLimit) events.addView(button(R.string.cw_history_more_events, () -> { eventLimit += 100; buildEvents(); }));
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView text(String value, int size) { TextView view = new TextView(context); view.setText(value); view.setTextSize(size); view.setTextColor(ContextCompat.getColor(context,R.color.cw_color_on_surface)); return view; }
    private MaterialButton button(int label, Runnable action) { MaterialButton view = new MaterialButton(context); view.setText(label); view.setMinHeight(dp(48)); view.setOnClickListener(clicked -> action.run()); return view; }
    private CoordinatorLayout.LayoutParams bottom() { CoordinatorLayout.LayoutParams params = new CoordinatorLayout.LayoutParams(-1,-2); params.gravity=Gravity.BOTTOM; params.setMargins(dp(12),0,dp(12),dp(12)); return params; }
    private int dp(int value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
}
