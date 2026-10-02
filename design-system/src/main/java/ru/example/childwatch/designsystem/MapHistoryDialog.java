package ru.example.childwatch.designsystem;

import android.content.Context;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Chronology uses the same segmentation as the route; it never interpolates a gap. */
public final class MapHistoryDialog {
    private MapHistoryDialog() {}

    public static void show(Context context, String person, long from, long to,
                            List<MapRouteSegments.Fix> fixes, boolean limited, Runnable events,
                            Consumer<MapRouteSegments.Fix> select) {
        SimpleDateFormat date = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
        String title = person + "\n" + date.format(new Date(from)) + " — " + date.format(new Date(to));
        if (fixes.isEmpty()) {
            new MaterialAlertDialogBuilder(context).setTitle(title)
                .setMessage(R.string.cw_history_empty).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        List<String> rows = new ArrayList<>();
        List<MapRouteSegments.Fix> targets = new ArrayList<>();
        rows.add(context.getString(R.string.cw_history_hint)); targets.add(null);
        if (limited) { rows.add(context.getString(R.string.cw_history_limited)); targets.add(null); }
        List<List<MapRouteSegments.Fix>> segments = MapRouteSegments.split(fixes);
        boolean hasLine = false;
        for (List<MapRouteSegments.Fix> segment : segments) if (segment.size() > 1) hasLine = true;
        if (!hasLine) { rows.add(context.getString(R.string.cw_history_no_route)); targets.add(null); }
        MapRouteSegments.Fix previous = null;
        for (MapRouteSegments.Fix point : fixes) {
            List<List<MapRouteSegments.Fix>> pair = previous == null ? java.util.Collections.emptyList()
                : MapRouteSegments.split(java.util.Arrays.asList(previous, point));
            if (previous != null && (pair.size() != 1 || pair.get(0).size() != 2)) {
                rows.add(context.getString(R.string.cw_history_break,
                    date.format(new Date(previous.timestampMs)), date.format(new Date(point.timestampMs))));
                targets.add(null);
            }
            boolean usable = !MapRouteSegments.split(java.util.Collections.singletonList(point)).isEmpty();
            rows.add(usable ? context.getString(R.string.cw_history_accuracy,
                    date.format(new Date(point.timestampMs)), Math.max(1, Math.round(point.accuracyMeters)))
                : context.getString(R.string.cw_history_uncertain, date.format(new Date(point.timestampMs))));
            targets.add(point);
            previous = point;
        }
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(context).setTitle(title)
            .setItems(rows.toArray(new String[0]), (ignoredDialog, which) -> {
                MapRouteSegments.Fix point = targets.get(which);
                if (point != null) select.accept(point);
            }).setNeutralButton(R.string.cw_history_events, (ignored, which) -> events.run())
            .setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnShowListener(ignored -> dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
            MapRouteSegments.Fix point = targets.get(position);
            if (point != null) { select.accept(point); dialog.dismiss(); }
        }));
        dialog.show();
    }
}
