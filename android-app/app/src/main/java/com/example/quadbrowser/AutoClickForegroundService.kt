package com.example.quadbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat

class AutoClickForegroundService : Service() {
      private var showAutoClickStatus = false
      private var floatingBubble: FloatingBubbleOverlay? = null
      private val keepAliveHandler = android.os.Handler(android.os.Looper.getMainLooper())
      // Foreground WebViews do not need periodic timer nudges. When the
      // app is backgrounded, a slower heartbeat still refreshes timers while
      // avoiding unnecessary main-thread wakeups.
      private val keepAliveIntervalMs = 15_000L
      private val keepAliveRunnable = object : Runnable {
          override fun run() {
              MainActivity.keepBackgroundWebViewsAlive()
              keepAliveHandler.postDelayed(this, keepAliveIntervalMs)
          }
      }
        companion object {
        const val ACTION_START = "com.example.quadbrowser.action.START_AUTO_CLICK"
        const val ACTION_START_BROWSER = "com.example.quadbrowser.action.START_BROWSER"
        const val ACTION_STOP = "com.example.quadbrowser.action.STOP_AUTO_CLICK"
          const val EXTRA_SHOW_BUBBLE = "show_floating_bubble"
    
        private const val CHANNEL_ID = "auto_clicker_background"
        private const val NOTIFICATION_ID = 1001

    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.auto_clicker_background_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.auto_clicker_background_channel_description)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        keepAliveHandler.post(keepAliveRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> showAutoClickStatus = true
            ACTION_START_BROWSER -> showAutoClickStatus = false
        }

        val notification = buildNotification(showAutoClickStatus)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (intent?.getBooleanExtra(EXTRA_SHOW_BUBBLE, false) == true) showFloatingBubble()
          return START_STICKY
      }

      private fun showFloatingBubble() {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
          if (floatingBubble == null) {
              floatingBubble = FloatingBubbleOverlay(this) {
                  hideFloatingBubble()
                  val openAppIntent = Intent(this, MainActivity::class.java).apply {
                      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                  }
                  startActivity(openAppIntent)
                  if (!showAutoClickStatus) stopSelf()
              }
          }
          floatingBubble?.show()
      }

      private fun hideFloatingBubble() {
          floatingBubble?.hide()
      }

      override fun onDestroy() {
          keepAliveHandler.removeCallbacks(keepAliveRunnable)
          hideFloatingBubble()
          floatingBubble = null
          super.onDestroy()
      }

      private fun buildNotification(showAutoClickStatus: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_auto_click)
            .setContentTitle(getString(if (showAutoClickStatus) R.string.auto_clicker_background_title else R.string.browser_background_title))
            .setContentText(getString(if (showAutoClickStatus) R.string.auto_clicker_background_message else R.string.browser_background_message))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}