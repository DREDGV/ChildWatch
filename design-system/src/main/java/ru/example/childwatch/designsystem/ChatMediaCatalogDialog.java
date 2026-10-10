package ru.example.childwatch.designsystem;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.io.File;
import com.google.android.material.button.MaterialButton;

/** Emotions beside the smile button; selection creates a draft and never sends it. */
public final class ChatMediaCatalogDialog implements AutoCloseable {
    public static final class Entry {
        public final String id, label, type;
        public Entry(String id, String label, String type) { this.id=id; this.label=label; this.type=type; }
    }
    public interface Actions {
        void pick(String id); void retry(); void motion(boolean enabled); void closed();
        default void emoji() { }
        default void phoneGif() { }
    }
    private final Dialog dialog;
    private final Context context;
    private final BooleanSupplier current;
    private final Actions actions;
    private final TextView status;
    private final ProgressBar progress;
    private final Button retry;
    private final GridLayout grid;
    private final Button stickers, gifs;
    private final Button phoneGif;
    private final Map<String, File> previews = new HashMap<>();
    private List<Entry> entries = Collections.emptyList();
    private String selectedType="STICKER";
    private boolean busy, closed, failed;
    public ChatMediaCatalogDialog(Context context, BooleanSupplier current, Actions actions) {
        this.context=context; this.current=current; this.actions=actions;
        LinearLayout root=new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        int pad=dp(16); root.setPadding(pad,pad,pad,pad);
        TextView title=new TextView(context); title.setText(R.string.chat_catalog_title); title.setTextSize(20); title.setTypeface(null,android.graphics.Typeface.BOLD); root.addView(title);
        LinearLayout tabs=new LinearLayout(context);
        Button emoji=new MaterialButton(context); emoji.setText(R.string.chat_catalog_emoji); emoji.setMinHeight(dp(48));
        emoji.setBackgroundTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.cw_color_surface_variant)));
        emoji.setTextColor(context.getColor(R.color.cw_color_on_surface));
        emoji.setOnClickListener(v -> { if (valid()&&!busy) { close(); actions.emoji(); } });
        stickers=new MaterialButton(context); stickers.setText(R.string.chat_catalog_stickers); stickers.setMinHeight(dp(48));
        gifs=new MaterialButton(context); gifs.setText(R.string.chat_catalog_gifs); gifs.setMinHeight(dp(48));
        tabs.addView(emoji,new LinearLayout.LayoutParams(0,-2,1));
        tabs.addView(stickers,new LinearLayout.LayoutParams(0,-2,1)); tabs.addView(gifs,new LinearLayout.LayoutParams(0,-2,1)); root.addView(tabs);
        stickers.setOnClickListener(v -> { if (valid()) { selectedType="STICKER"; render(); } });
        gifs.setOnClickListener(v -> { if (valid()) { selectedType="GIF"; render(); } });
        status=new TextView(context); status.setTextSize(16); status.setText(R.string.chat_catalog_loading); root.addView(status);
        progress=new ProgressBar(context); root.addView(progress,new LinearLayout.LayoutParams(-1,dp(32)));
        grid=new GridLayout(context); root.addView(grid,new LinearLayout.LayoutParams(-1,-2));
        phoneGif=new MaterialButton(context); phoneGif.setText(R.string.chat_catalog_phone_gif); phoneGif.setMinHeight(dp(48));
        phoneGif.setOnClickListener(v -> { if (valid()&&!busy) { close(); actions.phoneGif(); } }); root.addView(phoneGif);
        CheckBox animate=new com.google.android.material.checkbox.MaterialCheckBox(context); animate.setMinHeight(dp(48)); animate.setText(R.string.chat_catalog_animate);
        animate.setButtonTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.cw_color_primary)));
        animate.setChecked(context.getSharedPreferences("chat_media_display",0).getBoolean("animate_gif",true));
        animate.setOnCheckedChangeListener((button,value)-> { if (valid()) { ChatAnimatedMedia.setAnimationsEnabled(context,value); updateGridMotion(value); actions.motion(value); } }); root.addView(animate);
        retry=new MaterialButton(context); retry.setText(R.string.chat_catalog_retry); retry.setMinHeight(dp(48)); retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> { if (valid()&&!busy) actions.retry(); }); root.addView(retry);
        Button close=new MaterialButton(context); close.setText(R.string.chat_transcription_close); close.setAllCaps(false); close.setMinHeight(dp(48));
        close.setBackgroundTintList(android.content.res.ColorStateList.valueOf(context.getColor(R.color.cw_color_surface_variant)));
        close.setTextColor(context.getColor(R.color.cw_color_primary)); close.setOnClickListener(v -> close()); root.addView(close);
        // One bounded scroll surface keeps every control reachable at large font sizes.
        ScrollView scroll=new ScrollView(context) {
            @Override protected void onMeasure(int widthSpec,int heightSpec) {
                int limit=(int)(getResources().getDisplayMetrics().heightPixels*.8f);
                int mode=View.MeasureSpec.getMode(heightSpec);
                if(mode!=View.MeasureSpec.UNSPECIFIED) limit=Math.min(limit,View.MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(limit,View.MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(root);
        com.google.android.material.bottomsheet.BottomSheetDialog sheet=new com.google.android.material.bottomsheet.BottomSheetDialog(context);
        sheet.setContentView(scroll); dialog=sheet;
        dialog.setOnCancelListener(v->close()); dialog.setOnDismissListener(v->close());
    }
    public void show() { dialog.show(); }
    public void phoneGifEnabled(boolean enabled) { phoneGif.setVisibility(enabled?View.VISIBLE:View.GONE); }
    public void items(List<Entry> items) {
        if (!valid()) return;
        failed=false; entries=new ArrayList<>(items); progress.setVisibility(View.GONE); retry.setVisibility(View.GONE); status.setText(R.string.chat_catalog_hint); render();
    }
    public void preview(String id, File file) {
        if (!valid()) return;
        previews.put(id,file);
        View image=grid.findViewWithTag(id); if (image instanceof ImageView) load((ImageView)image,id,file);
    }
    public void failed() {
        if (!valid()) return;
        failed=true; progress.setVisibility(View.GONE); retry.setVisibility(View.VISIBLE); status.setText(R.string.chat_catalog_failed);
    }
    public void busy(boolean value) {
        if (!valid()) return;
        busy=value; progress.setVisibility(value?View.VISIBLE:View.GONE);
        if (value) { failed=false; retry.setVisibility(View.GONE); status.setText(R.string.chat_catalog_attaching); }
        else if (!failed&&!entries.isEmpty()) status.setText(R.string.chat_catalog_hint);
        render();
    }
    private void render() {
        clearGrid(); grid.removeAllViews(); stickers.setSelected("STICKER".equals(selectedType)); gifs.setSelected("GIF".equals(selectedType));
        stickers.setTextColor(context.getColor("STICKER".equals(selectedType)?R.color.cw_color_on_primary:R.color.cw_color_on_surface));
        gifs.setTextColor(context.getColor("GIF".equals(selectedType)?R.color.cw_color_on_primary:R.color.cw_color_on_surface));
        stickers.setBackgroundTintList(android.content.res.ColorStateList.valueOf(context.getColor("STICKER".equals(selectedType)?R.color.cw_color_primary:R.color.cw_color_surface_variant)));
        gifs.setBackgroundTintList(android.content.res.ColorStateList.valueOf(context.getColor("GIF".equals(selectedType)?R.color.cw_color_primary:R.color.cw_color_surface_variant)));
        float scaled=context.getResources().getConfiguration().fontScale;
        int columns=scaled>1.3f?2:3; grid.setColumnCount(columns);
        int index=0;
        for (Entry entry:entries) {
            if (!selectedType.equals(entry.type)) continue;
            LinearLayout cell=new LinearLayout(context); cell.setOrientation(LinearLayout.VERTICAL); cell.setPadding(dp(4),dp(8),dp(4),dp(8));
            ImageView image=new ImageView(context); image.setTag(entry.id); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cell.addView(image,new LinearLayout.LayoutParams(-1,dp(88)));
            TextView label=new TextView(context); label.setText(entry.label); label.setTextSize(14); label.setGravity(android.view.Gravity.CENTER); cell.addView(label,new LinearLayout.LayoutParams(-1,-2));
            cell.setContentDescription(entry.label); cell.setEnabled(!busy); cell.setFocusable(true); cell.setClickable(true);
            android.util.TypedValue background=new android.util.TypedValue(); context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,background,true); cell.setBackgroundResource(background.resourceId);
            cell.setOnClickListener(v -> { if (valid()&&!busy) actions.pick(entry.id); });
            GridLayout.LayoutParams params=new GridLayout.LayoutParams(GridLayout.spec(index/columns),GridLayout.spec(index%columns,1f)); params.width=0; params.height=-2;
            grid.addView(cell,params); index++;
            File file=previews.get(entry.id); if(file!=null) load(image,entry.id,file);
        }
    }
    private void load(ImageView image, String id, File file) {
        ChatAnimatedMedia.load(image,file,() -> !closed&&current.getAsBoolean()&&id.equals(image.getTag()),
            () -> dialog.isShowing(), () -> { });
    }
    private void clearGrid() {
        for(int index=0;index<grid.getChildCount();index++) {
            LinearLayout cell=(LinearLayout)grid.getChildAt(index);
            if(cell.getChildAt(0) instanceof ImageView) ChatAnimatedMedia.clear((ImageView)cell.getChildAt(0));
        }
    }
    private void updateGridMotion(boolean enabled) {
        for(int index=0;index<grid.getChildCount();index++) {
            LinearLayout cell=(LinearLayout)grid.getChildAt(index);
            if(cell.getChildAt(0) instanceof ImageView) ChatAnimatedMedia.update((ImageView)cell.getChildAt(0),enabled);
        }
    }
    private int dp(int value) { return (int)(value*context.getResources().getDisplayMetrics().density+.5f); }
    private boolean valid() { if(closed)return false; if(!current.getAsBoolean()) {close();return false;} return true; }
    @Override public void close() { if(closed)return;closed=true;clearGrid();previews.clear();if(dialog.isShowing())dialog.dismiss();actions.closed(); }
}
