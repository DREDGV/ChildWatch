package ru.example.childwatch.designsystem;

import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** One finite transition per fresh measurement. Call on the UI thread only. */
public final class MapMarkerMotion {
    public interface Position { void set(double latitude, double longitude); }
    public interface FrameListener { void onFrame(String key, double latitude, double longitude); }
    public static final class DisplayedPosition {
        public final double latitude, longitude;
        private DisplayedPosition(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
    private static final class Entry {
        MapMarkerMotionPolicy.Fix fix;
        ValueAnimator animator;
        Position position;
        Runnable invalidate;
        double latitude, longitude;
    }
    private final Map<String, Entry> entries = new HashMap<>();
    private String scope;
    private FrameListener frameListener;

    public void setFrameListener(FrameListener listener) { frameListener = listener; }

    public DisplayedPosition displayedPosition(String key) {
        Entry entry = entries.get(key);
        return entry == null ? null : new DisplayedPosition(entry.latitude, entry.longitude);
    }

    private void cancel(Entry entry) {
        if (entry.animator != null) {
            entry.animator.removeAllUpdateListeners();
            entry.animator.cancel();
            entry.animator = null;
        }
    }

    private void render(String key, Entry entry) {
        entry.position.set(entry.latitude, entry.longitude);
        entry.invalidate.run();
        if (frameListener != null) frameListener.onFrame(key, entry.latitude, entry.longitude);
    }

    public void clear() {
        for (Entry entry : entries.values()) cancel(entry);
        entries.clear();
        scope = null;
    }

    public void retain(Set<String> keys) {
        for (String key : new HashSet<>(entries.keySet())) {
            if (!keys.contains(key)) cancel(entries.remove(key));
        }
    }

    public void update(String nextScope, String key, MapMarkerMotionPolicy.Fix next,
                       boolean enabled, Position position, Runnable invalidate) {
        if (!enabled || !ValueAnimator.areAnimatorsEnabled()) {
            clear();
            position.set(next.latitude, next.longitude);
            invalidate.run();
            if (frameListener != null) frameListener.onFrame(key, next.latitude, next.longitude);
            return;
        }
        if (!nextScope.equals(scope)) { clear(); scope = nextScope; }
        Entry existing = entries.get(key);
        if (existing != null && MapMarkerMotionPolicy.sameMeasurement(existing.fix, next)) {
            // Map pan/zoom recreates markers. Rebind the view without ending a live transition.
            existing.position = position;
            existing.invalidate = invalidate;
            position.set(existing.latitude, existing.longitude);
            invalidate.run();
            return;
        }
        Entry previous = entries.remove(key);
        if (previous != null) cancel(previous);
        Entry entry = new Entry();
        entry.fix = next;
        entry.position = position;
        entry.invalidate = invalidate;
        entry.latitude = next.latitude;
        entry.longitude = next.longitude;
        entries.put(key, entry);
        if (previous == null || !MapMarkerMotionPolicy.shouldAnimate(previous.fix, next, System.currentTimeMillis())) {
            render(key, entry);
            return;
        }
        // Interrupt from the actually displayed position, not the previous target.
        final double fromLatitude = previous.latitude;
        final double fromLongitude = previous.longitude;
        entry.latitude = fromLatitude;
        entry.longitude = fromLongitude;
        entry.animator = ValueAnimator.ofFloat(0f, 1f);
        entry.animator.setDuration(MapMarkerMotionPolicy.durationMs(previous.fix, next));
        entry.animator.setInterpolator(new LinearInterpolator());
        entry.animator.addUpdateListener(animation -> {
            float fraction = (Float) animation.getAnimatedValue();
            entry.latitude = fromLatitude + (next.latitude - fromLatitude) * fraction;
            entry.longitude = MapMarkerMotionPolicy.longitudeAt(fromLongitude, next.longitude, fraction);
            render(key, entry);
        });
        render(key, entry);
        entry.animator.start();
    }
}
