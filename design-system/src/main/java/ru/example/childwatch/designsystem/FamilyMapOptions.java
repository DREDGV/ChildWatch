package ru.example.childwatch.designsystem;
import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Display choices only; these switches never change anybody's location permissions. */
public final class FamilyMapOptions {
    private final SharedPreferences prefs;
    public FamilyMapOptions(Context context) { prefs = context.getSharedPreferences("family_map_view", Context.MODE_PRIVATE); }
    public boolean distances() { return prefs.getBoolean("distances", true); }
    public boolean trails() { return prefs.getBoolean("trails", true); }
    public boolean allTrails() { return prefs.getBoolean("all_trails", false); }
    public boolean speeds() { return prefs.getBoolean("speeds", true); }
    public boolean speedColors() { return prefs.getBoolean("speed_colors", true); }
    public void install(MaterialToolbar toolbar, Runnable changed) {
        toolbar.getMenu().removeItem(93240);
        toolbar.getMenu().add(0, 93240, 0, R.string.cw_map_view).setOnMenuItemClickListener(item -> {
            Context context = toolbar.getContext();
            boolean[] choices = {distances(), trails(), allTrails(), speeds(), speedColors()};
            new MaterialAlertDialogBuilder(context).setTitle(R.string.cw_map_view)
                .setMultiChoiceItems(new String[]{context.getString(R.string.cw_map_distances),
                    context.getString(R.string.cw_map_trails), context.getString(R.string.cw_map_all_trails),
                    context.getString(R.string.cw_map_speeds), context.getString(R.string.cw_map_speed_colors)}, choices,
                    (dialog, index, checked) -> choices[index] = checked)
                .setPositiveButton(R.string.cw_map_apply, (dialog, which) -> {
                    prefs.edit().putBoolean("distances", choices[0]).putBoolean("trails", choices[1])
                        .putBoolean("all_trails", choices[2]).putBoolean("speeds", choices[3]).putBoolean("speed_colors", choices[4]).apply(); changed.run();
                }).setNeutralButton(R.string.cw_map_speed_legend_title, (dialog, which) ->
                    new MaterialAlertDialogBuilder(context).setTitle(R.string.cw_map_speed_legend_title)
                        .setMessage(context.getString(R.string.cw_map_speed_legend) + "\n\n" + context.getString(R.string.cw_map_speed_help))
                        .setPositiveButton(android.R.string.ok, null).show())
                .setNegativeButton(android.R.string.cancel, null).show();
            return true;
        });
    }
}
