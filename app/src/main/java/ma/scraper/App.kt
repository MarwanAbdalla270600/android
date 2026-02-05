package ma.scraper

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppVisibility.init(this)
    }
}
