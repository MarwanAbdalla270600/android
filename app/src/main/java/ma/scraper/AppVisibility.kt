package ma.scraper

import android.app.Activity
import android.app.Application
import android.os.Bundle

object AppVisibility {
    @Volatile var isForeground: Boolean = false
        private set

    fun init(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started++
                isForeground = started > 0
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                isForeground = started > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
