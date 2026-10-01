package ru.example.childwatch.designsystem;
import android.app.Activity;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.shape.RelativeCornerSize;
import java.util.List;
import java.util.function.Consumer;

/** Accessible full family list; the compact map strip can remain compact. */
public final class MapFamilyOverview {
    public static void show(Activity host, List<MapMemberStrip.Entry> entries, MapMemberStrip.AvatarBinder binder,
                            Consumer<MapMemberStrip.Entry> select) {
        float density = host.getResources().getDisplayMetrics().density;
        int space = Math.round(16 * density);
        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL); content.setPadding(space, 0, space, space);
        ScrollView scroll = new ScrollView(host); scroll.addView(content);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(host)
            .setTitle(R.string.cw_map_family).setView(scroll).setNegativeButton(android.R.string.cancel, null).create();
        if (entries.isEmpty()) {
            TextView empty = new TextView(host); empty.setText(R.string.cw_map_no_members); empty.setTextSize(16); content.addView(empty);
        }
        for (MapMemberStrip.Entry entry : entries) {
            LinearLayout row = new LinearLayout(host); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, space, 0, space); row.setMinimumHeight(Math.round(64 * density));
            ShapeableImageView avatar = new ShapeableImageView(host);
            avatar.setShapeAppearanceModel(ShapeAppearanceModel.builder().setAllCornerSizes(new RelativeCornerSize(.5f)).build());
            avatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            binder.bind(avatar, entry.avatar, entry.name); row.addView(avatar, new LinearLayout.LayoutParams(Math.round(48*density), Math.round(48*density)));
            LinearLayout text = new LinearLayout(host); text.setOrientation(LinearLayout.VERTICAL); text.setPadding(space, 0, 0, 0);
            TextView name = new TextView(host); name.setText(entry.name); name.setTextSize(16); name.setTypeface(null, android.graphics.Typeface.BOLD); text.addView(name);
            TextView status = new TextView(host); status.setText((entry.distance == null ? entry.age : entry.distance + "\n" + entry.age) + (entry.speed == null ? "" : "\n" + entry.speed));
            status.setTextSize(14); text.addView(status); row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            row.setFocusable(true); row.setClickable(true); row.setContentDescription(entry.name + ", " + status.getText());
            row.setOnClickListener(view -> { dialog.dismiss(); select.accept(entry); }); content.addView(row);
        }
        dialog.setOnShowListener(value -> {
            scroll.getLayoutParams().height = Math.min(Math.round(480*density), (int)(host.getResources().getDisplayMetrics().heightPixels*.65f));
            scroll.requestLayout();
        });
        dialog.show();
    }
    private MapFamilyOverview() {}
}
