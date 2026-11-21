package ru.example.childwatch.designsystem;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public final class UpdateReleaseNotification {
    private static final String CHANNEL = "available_app_updates";
    private static final int ID = 6101;
    private static final int MAX_NOTES = 600;

    private UpdateReleaseNotification() { }

    public static String shortNotes(String value) {
        String notes = value == null ? "" : value.trim();
        if (notes.codePointCount(0, notes.length()) <= MAX_NOTES) return notes;
        return notes.substring(0, notes.offsetByCodePoints(0, MAX_NOTES - 1)).trim() + "…";
    }

    public static void show(Context context, int version, int icon, String title, String message, String releaseNotes) {
        SharedPreferences preferences = context.getSharedPreferences(CHANNEL, Context.MODE_PRIVATE);
        if (preferences.getInt("lastVersion", 0) >= version) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.cw_update_available_channel), NotificationManager.IMPORTANCE_LOW));
            if (manager.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE) return;
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch == null) return;
        PendingIntent open = PendingIntent.getActivity(context, ID, launch,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String notes = shortNotes(releaseNotes);
        String details = notes.isEmpty() ? message
            : message + "\n\n" + context.getString(R.string.cw_update_whats_new) + "\n" + notes;
        try {
            manager.notify(ID, new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(icon).setContentTitle(title).setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(details))
                .setContentIntent(open).setOnlyAlertOnce(true).setAutoCancel(true).build());
            preferences.edit().putInt("lastVersion", version).apply();
        } catch (SecurityException ignored) {
            // Notification permission may change after the check.
        }
    }

    public static void clear(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(ID);
    }
}
