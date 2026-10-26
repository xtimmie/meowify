package com.xtim.meowify

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.chaquo.python.android.AndroidPlatform
import com.chaquo.python.Python

class MeowifyApp : Application() {

    companion object {
        const val CHANNEL_SERVER = "meowify_server"
        const val CHANNEL_DOWNLOAD = "meowify_download"
    }

    override fun onCreate() {
        super.onCreate()

        // only the default process needs the chaquopy runtime — the webview
        // process (see android:process=":webview" on MainActivity) doesn't
        // touch python at all, so skip starting a second interpreter there
        val processName = getProcessNameCompat()
        val isDefaultProcess = processName == null || processName == packageName

        if (isDefaultProcess) {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
            createNotificationChannels()
        }
    }

    private fun getProcessNameCompat(): String? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            try {
                val am = getSystemService(android.app.ActivityManager::class.java)
                val pid = android.os.Process.myPid()
                am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // persistent foreground service channel
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVER,
                "meowify server",
                NotificationManager.IMPORTANCE_LOW   // silent, no sound
            ).apply {
                description = "keeps meowify running in the background"
                setShowBadge(false)
            }
        )

        // download progress channel (can be dismissed)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOAD,
                "downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "shows active download progress"
                setShowBadge(true)
            }
        )
    }
}
