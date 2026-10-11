package ru.example.childwatch.designsystem;

import android.app.Activity;
import android.content.Intent;

/** Shared home entry into the dedicated games screen. */
public final class GamesMenu {
    private GamesMenu() { }
    public static void show(Activity activity) {
        activity.startActivity(new Intent(activity, GamesActivity.class));
    }
}
