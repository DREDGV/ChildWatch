package ru.example.childwatch.designsystem;

import android.app.Activity;
import android.content.Intent;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/** One shared entry point: only playable games appear in the chooser. */
public final class GamesMenu {
    private GamesMenu() { }

    public static BottomSheetDialog show(Activity activity) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        dialog.setContentView(R.layout.cw_games_menu);
        dialog.findViewById(R.id.gamesArcanoid).setOnClickListener(view -> {
            dialog.dismiss();
            activity.startActivity(new Intent(activity, ArcanoidActivity.class));
        });
        dialog.findViewById(R.id.gamesClose).setOnClickListener(view -> dialog.dismiss());
        dialog.show();
        return dialog;
    }
}
