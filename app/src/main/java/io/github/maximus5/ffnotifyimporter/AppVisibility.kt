package io.github.maximus5.ffnotifyimporter

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Whether any activity of this app is on screen right now (started and not yet stopped).
 *
 * Used when a notification opens the app: `MainActivity` is recreated by that tap
 * (`FLAG_ACTIVITY_CLEAR_TOP`), and at the moment its `onCreate` runs the previous instance is
 * only paused, not stopped yet - so a non-zero count there means "the user was already looking at
 * the app", zero means "the app was in the background (or not running)". See
 * `MainActivity.openedWhileVisible`.
 */
object AppVisibility {

    @Volatile
    private var startedActivities = 0

    val isVisible: Boolean get() = startedActivities > 0

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivities > 0) startedActivities--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
