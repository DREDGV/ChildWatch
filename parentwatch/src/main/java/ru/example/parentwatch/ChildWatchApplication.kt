package ru.example.parentwatch

import android.app.Application
import com.vanniktech.emoji.EmojiManager
import com.vanniktech.emoji.google.GoogleEmojiProvider
import ru.example.parentwatch.utils.AppVisibilityTracker

class ChildWatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        EmojiManager.install(GoogleEmojiProvider())
        AppVisibilityTracker.register(this)
    }
}
