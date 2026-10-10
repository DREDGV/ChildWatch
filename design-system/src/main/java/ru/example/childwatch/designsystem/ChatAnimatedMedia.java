package ru.example.childwatch.designsystem;

import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.CustomViewTarget;
import com.bumptech.glide.request.transition.Transition;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Owns decoded media only while the message is visible; callers own verified files and exact scope. */
public final class ChatAnimatedMedia {
    private ChatAnimatedMedia() { }
    private static final class Binding {
        final BooleanSupplier current, motion;
        Binding(BooleanSupplier current, BooleanSupplier motion) { this.current = current; this.motion = motion; }
    }
    public static boolean animationsEnabled(android.content.Context context) {
        return context.getSharedPreferences("chat_media_display", 0).getBoolean("animate_gif", true)
            && Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }
    public static void setAnimationsEnabled(android.content.Context context, boolean enabled) {
        context.getSharedPreferences("chat_media_display", 0).edit().putBoolean("animate_gif", enabled).apply();
    }
    public static void load(ImageView view, File file, BooleanSupplier current, BooleanSupplier motion, Runnable finished) {
        // A recycled ImageView must release its previous request before receiving new ownership.
        clear(view);
        Binding binding = new Binding(current, motion);
        view.setTag(R.id.chat_animated_media_binding, binding);
        AtomicBoolean ended = new AtomicBoolean();
        Runnable finish = () -> { if (ended.compareAndSet(false, true)) finished.run(); };
        Glide.with(view).asDrawable().load(file).override(384, 384)
            .diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true).dontAnimate()
            .into(new CustomViewTarget<ImageView, Drawable>(view) {
                @Override public void onResourceReady(@NonNull Drawable value, @Nullable Transition<? super Drawable> transition) {
                    if (view.getTag(R.id.chat_animated_media_binding) == binding && current.getAsBoolean()) {
                        view.setImageDrawable(value);
                        update(view, true);
                        view.post(() -> { if (view.getTag(R.id.chat_animated_media_binding) == binding) update(view, true); });
                    } else if (value instanceof Animatable) ((Animatable) value).stop();
                    finish.run();
                }
                @Override protected void onResourceCleared(@Nullable Drawable placeholder) {
                    // Clearing ownership is required even after access/lifecycle has changed.
                    if (view.getTag(R.id.chat_animated_media_binding) == binding) { update(view, false); view.setImageDrawable(null); }
                    finish.run();
                }
                @Override public void onLoadFailed(@Nullable Drawable placeholder) {
                    if (view.getTag(R.id.chat_animated_media_binding) == binding && current.getAsBoolean()) { view.setImageDrawable(null); view.setContentDescription(view.getContext().getString(R.string.chat_media_unavailable)); }
                    finish.run();
                }
            });
    }
    public static void update(ImageView view, boolean animate) {
        Drawable drawable = view.getDrawable();
        if (!(drawable instanceof Animatable)) return;
        Object stored = view.getTag(R.id.chat_animated_media_binding);
        Binding binding = stored instanceof Binding ? (Binding) stored : null;
        boolean visible = view.isAttachedToWindow() && view.getWindowVisibility() == View.VISIBLE
            && view.isShown() && view.getGlobalVisibleRect(new Rect());
        if (animate && visible && binding != null && binding.current.getAsBoolean()
                && binding.motion.getAsBoolean() && animationsEnabled(view.getContext())) ((Animatable) drawable).start();
        else ((Animatable) drawable).stop();
    }
    public static void clear(ImageView view) {
        update(view, false); Glide.with(view).clear(view); view.setImageDrawable(null);
        view.setTag(R.id.chat_animated_media_binding, null);
    }
}
