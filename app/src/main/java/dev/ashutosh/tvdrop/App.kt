package dev.ashutosh.tvdrop

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Downloads.init(this)
        // Lives as long as the process so the phone can push files while the activity is in the background.
        Server(this).start()
    }
}
