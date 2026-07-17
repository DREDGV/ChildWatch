package ru.example.parentwatch.utils

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Tracks actual visible activities; a foreground service alone is not visible UI. */
object AppVisibilityTracker : Application.ActivityLifecycleCallbacks {
    private val registered = AtomicBoolean(false)
    private val startedActivities = AtomicInteger(0)

    fun register(application: Application) {
        if (registered.compareAndSet(false, true)) {
            application.registerActivityLifecycleCallbacks(this)
        }
    }

    fun isVisible(): Boolean = startedActivities.get() > 0

    override fun onActivityStarted(activity: Activity) {
        startedActivities.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
