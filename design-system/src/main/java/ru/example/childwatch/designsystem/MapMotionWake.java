package ru.example.childwatch.designsystem;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

/** Short-lived visual wake made from the same positions used to draw the avatar. */
public final class MapMotionWake extends Overlay {
    private static final long FADE_MS = 1_400;
    private final Map<String, ArrayDeque<Sample>> trails = new HashMap<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Point head = new Point(), prior = new Point(), pixel = new Point();
    private final float density;

    private static final class Sample {
        final GeoPoint position;
        final long at;
        Sample(double latitude, double longitude, long at) {
            position = new GeoPoint(latitude, longitude);
            this.at = at;
        }
    }

    public MapMotionWake(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        paint.setColor(context.getColor(R.color.cw_color_primary));
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void sample(String key, double latitude, double longitude) {
        long now = SystemClock.uptimeMillis();
        ArrayDeque<Sample> trail = trails.get(key);
        if (trail == null) {
            trail = new ArrayDeque<>();
            trails.put(key, trail);
        }
        Sample last = trail.peekLast();
        if (last != null && (now - last.at < 24 ||
                (last.position.getLatitude() == latitude && last.position.getLongitude() == longitude))) return;
        trail.addLast(new Sample(latitude, longitude, now));
        while (trail.size() > 64) trail.removeFirst();
    }

    public void discard(String key) { trails.remove(key); }
    public void clear() { trails.clear(); }

    @Override
    public void draw(Canvas canvas, MapView mapView, boolean shadow) {
        if (shadow) return;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            clear();
            return;
        }
        long now = SystemClock.uptimeMillis();
        Projection projection = mapView.getProjection();
        boolean visible = false;
        Iterator<ArrayDeque<Sample>> entries = trails.values().iterator();
        while (entries.hasNext()) {
            ArrayDeque<Sample> trail = entries.next();
            if (now - trail.getLast().at >= FADE_MS) {
                entries.remove();
                continue;
            }
            if (trail.size() >= 2) {
                drawTrail(canvas, projection, trail, now);
                visible = true;
            }
        }
        if (visible) mapView.postInvalidateOnAnimation();
    }

    private void drawTrail(Canvas canvas, Projection projection, ArrayDeque<Sample> trail, long now) {
        Iterator<Sample> points = trail.descendingIterator();
        Sample newest = points.next();
        projection.toPixels(newest.position, head);
        prior.set(head.x, head.y);
        float remaining = 76 * density;
        float drawn = 0;
        float directionX = 0, directionY = 0;
        float fade = 1f - (now - newest.at) / (float) FADE_MS;
        while (points.hasNext() && remaining > 0) {
            Sample older = points.next();
            if (now - older.at > FADE_MS) break;
            projection.toPixels(older.position, pixel);
            float dx = pixel.x - prior.x, dy = pixel.y - prior.y;
            float distance = (float) Math.hypot(dx, dy);
            if (distance < 1) continue;
            // The projection may choose different copies of the world at the date line.
            if (distance > 400 * density) return;
            directionX = dx / distance;
            directionY = dy / distance;
            float used = Math.min(distance, remaining);
            drawSegment(canvas, prior.x, prior.y, prior.x + directionX * used,
                prior.y + directionY * used, fade, drawn / (76 * density));
            drawn += used;
            remaining -= used;
            prior.set(pixel.x, pixel.y);
        }
        // At low zoom a few confirmed metres may fit under the avatar. Extend only the
        // decoration in screen pixels so its direction stays visible behind the icon.
        float minimum = 38 * density * fade;
        if (drawn > 0 && drawn < minimum) {
            float end = minimum - drawn;
            drawSegment(canvas, prior.x, prior.y, prior.x + directionX * end,
                prior.y + directionY * end, fade * .55f, .65f);
        }
    }

    private void drawSegment(Canvas canvas, float x, float y, float endX, float endY, float fade, float progress) {
        float taper = 1 - progress;
        paint.setAlpha(Math.round(190 * fade * taper));
        paint.setStrokeWidth(density * (2 + 4 * taper));
        canvas.drawLine(x, y, endX, endY, paint);
    }
}
