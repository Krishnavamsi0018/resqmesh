package com.resqmesh.app.mesh

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.resqmesh.app.R
import com.resqmesh.app.graph
import com.resqmesh.app.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * Keeps relaying alive with the screen off and the app in the background (F-20, F-25) by running
 * Nearby Connections inside a connectedDevice foreground service with an ongoing notification.
 */
class MeshService : LifecycleService() {
    private var relaying = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (!goForeground()) {
            stopSelf()
            return
        }
        relaying = true
        graph.applyChainMode()
        graph.transport.start()
        graph.gateway.start()
        graph.sms.start()

        lifecycleScope.launch {
            graph.state.peers.collect { peers -> updateNotification(peers.count { it.linked }) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP || !relaying) {
            stopSelf()
            return START_NOT_STICKY
        }
        // If Android kills the process for memory, bring relaying back when it can.
        return START_STICKY
    }

    override fun onDestroy() {
        graph.gateway.stop()
        graph.sms.stop()
        graph.transport.stop()
        super.onDestroy()
    }

    /**
     * Location type too, because Nearby's discovery needs location access, and with the screen off
     * Android grants it only to a location-type foreground service (the week-2 status 8034 failure).
     * If Android refuses that (no location permission, or started from the background), fall back
     * to connected-device only: advertising and existing links still work.
     */
    private fun goForeground(): Boolean {
        val notification = notification(linked = 0)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return true
        }
        val connected = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val withLocation = connected or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        for (types in listOf(withLocation, connected)) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
                if (types == connected) {
                    graph.state.log(LogKind.ERROR, "Relaying without location access: open the app so phones can be found")
                }
                return true
            } catch (e: SecurityException) {
                Log.w(TAG, "Foreground service types $types refused", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Foreground service types $types refused", e)
            }
        }
        graph.state.log(LogKind.ERROR, "Android refused to start relaying in the background: open the app and tap Start")
        return false
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_mesh), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.notification_channel_mesh_description)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(linked: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MeshService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (linked == 0) {
            getString(R.string.notification_relaying_none)
        } else {
            resources.getQuantityString(R.plurals.notification_relaying_some, linked, linked)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_relaying_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_action_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(linked: Int) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(linked))
    }

    companion object {
        private const val TAG = "MeshService"
        private const val CHANNEL_ID = "mesh"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.resqmesh.app.action.STOP_MESH"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshService::class.java))
        }
    }
}
