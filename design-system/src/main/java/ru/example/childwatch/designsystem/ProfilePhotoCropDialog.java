package ru.example.childwatch.designsystem;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Decode away from the UI thread; export the exact visible square as a compact JPEG. */
public final class ProfilePhotoCropDialog {
    public interface Result { void finished(Uri photo); }

    public static void show(Activity activity, Uri source, Result result) {
        float density = activity.getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * density);
        content.setPadding(padding, 0, padding, padding);
        TextView hint = new TextView(activity);
        hint.setText(R.string.cw_profile_crop_hint);
        hint.setTextSize(14);
        hint.setTextColor(activity.getColor(R.color.cw_color_on_surface_variant));
        content.addView(hint);
        com.google.android.material.progressindicator.LinearProgressIndicator loading = new com.google.android.material.progressindicator.LinearProgressIndicator(activity);
        loading.setIndeterminate(true);
        content.addView(loading, new LinearLayout.LayoutParams(-1, -2));
        CropView crop = new CropView(activity);
        content.addView(crop, new LinearLayout.LayoutParams(-1, Math.round(260 * density)));
        TextView zoomLabel = new TextView(activity);
        zoomLabel.setText(R.string.cw_profile_crop_zoom);
        content.addView(zoomLabel);
        SeekBar zoom = new SeekBar(activity);
        zoom.setMax(300);
        zoom.setContentDescription(activity.getString(R.string.cw_profile_crop_zoom));
        zoom.setEnabled(false);
        content.addView(zoom, new LinearLayout.LayoutParams(-1, Math.round(48 * density)));
        crop.zoomChanged = value -> zoom.setProgress(Math.round((value - 1) * 100));
        zoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) crop.setZoom(1 + value / 100f);
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        MaterialButton rotate = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        rotate.setText(R.string.cw_profile_crop_rotate);
        rotate.setEnabled(false);
        rotate.setOnClickListener(v -> crop.rotate());
        content.addView(rotate, new LinearLayout.LayoutParams(-1, -2));
        TextView error = new TextView(activity);
        error.setTextColor(activity.getColor(R.color.cw_color_on_surface));
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(error);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        AtomicBoolean answered = new AtomicBoolean(false);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.cw_profile_crop_title)
                .setView(scroll)
                .setPositiveButton(R.string.cw_profile_crop_use, null)
                .setNegativeButton(android.R.string.cancel, null).create();
        androidx.lifecycle.LifecycleEventObserver observer = (owner, event) -> {
            if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) dialog.dismiss();
        };
        if (activity instanceof androidx.lifecycle.LifecycleOwner) ((androidx.lifecycle.LifecycleOwner) activity).getLifecycle().addObserver(observer);
        dialog.setOnDismissListener(d -> {
            if (activity instanceof androidx.lifecycle.LifecycleOwner) ((androidx.lifecycle.LifecycleOwner) activity).getLifecycle().removeObserver(observer);
            crop.release();
            if (answered.compareAndSet(false, true)) result.finished(null);
        });
        dialog.setOnShowListener(d -> {
            for (int button : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
                dialog.getButton(button).setAllCaps(false);
                dialog.getButton(button).setSingleLine(false);
                dialog.getButton(button).setMinHeight(Math.round(48 * density));
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            scroll.getLayoutParams().height = Math.min(Math.round(440 * density), (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.65f));
            scroll.requestLayout();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                Bitmap output;
                try { output = crop.export(); }
                catch (RuntimeException failure) { error.setText(R.string.cw_profile_crop_failed); return; }
                dialog.setCancelable(false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                new Thread(() -> {
                    File file = null;
                    Uri photo = null;
                    try {
                        File directory = new File(activity.getCacheDir(), "profile-crops");
                        if (!directory.exists() && !directory.mkdirs()) throw new java.io.IOException("Cannot create crop cache");
                        file = File.createTempFile("avatar-", ".jpg", directory);
                        try (FileOutputStream stream = new FileOutputStream(file)) {
                            if (!output.compress(Bitmap.CompressFormat.JPEG, 90, stream)) throw new java.io.IOException("Cannot encode crop");
                        }
                        photo = Uri.fromFile(file);
                    } catch (Exception failure) {
                        if (file != null) file.delete();
                    } finally { output.recycle(); }
                    Uri prepared = photo;
                    activity.runOnUiThread(() -> {
                        if (!dialog.isShowing() || activity.isDestroyed() || activity.isFinishing()) {
                            if (prepared != null) new File(prepared.getPath()).delete();
                            return;
                        }
                        if (prepared == null) {
                            dialog.setCancelable(true);
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                            error.setText(R.string.cw_profile_crop_failed);
                            return;
                        }
                        answered.set(true);
                        dialog.dismiss();
                        result.finished(prepared);
                    });
                }, "profile-crop-export").start();
            });
        });
        dialog.show();
        new Thread(() -> {
            Bitmap image = null;
            try { image = decode(activity, source); } catch (Exception | OutOfMemoryError failure) { /* Show a recoverable error below. */ }
            Bitmap loaded = image;
            activity.runOnUiThread(() -> {
                if (!dialog.isShowing() || activity.isDestroyed() || activity.isFinishing()) {
                    if (loaded != null) loaded.recycle();
                    return;
                }
                loading.setVisibility(View.GONE);
                if (loaded == null) { error.setText(R.string.cw_profile_crop_unreadable); return; }
                crop.setBitmap(loaded);
                zoom.setEnabled(true);
                rotate.setEnabled(true);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            });
        }, "profile-crop-decode").start();
    }

    public static boolean isTemporaryPhoto(android.content.Context context, Uri uri) {
        if (uri == null || !"file".equals(uri.getScheme()) || uri.getPath() == null) return false;
        try {
            File directory = new File(context.getCacheDir(), "profile-crops").getCanonicalFile();
            File file = new File(uri.getPath()).getCanonicalFile();
            return directory.equals(file.getParentFile()) && file.getName().startsWith("avatar-") && file.isFile();
        } catch (java.io.IOException error) { return false; }
    }

    /** Only files created by this cropper can be removed, never gallery originals. */
    public static void deleteTemporary(android.content.Context context, Uri uri) {
        if (uri == null || !"file".equals(uri.getScheme()) || uri.getPath() == null) return;
        try {
            File directory = new File(context.getCacheDir(), "profile-crops").getCanonicalFile();
            File file = new File(uri.getPath()).getCanonicalFile();
            if (directory.equals(file.getParentFile()) && file.getName().startsWith("avatar-")) file.delete();
        } catch (java.io.IOException ignored) { /* The cache can be cleared by Android. */ }
    }

    private static Bitmap decode(Activity activity, Uri uri) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream stream = activity.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(stream, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream stream = activity.getContentResolver().openInputStream(uri)) {
            decoded = BitmapFactory.decodeStream(stream, null, options);
        }
        if (decoded == null) return null;
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream stream = activity.getContentResolver().openInputStream(uri)) {
            if (stream != null) orientation = new ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) { /* Some formats have no EXIF. */ }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(-90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(-90); break;
            default: return decoded;
        }
        Bitmap oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
        if (oriented != decoded) decoded.recycle();
        return oriented;
    }

    private interface ZoomChanged { void changed(float zoom); }

    private static final class CropView extends View {
        private Bitmap bitmap;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF frame = new RectF();
        private final ScaleGestureDetector scaleDetector;
        private float zoom = 1, offsetX, offsetY, lastX, lastY;
        private ZoomChanged zoomChanged;

        CropView(Activity context) {
            super(context);
            setFocusable(true);
            setContentDescription(context.getString(R.string.cw_profile_crop_hint));
            scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    setZoom(zoom * detector.getScaleFactor());
                    return true;
                }
            });
        }
        void setBitmap(Bitmap value) { bitmap = value; zoom = 1; offsetX = offsetY = 0; invalidate(); }
        void release() { if (bitmap != null) bitmap.recycle(); bitmap = null; }
        float baseScale() { return Math.max(frame.width() / bitmap.getWidth(), frame.height() / bitmap.getHeight()); }
        void setZoom(float value) {
            if (bitmap == null) return;
            zoom = Math.max(1, Math.min(4, value)); clamp(); invalidate();
            if (zoomChanged != null) zoomChanged.changed(zoom);
        }
        void rotate() {
            if (bitmap == null) return;
            Matrix rotation = new Matrix(); rotation.setRotate(90);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), rotation, true);
            if (rotated != bitmap) bitmap.recycle();
            setBitmap(rotated);
            if (zoomChanged != null) zoomChanged.changed(1);
        }
        private void clamp() {
            if (bitmap == null || frame.isEmpty()) return;
            float scale = baseScale() * zoom;
            float maxX = Math.max(0, (bitmap.getWidth() * scale - frame.width()) / 2);
            float maxY = Math.max(0, (bitmap.getHeight() * scale - frame.height()) / 2);
            offsetX = Math.max(-maxX, Math.min(maxX, offsetX));
            offsetY = Math.max(-maxY, Math.min(maxY, offsetY));
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            float side = Math.min(w, h) * 0.92f;
            frame.set((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2);
            clamp();
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.rgb(25, 39, 37));
            if (bitmap == null || frame.isEmpty()) return;
            float scale = baseScale() * zoom;
            RectF dest = new RectF(frame.centerX() + offsetX - bitmap.getWidth() * scale / 2,
                    frame.centerY() + offsetY - bitmap.getHeight() * scale / 2,
                    frame.centerX() + offsetX + bitmap.getWidth() * scale / 2,
                    frame.centerY() + offsetY + bitmap.getHeight() * scale / 2);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawBitmap(bitmap, null, dest, paint);
            Path outside = new Path();
            outside.setFillType(Path.FillType.EVEN_ODD);
            outside.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            outside.addCircle(frame.centerX(), frame.centerY(), frame.width() / 2, Path.Direction.CW);
            paint.setColor(0xAA000000);
            canvas.drawPath(outside, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(frame.centerX(), frame.centerY(), frame.width() / 2, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (bitmap == null) return false;
            scaleDetector.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
                lastX = event.getX(); lastY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                if (!scaleDetector.isInProgress() && event.getPointerCount() == 1) {
                    offsetX += event.getX() - lastX; offsetY += event.getY() - lastY; clamp(); invalidate();
                }
                lastX = event.getX(); lastY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
                int remaining = event.getActionIndex() == 0 ? 1 : 0;
                lastX = event.getX(remaining); lastY = event.getY(remaining);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN);
        }
        @Override public boolean performAccessibilityAction(int action, android.os.Bundle args) {
            if (bitmap != null) {
                float step = frame.width() * 0.1f;
                if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()) offsetX -= step;
                else if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()) offsetX += step;
                else if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()) offsetY -= step;
                else if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()) offsetY += step;
                else return super.performAccessibilityAction(action, args);
                clamp(); invalidate(); return true;
            }
            return super.performAccessibilityAction(action, args);
        }
        Bitmap export() {
            if (bitmap == null || frame.isEmpty()) throw new IllegalStateException("Photo not ready");
            clamp();
            Bitmap output = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            canvas.drawColor(Color.WHITE);
            float scale = baseScale() * zoom;
            float left = frame.centerX() + offsetX - bitmap.getWidth() * scale / 2;
            float top = frame.centerY() + offsetY - bitmap.getHeight() * scale / 2;
            float ratio = 512 / frame.width();
            RectF dest = new RectF((left - frame.left) * ratio, (top - frame.top) * ratio,
                    (left + bitmap.getWidth() * scale - frame.left) * ratio,
                    (top + bitmap.getHeight() * scale - frame.top) * ratio);
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
            canvas.drawBitmap(bitmap, null, dest, paint);
            return output;
        }
    }
    private ProfilePhotoCropDialog() {}
}
