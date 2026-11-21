package ru.example.childwatch.designsystem;

import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import com.google.android.material.button.MaterialButton;
import java.text.SimpleDateFormat;
import java.util.Collections;
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
    private final LinearLayout placeEvents;
    private final ScrollView summaryScroll;
    private final TextView status, time, daySummary;
    private MaterialButton reopen;
    private final MaterialButton retry;
    private MaterialButton fold;
    private final SeekBar slider;
    private final MaterialButton playbackButton;
    private final Listener listener;
    private final long from, to;
    private final SimpleDateFormat format = new SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private MapHistoryPlayback playback = new MapHistoryPlayback(Collections.emptyList());
    private boolean complete, folded;
    private Long selectedTime;
    private MapRouteSegments.Fix displayedPoint;
    private boolean playing;
    private long lastFrameAt;
    private final Runnable playbackTick = this::tick;
    private int eventLimit = 100;

    public MapHistoryPanel(Context context, ViewGroup root, String person, long from, long to, Listener listener) {
        this.context = context; this.root = root; this.from = from; this.to = to; this.listener = listener;
        details = column();
        panel = column(); panel.setPadding(dp(12), dp(8), dp(12), dp(8));
        panel.setBackgroundColor(ContextCompat.getColor(context, R.color.cw_color_surface));
        panel.setElevation(dp(8));
        LinearLayout content = column();
        content.addView(text(person + "\n" + format.format(new Date(from)) + "\n" + java.util.TimeZone.getDefault().getID(), 16));
        LinearLayout header = new LinearLayout(context);
        fold = button(R.string.cw_history_fold, this::toggleFold);
        addAction(header, fold);
        addAction(header, button(R.string.cw_history_hide, this::hide));
        content.addView(header);
        time = text("", 18); content.addView(time);
        status = text(context.getString(R.string.cw_history_loading), 13); content.addView(status);
        content.addView(details);
        summaryScroll = new ScrollView(context) {
            @Override protected void onMeasure(int width, int height) {
                int available = View.MeasureSpec.getSize(height);
                if (available == 0) available = getResources().getDisplayMetrics().heightPixels;
                int limit = (int) (available * .52f);
                super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
            }
        };
        summaryScroll.addView(content);
        panel.addView(summaryScroll, new LinearLayout.LayoutParams(-1, -2));
        slider = new SeekBar(context); slider.setMax(10000);
        slider.setContentDescription(context.getString(R.string.cw_history_time)); details.addView(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (user) {
                    pausePlayback();
                    long selected = from + (to - from) * progress / 10000;
                    showSelection(selected, playback.pointAt(selected), false);
                }
            }
            public void onStartTrackingTouch(SeekBar bar) { pausePlayback(); }
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        playbackButton = button(R.string.cw_history_play, this::playOrStep);
        playbackButton.setAllCaps(false);
        updatePlaybackButton();
        panel.addView(playbackButton, new LinearLayout.LayoutParams(-1, -2));
        daySummary = text("", 14);
        ViewCompat.setAccessibilityHeading(daySummary, true);
        daySummary.setVisibility(View.GONE);
        details.addView(daySummary);
        placeEvents = column();
        details.addView(placeEvents);
        events = column();
        details.addView(events);
        LinearLayout actions = new LinearLayout(context);
        addAction(actions, button(R.string.cw_history_now, listener::onLive));
        addAction(actions, button(R.string.cw_history_events, () -> {
            pausePlayback();
            listener.onEvents();
        }));
        retry = button(R.string.cw_history_retry, listener::onRetry); retry.setVisibility(View.GONE); addAction(actions, retry);
        panel.addView(actions);
        reopen = button(R.string.cw_history_reopen, () -> { reopen.setVisibility(View.GONE); panel.setVisibility(View.VISIBLE); });
        reopen.setVisibility(View.GONE);
        root.addView(panel, bottom()); root.addView(reopen, bottom());
    }
    public boolean isVisible() { return panel.getVisibility() == View.VISIBLE; }
    public static final class PlaceEvent {
        public final long timestamp;
        public final String place;
        public final boolean entered;

        public PlaceEvent(long timestamp, String place, boolean entered) {
            this.timestamp = timestamp;
            this.place = place;
            this.entered = entered;
        }
    }

    public void showPlaceEvents(List<PlaceEvent> rows, int statusLabel, Runnable retryPlaces) {
        placeEvents.removeAllViews();
        TextView heading = text(context.getString(R.string.cw_day_places), 14);
        ViewCompat.setAccessibilityHeading(heading, true);
        placeEvents.addView(heading);
        if (statusLabel != 0) {
            placeEvents.addView(text(context.getString(statusLabel), 13));
        }
        if (retryPlaces != null) placeEvents.addView(button(R.string.cw_day_places_retry, retryPlaces));
        for (PlaceEvent row : rows) {
            if (row.timestamp < from || row.timestamp > to) continue;
            MaterialButton event = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            event.setAllCaps(false);
            event.setMinHeight(dp(48));
            event.setText(context.getString(row.entered ? R.string.cw_day_place_enter : R.string.cw_day_place_exit,
                clock.format(new Date(row.timestamp)), row.place));
            event.setOnClickListener(view -> {
                pausePlayback();
                select(row.timestamp);
                summaryScroll.smoothScrollTo(0, time.getTop());
            });
            placeEvents.addView(event);
        }
    }

    public void clearPlaceEvents() {
        placeEvents.removeAllViews();
    }
    public void hide() { pausePlayback(); panel.setVisibility(View.GONE); reopen.setVisibility(View.VISIBLE); }
    public void dispose() { pausePlayback(); root.removeView(panel); root.removeView(reopen); }
    public void pausePlayback() {
        playing = false;
        panel.removeCallbacks(playbackTick);
        updatePlaybackButton();
    }

    private void toggleFold() {
        folded = !folded;
        int visibility = folded ? View.GONE : View.VISIBLE;
        details.setVisibility(visibility);
        playbackButton.setVisibility(visibility);
        if (folded) pausePlayback();
        fold.setText(folded ? R.string.cw_history_expand : R.string.cw_history_fold);
    }

    private void addAction(LinearLayout row, MaterialButton action) {
        action.setAllCaps(false);
        action.setSingleLine(false);
        row.addView(action, new LinearLayout.LayoutParams(0, -2, 1));
    }
    public void limited(boolean legacy) {
        pausePlayback();
        complete = false;
        buildEvents();
        status.setText(legacy ? R.string.cw_history_limited : R.string.cw_history_size_limit);
        retry.setVisibility(View.GONE);
    }
    public void update(List<MapRouteSegments.Fix> fixes, boolean finished) {
        playback = new MapHistoryPlayback(fixes);
        complete = finished;
        status.setText(context.getString(finished ? R.string.cw_history_complete : R.string.cw_history_partial, fixes.size()));
        retry.setVisibility(View.GONE); buildEvents();
        if (selectedTime == null) selectedTime = playback.firstTime();
        if (selectedTime != null) {
            if (playing && !playback.canPlay(selectedTime)) pausePlayback();
            // Redrawing the route may remove its cursor overlay.
            displayedPoint = null;
            select(selectedTime);
        } else {
            updatePlaybackButton();
            if (finished) status.setText(fixes.isEmpty() ? R.string.cw_history_empty : R.string.cw_history_no_route);
        }
    }
    public void error(boolean denied, boolean changed) {
        pausePlayback();
        if (denied || changed) {
            clearPlaceEvents();
            playback = new MapHistoryPlayback(Collections.emptyList());
            selectedTime = null;
            events.removeAllViews();
            daySummary.setVisibility(View.GONE);
            time.setText("");
            displayedPoint = null;
            listener.onPoint(null);
            updatePlaybackButton();
        }
        status.setText(denied ? R.string.cw_history_denied : changed ? R.string.cw_history_changed :
            playback.firstTime() == null ? R.string.cw_history_unavailable : R.string.cw_history_partial_error);
        retry.setVisibility(denied ? View.GONE : View.VISIBLE);
    }

    private void updatePlaybackButton() {
        boolean canPlay = selectedTime != null && playback.canPlay(selectedTime);
        boolean hasNext = selectedTime != null && playback.nextSegmentAfter(selectedTime) != null;
        playbackButton.setEnabled(playing || canPlay || hasNext);
        String label = playing ? context.getString(R.string.cw_history_pause)
            : !canPlay && hasNext ? context.getString(R.string.cw_history_next_segment)
            : context.getString(R.string.cw_history_play, MapHistoryPlayback.SPEED);
        if (!TextUtils.equals(label, playbackButton.getText())) playbackButton.setText(label);
    }

    private void playOrStep() {
        if (playing) {
            pausePlayback();
            return;
        }
        if (selectedTime == null) return;
        if (!playback.canPlay(selectedTime)) {
            Long next = playback.nextSegmentAfter(selectedTime);
            if (next != null) select(next);
            return;
        }
        playing = true;
        lastFrameAt = SystemClock.elapsedRealtime();
        updatePlaybackButton();
        panel.postDelayed(playbackTick, 160);
    }

    private void tick() {
        if (!playing || selectedTime == null) return;
        long now = SystemClock.elapsedRealtime();
        MapHistoryPlayback.Frame frame = playback.advance(selectedTime, now - lastFrameAt);
        lastFrameAt = now;
        showSelection(frame.time, frame.point, true);
        if (frame.ended) {
            pausePlayback();
            int reason = playback.nextSegmentAfter(frame.time) != null || playback.canPlay(frame.time)
                ? R.string.cw_history_playback_gap
                : complete ? R.string.cw_history_playback_end : R.string.cw_history_playback_loaded_end;
            time.append("\n" + context.getString(reason));
        } else {
            panel.postDelayed(playbackTick, 160);
        }
    }

    private void select(long selected) {
        showSelection(selected, playback.pointAt(selected), true);
    }

    private void showSelection(long selected, MapRouteSegments.Fix chosen, boolean moveSlider) {
        selectedTime = selected;
        if (moveSlider) slider.setProgress((int) ((selected - from) * 10000 / Math.max(1, to - from)));
        time.setText(format.format(new Date(selected)));
        if (chosen == null) {
            int label = !complete && playback.nextSegmentAfter(selected) == null
                ? R.string.cw_history_waiting_part : R.string.cw_history_gap;
            time.append("\n" + context.getString(label));
        } else {
            time.append("\n" + context.getString(R.string.cw_history_measured_at, format.format(new Date(chosen.timestampMs))));
            time.append(" · ±" + Math.max(1, Math.round(chosen.accuracyMeters)) + " м");
        }
        if (chosen != displayedPoint) {
            displayedPoint = chosen;
            listener.onPoint(chosen);
        }
        updatePlaybackButton();
    }
    private void buildEvents() {
        events.removeAllViews();
        MapDaySummary summary = new MapDaySummary(playback.segments(), from, to, complete);
        if (playback.firstTime() == null && !complete) {
            daySummary.setVisibility(View.GONE);
            return;
        }
        daySummary.setVisibility(View.VISIBLE);
        daySummary.setText(context.getString(complete ? R.string.cw_day_summary : R.string.cw_day_summary_partial,
            duration(summary.observedMs), summary.stops, summary.gaps));
        for (int i = 0; i < Math.min(eventLimit, summary.events.size()); i++) {
            MapDaySummary.Event item = summary.events.get(i);
            MaterialButton event = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            event.setAllCaps(false);
            event.setMinHeight(dp(48));
            event.setText(context.getString(item.gap ? R.string.cw_day_gap : R.string.cw_day_stop,
                clock.format(new Date(item.start)), clock.format(new Date(item.end)), duration(item.end - item.start)));
            event.setOnClickListener(view -> {
                pausePlayback();
                if (item.gap) showSelection(item.seekTime(), null, true);
                else select(item.seekTime());
                summaryScroll.smoothScrollTo(0, time.getTop());
            });
            events.addView(event);
        }
        if (summary.events.size() > eventLimit) events.addView(button(R.string.cw_day_more_events, () -> { eventLimit += 100; buildEvents(); }));
    }

    private String duration(long millis) {
        long seconds = millis / 1_000;
        if (seconds >= 3600) return context.getString(R.string.cw_day_duration_hours, seconds / 3600, (seconds % 3600) / 60);
        if (seconds >= 60) return context.getString(R.string.cw_day_duration_minutes, seconds / 60);
        return context.getString(R.string.cw_day_duration_seconds, seconds);
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView text(String value, int size) { TextView view = new TextView(context); view.setText(value); view.setTextSize(size); view.setTextColor(ContextCompat.getColor(context,R.color.cw_color_on_surface)); return view; }
    private MaterialButton button(int label, Runnable action) { MaterialButton view = new MaterialButton(context); view.setText(label); view.setMinHeight(dp(48)); view.setOnClickListener(clicked -> action.run()); return view; }
    private CoordinatorLayout.LayoutParams bottom() { CoordinatorLayout.LayoutParams params = new CoordinatorLayout.LayoutParams(-1,-2); params.gravity=Gravity.BOTTOM; params.setMargins(dp(12),0,dp(12),dp(12)); return params; }
    private int dp(int value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
}
