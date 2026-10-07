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
    private static final class Entry {
        MapMarkerMotionPolicy.Fix fix;
        ValueAnimator animator;
        Position position;
        Runnable invalidate;
        double latitude, longitude;
    }
    private final Map<String, Entry> entries = new HashMap<>();
    private String scope;

    private void finish(Entry entry) {
        if (entry.animator != null) {
            entry.animator.cancel();
            entry.animator.removeAllUpdateListeners();
            entry.animator = null;
        }
        if (entry.position != null) {
            entry.position.set(entry.fix.latitude, entry.fix.longitude);
            entry.invalidate.run();
        }
    }

    public void clear() {
        for (Entry entry : entries.values()) finish(entry);
        entries.clear();
        scope = null;
    }

    public void retain(Set<String> keys) {
        for (String key : new HashSet<>(entries.keySet())) {
            if (!keys.contains(key)) finish(entries.remove(key));
        }
    }

    public void update(String nextScope, String key, MapMarkerMotionPolicy.Fix next,
                       boolean enabled, Position position, Runnable invalidate) {
        if (!enabled || !ValueAnimator.areAnimatorsEnabled()) {
            clear();
            position.set(next.latitude, next.longitude);
            return;
        }
        if (!nextScope.equals(scope)) { clear(); scope = nextScope; }
        Entry existing = entries.get(key);
        if (existing != null && MapMarkerMotionPolicy.sameMeasurement(existing.fix, next)) {
            // Map pan/zoom recreates markers. Rebind the view without ending a live transition.
            existing.position = position;
            existing.invalidate = invalidate;
            position.set(existing.latitude, existing.longitude);
            return;
        }
        Entry previous = entries.remove(key);
        if (previous != null) finish(previous);
        Entry entry = new Entry();
        entry.fix = next;
        entry.position = position;
        entry.invalidate = invalidate;
        entry.latitude = next.latitude;
        entry.longitude = next.longitude;
        entries.put(key, entry);
        if (previous == null || !MapMarkerMotionPolicy.shouldAnimate(previous.fix, next, System.currentTimeMillis())) {
            position.set(next.latitude, next.longitude);
            return;
        }
        MapMarkerMotionPolicy.Fix from = previous.fix;
        entry.latitude = from.latitude;
        entry.longitude = from.longitude;
        entry.animator = ValueAnimator.ofFloat(0f, 1f);
        entry.animator.setDuration(550);
        entry.animator.setInterpolator(new LinearInterpolator());
        entry.animator.addUpdateListener(animation -> {
            float fraction = (Float) animation.getAnimatedValue();
            entry.latitude = from.latitude + (next.latitude - from.latitude) * fraction;
            entry.longitude = MapMarkerMotionPolicy.longitudeAt(from.longitude, next.longitude, fraction);
            entry.position.set(entry.latitude, entry.longitude);
            entry.invalidate.run();
        });
        entry.animator.start();
    }
}
