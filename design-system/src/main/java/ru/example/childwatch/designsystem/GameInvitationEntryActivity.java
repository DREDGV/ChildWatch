package ru.example.childwatch.designsystem;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** Private PendingIntent entry: stale notifications cannot switch the current family profile. */
public final class GameInvitationEntryActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if(!FamilyGameInvitations.open(this,getIntent()))Toast.makeText(this,R.string.cw_game_invitation_unavailable,Toast.LENGTH_LONG).show();
        finish();
    }
}
