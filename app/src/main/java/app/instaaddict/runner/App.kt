package app.instaaddict.runner

import android.app.Application
import app.instaaddict.runner.adb.AdbLink
import app.instaaddict.runner.bot.Engine
import app.instaaddict.runner.core.AppLog
import app.instaaddict.runner.core.MetricsStore
import app.instaaddict.runner.core.Prefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(filesDir)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            AppLog.recordCrash(t, e)
            previous?.uncaughtException(t, e)
        }
        Prefs.init(this)
        MetricsStore.init(filesDir)
        try {
            AdbLink.init(this)
        } catch (e: Throwable) {
            AppLog.e("App", "Could not prepare the phone link", e)
        }
        Engine.init(this)
        AppLog.i("App", "InstaAddict started (Android ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL})")
    }
}
