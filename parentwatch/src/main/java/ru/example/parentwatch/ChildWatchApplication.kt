package ru.example.parentwatch

import android.app.Application
import com.vanniktech.emoji.EmojiManager
import com.vanniktech.emoji.google.GoogleEmojiProvider
import ru.example.parentwatch.utils.AppVisibilityTracker

class ChildWatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        EmojiManager.install(GoogleEmojiProvider())
        ru.example.childwatch.designsystem.FamilyGameInvitations.install(this) {
            ru.example.parentwatch.games.FamilyGamesHost.create(this)
        }
        AppVisibilityTracker.register(this)
    }
}
