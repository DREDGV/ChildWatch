package ru.example.childwatch.designsystem;

import android.app.Activity;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.ArrayList;
import java.util.List;

/** Shared own-person editor. Selection is a draft until the host confirms publication. */
public final class FamilyProfileEditor {
    private static final java.util.Map<Activity, AlertDialog> OPEN = new java.util.WeakHashMap<>();
    public static boolean isOpen(Activity activity) {
        AlertDialog current = OPEN.get(activity);
        return current != null && current.isShowing();
    }
    private static final String STATE_KEY = "cw.ownProfileDraft";
    private static final java.util.Map<Activity, FamilyProfileEditor> EDITORS = new java.util.WeakHashMap<>();
    private static final java.util.Map<Activity, android.os.Bundle> RESTORED = new java.util.WeakHashMap<>();

    /** Call after content setup. Posting allows the host to finish initializing its handlers. */
    public static void restore(Activity host, android.os.Bundle saved, Runnable reopen) {
        android.os.Bundle draft = saved == null ? null : saved.getBundle(STATE_KEY);
        if (draft == null) return;
        RESTORED.put(host, draft);
        host.getWindow().getDecorView().post(() -> {
            if (!host.isFinishing() && !host.isDestroyed()) reopen.run();
        });
    }

    public static void saveState(Activity host, android.os.Bundle out) {
        FamilyProfileEditor editor = EDITORS.get(host);
        android.os.Bundle draft = editor != null ? editor.snapshot() : RESTORED.get(host);
        if (draft != null) out.putBundle(STATE_KEY, draft);
    }

    private android.os.Bundle snapshot() {
        android.os.Bundle state = new android.os.Bundle();
        state.putString("scope", scope);
        state.putString("name", name.getText().toString());
        state.putString("avatar", selectedAvatar);
        state.putString("photo", pendingPhoto == null ? null : pendingPhoto.toString());
        state.putBoolean("expanded", expanded);
        return state;
    }

    public interface Binder { void bind(ShapeableImageView image, String key, String name); }
    public interface PhotoResult { void picked(Uri uri); }
    public interface Picker { void pick(PhotoResult result); }
    public interface Done { void complete(boolean stored); }
    public interface Save { void save(String name, String avatar, Uri photo, Done done); }

    private final Activity activity;
    private final String scope;
    private final String originalName;
    private final String originalAvatar;
    private final Binder binder;
    private final Save save;
    private final List<String> values;
    private final List<ProfileAvatarChoice> choices = new ArrayList<>();
    private final TextInputLayout nameField;
    private final TextInputEditText name;
    private final ShapeableImageView preview;
    private final MaterialButton photoButton;
    private final LinearProgressIndicator progress;
    private final TextView message;
    private final AlertDialog dialog;
    private String selectedAvatar;
    private Uri pendingPhoto;
    private boolean busy;
    private boolean picking;
    private boolean expanded;
    private boolean retainingDraft;
    private AlertDialog discardDialog;
    private Object gestureBack;

    public static AlertDialog show(Activity activity, String initialName, String avatar,
                                   List<String> values, Binder binder, Picker picker, Save save, Runnable closed, String scope) {
        return new FamilyProfileEditor(activity, initialName, avatar, values, binder, picker, save, closed, scope).dialog;
    }

    private FamilyProfileEditor(Activity host, String initialName, String avatar, List<String> presets,
                                Binder imageBinder, Picker picker, Save onSave, Runnable closed, String identityScope) {
        activity = host;
        scope = identityScope;
        android.os.Bundle draft = RESTORED.remove(host);
        if (draft != null && !java.util.Objects.equals(scope, draft.getString("scope"))) {
            ProfilePhotoCropDialog.deleteTemporary(host, Uri.parse(draft.getString("photo", "")));
            draft = null;
        }
        originalName = initialName == null ? "" : initialName.trim();
        originalAvatar = avatar;
        selectedAvatar = draft == null ? avatar : draft.getString("avatar");
        if (draft != null && draft.getString("photo") != null) {
            Uri candidate = Uri.parse(draft.getString("photo"));
            // Only restore our private generated crop; never dereference an arbitrary saved URI.
            if (ProfilePhotoCropDialog.isTemporaryPhoto(host, candidate)) pendingPhoto = candidate;
        }
        binder = imageBinder;
        save = onSave;
        values = presets;
        message = label(R.string.cw_profile_failed, 14);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        message.setVisibility(View.GONE);
        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(16));

