package ru.example.parentwatch

import android.app.Application
import ru.example.parentwatch.utils.AppVisibilityTracker

class ChildWatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppVisibilityTracker.register(this)
    }
}
