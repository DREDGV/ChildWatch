package ru.example.childwatch

import android.app.Application
import com.vanniktech.emoji.EmojiManager
import com.vanniktech.emoji.google.GoogleEmojiProvider

class ChildWatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        EmojiManager.install(GoogleEmojiProvider())
        ru.example.childwatch.designsystem.FamilyGameInvitations.install(this) {
            ru.example.childwatch.games.FamilyGamesHost.create(this)
        }
    }
}
