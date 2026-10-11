package com.jingyue.mimotts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

private const val TAG = "MiMoKeepAliveService"
private const val CHANNEL_ID = "mimo_tts_background"
private const val NOTIFICATION_ID = 1001

/** Keeps the MiMo package in a foreground process so the system TTS binder can connect reliably. */
class MiMoKeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("MiMo TTS 后台服务运行中")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: SecurityException) {
            Log.e(TAG, "Unable to promote keep-alive service to foreground", error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MiMo TTS 后台运行",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持 MiMo TTS 可被系统和阅读器连接"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