        preview = new ShapeableImageView(host);
        preview.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(dp(48)).build());
        preview.setContentDescription(host.getString(R.string.cw_profile_preview));
        preview.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(96), dp(96));
        previewParams.gravity = Gravity.CENTER_HORIZONTAL;
        previewParams.bottomMargin = dp(8);
        content.addView(preview, previewParams);
        TextView hint = label(R.string.cw_profile_hint, 14);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(host.getColor(R.color.cw_color_on_surface_variant));
        content.addView(hint);
        photoButton = new MaterialButton(host, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        photoButton.setText(R.string.cw_profile_photo);
        photoButton.setMinHeight(dp(48));
        LinearLayout.LayoutParams photoParams = new LinearLayout.LayoutParams(-1, -2);
        photoParams.topMargin = dp(12);
        photoParams.bottomMargin = dp(16);
        content.addView(photoButton, photoParams);
        photoButton.setVisibility(picker == null ? View.GONE : View.VISIBLE);

        nameField = new TextInputLayout(host);
        nameField.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        nameField.setHint(host.getString(R.string.cw_profile_name));
        nameField.setCounterEnabled(true);
        nameField.setCounterMaxLength(80);
        name = new TextInputEditText(host);
        name.setSingleLine(true);
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setText(draft == null ? originalName : draft.getString("name", originalName));
        name.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        nameField.addView(name, new LinearLayout.LayoutParams(-1, -2));
        content.addView(nameField, new LinearLayout.LayoutParams(-1, -2));
        TextView presetsTitle = label(R.string.cw_profile_presets, 16);
        presetsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        presetsTitle.setPadding(0, dp(20), 0, dp(8));
        content.addView(presetsTitle);

        expanded = draft == null ? values.indexOf(avatar) >= 9 : draft.getBoolean("expanded");
        for (int row = 0; row * 3 < values.size(); row++) {
            LinearLayout line = new LinearLayout(host);
            line.setGravity(Gravity.CENTER);
            for (int col = 0; col < 3; col++) {
                int index = row * 3 + col;
                if (index >= values.size()) {
                    line.addView(new View(host), new LinearLayout.LayoutParams(0, dp(68), 1));
                    continue;
                }
                String value = values.get(index);
                ProfileAvatarChoice choice = new ProfileAvatarChoice(host, index + 1);
                choice.setTag(row);
                binder.bind(choice.image, value, originalName);
                choice.setOnClickListener(v -> {
                    ProfilePhotoCropDialog.deleteTemporary(host, pendingPhoto);
                    pendingPhoto = null;
                    selectedAvatar = value;
                    message.setVisibility(View.GONE);
                    refresh();
                });
                choices.add(choice);
                line.addView(choice, new LinearLayout.LayoutParams(0, dp(68), 1));
            }
            line.setTag(row);
            line.setVisibility(row < 3 || expanded ? View.VISIBLE : View.GONE);
            content.addView(line);
        }
        MaterialButton all = new MaterialButton(host, null, com.google.android.material.R.attr.borderlessButtonStyle);
        all.setText(expanded ? host.getString(R.string.cw_profile_fewer_avatars) : host.getString(R.string.cw_profile_all_avatars, values.size()));
        all.setVisibility(values.size() > 9 ? View.VISIBLE : View.GONE);
        content.addView(all, new LinearLayout.LayoutParams(-1, -2));
        all.setOnClickListener(v -> {
            expanded = !expanded;
            for (ProfileAvatarChoice choice : choices) {
                View line = (View) choice.getParent();
                line.setVisibility((int) line.getTag() < 3 || expanded ? View.VISIBLE : View.GONE);
            }
            all.setText(expanded ? host.getString(R.string.cw_profile_fewer_avatars) : host.getString(R.string.cw_profile_all_avatars, values.size()));
        });
        progress = new LinearProgressIndicator(host);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        content.addView(progress, new LinearLayout.LayoutParams(-1, -2));
        content.addView(message);

        ScrollView scroll = new ScrollView(host);
        scroll.setFillViewport(false);
        scroll.addView(content);
        final boolean[] keyboardShown = {false};
        android.view.ViewTreeObserver.OnGlobalLayoutListener keyboardLayout = () -> {
            AlertDialog current = OPEN.get(host);
            if (current == null || current.getWindow() == null || !current.isShowing()) return;
            View decor = current.getWindow().getDecorView();
            android.graphics.Rect visible = new android.graphics.Rect();
            decor.getWindowVisibleDisplayFrame(visible);
            int screenHeight = host.getResources().getDisplayMetrics().heightPixels;
            boolean keyboard = screenHeight - visible.bottom > dp(160);
            int bodyHeight = Math.max(dp(120), Math.min(dp(520), visible.height() - dp(180)));
            if (scroll.getLayoutParams().height != bodyHeight) {
                scroll.getLayoutParams().height = bodyHeight;
                scroll.requestLayout();
            }
            if (keyboard && !keyboardShown[0] && name.hasFocus()) {
                scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, nameField.getTop() - dp(12))));
            }
            keyboardShown[0] = keyboard;
        };
        dialog = new MaterialAlertDialogBuilder(host)
                .setTitle(R.string.cw_profile_title)
                .setView(scroll)
                .setPositiveButton(R.string.cw_profile_save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        Runnable back = () -> {
            if (keyboardShown[0]) {
                android.view.inputmethod.InputMethodManager input = (android.view.inputmethod.InputMethodManager)
                        host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (input != null) input.hideSoftInputFromWindow(name.getWindowToken(), 0);
                name.clearFocus();
            } else requestClose();
        };
        dialog.setOnKeyListener((d, key, event) -> {
            if (key != android.view.KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) back.run();
            return true;
        });
        dialog.setOnShowListener(d -> {
            for (int button : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
                dialog.getButton(button).setAllCaps(false);
                dialog.getButton(button).setSingleLine(false);
                dialog.getButton(button).setMinHeight(dp(48));
            }
            if (android.os.Build.VERSION.SDK_INT >= 33 && dialog.getWindow() != null) {
                gestureBack = Api33Back.install(dialog.getWindow(), back);
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> requestClose());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit());
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                dialog.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(keyboardLayout);
            }
        });
        name.setOnFocusChangeListener((v, focused) -> {
            if (focused) scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, nameField.getTop() - dp(12))));
        });
        name.setOnEditorActionListener((v, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) return false;
            android.view.inputmethod.InputMethodManager input = (android.view.inputmethod.InputMethodManager)
                    host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (input != null) input.hideSoftInputFromWindow(name.getWindowToken(), 0);
            name.clearFocus();
            return true;
        });
        photoButton.setOnClickListener(v -> {
            if (busy || picking || picker == null) return;
            picking = true;
            try {
                picker.pick(uri -> {
                    if (!alive()) return;
                    if (uri == null) { picking = false; return; }
                    ProfilePhotoCropDialog.show(host, uri, cropped -> {
                        picking = false;
                        if (!alive()) { ProfilePhotoCropDialog.deleteTemporary(host, cropped); return; }
                        if (cropped == null) return;
                        ProfilePhotoCropDialog.deleteTemporary(host, pendingPhoto);
                        pendingPhoto = cropped;
                        message.setVisibility(View.GONE);
                        refresh();
                    });
                });
            } catch (RuntimeException error) {
                picking = false;
                showFailure(R.string.cw_profile_crop_unreadable);
            }
        });
        name.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { nameField.setError(null); }
            public void afterTextChanged(Editable text) { if (pendingPhoto == null && selectedAvatar == null) refresh(); }
        });
        androidx.lifecycle.LifecycleEventObserver observer = (owner, event) -> {
            if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                retainingDraft = !host.isFinishing();
                if (discardDialog != null) discardDialog.dismiss();
                dialog.dismiss();
            }
        };
        if (host instanceof androidx.lifecycle.LifecycleOwner) {
            ((androidx.lifecycle.LifecycleOwner) host).getLifecycle().addObserver(observer);
        }
        dialog.setOnDismissListener(d -> {
            if (dialog.getWindow() != null) {
                android.view.ViewTreeObserver tree = dialog.getWindow().getDecorView().getViewTreeObserver();
                if (tree.isAlive()) tree.removeOnGlobalLayoutListener(keyboardLayout);
            }
            OPEN.remove(host);
            EDITORS.remove(host);
            if (android.os.Build.VERSION.SDK_INT >= 33 && gestureBack != null && dialog.getWindow() != null) {
                Api33Back.remove(dialog.getWindow(), gestureBack);
            }
            if (host instanceof androidx.lifecycle.LifecycleOwner) ((androidx.lifecycle.LifecycleOwner) host).getLifecycle().removeObserver(observer);
            // State saved by the host owns this crop across recreation/process restoration.
            if (!retainingDraft && !host.isChangingConfigurations() && !host.isDestroyed())
                ProfilePhotoCropDialog.deleteTemporary(host, pendingPhoto);
            closed.run();
        });
        OPEN.put(host, dialog);
        EDITORS.put(host, this);
        dialog.show();
        refresh();
        if (draft != null && draft.getString("photo") != null && pendingPhoto == null)
            showFailure(R.string.cw_profile_crop_unreadable);
    }

    private void refresh() {
        binder.bind(preview, pendingPhoto == null ? selectedAvatar : pendingPhoto.toString(), name.getText().toString());
        for (int i = 0; i < choices.size(); i++) choices.get(i).setChecked(pendingPhoto == null && values.get(i).equals(selectedAvatar));
    }

    private void submit() {
        if (busy || picking) return;
        String displayName = name.getText().toString().trim();
        if (displayName.length() < 2 || displayName.length() > 80) {
            nameField.setError(activity.getString(R.string.cw_profile_name_error));
            name.requestFocus();
            return;
        }
        setBusy(true);
        try {
            save.save(displayName, selectedAvatar, pendingPhoto, stored -> {
                if (!alive()) return;
                if (stored) dialog.dismiss();
                else { setBusy(false); showFailure(R.string.cw_profile_failed); }
            });
        } catch (RuntimeException error) {
            setBusy(false);
            showFailure(R.string.cw_profile_failed);
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        dialog.setCancelable(!value);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!value);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(value ? R.string.cw_profile_saving : R.string.cw_profile_save);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!value);
        photoButton.setEnabled(!value);
        name.setEnabled(!value);
        for (ProfileAvatarChoice choice : choices) choice.setEnabled(!value);
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        message.setText(R.string.cw_profile_saving);
        message.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    private void showFailure(int text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
        message.post(() -> message.requestRectangleOnScreen(new android.graphics.Rect(0, 0, message.getWidth(), message.getHeight()), false));
    }

    private void requestClose() {
        if (busy || (discardDialog != null && discardDialog.isShowing())) return;
        boolean changed = pendingPhoto != null || !originalName.equals(name.getText().toString().trim())
                || !java.util.Objects.equals(originalAvatar, selectedAvatar);
        if (!changed) { dialog.dismiss(); return; }
        discardDialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.cw_profile_discard_title)
                .setPositiveButton(R.string.cw_profile_stay, null)
                .setNegativeButton(R.string.cw_profile_discard, (d, w) -> dialog.dismiss()).show();
    }

    private boolean alive() { return dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed(); }
    private TextView label(int text, int size) {
        TextView view = new TextView(activity);
        view.setText(text); view.setTextSize(size); view.setTextColor(activity.getColor(R.color.cw_color_on_surface));
        return view;
    }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    @androidx.annotation.RequiresApi(33)
    private static final class Api33Back {
        static Object install(android.view.Window window, Runnable action) {
            android.window.OnBackInvokedCallback callback = action::run;
            window.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }
        static void remove(android.view.Window window, Object callback) {
            window.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) callback);
        }
    }
}
