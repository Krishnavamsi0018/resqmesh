package com.resqmesh.app.gateway

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SmsManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.resqmesh.app.mesh.LogKind
import com.resqmesh.app.mesh.MeshNode
import com.resqmesh.app.mesh.MeshState
import com.resqmesh.app.settings.AppSettings
import com.resqmesh.app.settings.SmsPolicy
import com.resqmesh.mesh.gateway.SmsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Texts SOS to the rescue team whenever this phone has cellular service, with or without data
 * (F-41, TRD 2.7). [MeshNode] decides which SOS and when; this class checks that sending is
 * possible, sends through SmsManager, and reports each result back. Runs while relaying is on.
 * Main thread.
 */
class SmsGateway(
    private val context: Context,
    private val node: MeshNode,
    private val state: MeshState,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private var loop: Job? = null
    private var service = false
    private var wasReady = false
    /** SOS whose SMS is out, by ID, with when it went: Android may never report back. */
    private val sending = HashMap<String, Long>()

    private val results = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getStringExtra(EXTRA_SOS_ID) ?: return
            if (sending.remove(id) == null) return
            val sent = resultCode == Activity.RESULT_OK
            if (sent) {
                state.updateSms { it.copy(sent = it.sent + 1, lastSentAt = System.currentTimeMillis(), lastError = null) }
            } else {
                state.updateSms { it.copy(lastError = describe(resultCode)) }
            }
            node.onSmsResult(id, sent)
        }
    }

    private var serviceListener: Any? = null

    fun start() {
        if (loop != null) return
        ContextCompat.registerReceiver(context, results, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        listenForService()
        loop = scope.launch {
            while (true) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        runCatching { context.unregisterReceiver(results) }
        stopListening()
        wasReady = false
        // Results that arrive after this would be lost; let these SOS be tried again next time.
        sending.keys.toList().forEach { node.onSmsResult(it, sent = false) }
        sending.clear()
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        // No report from Android after a while: count it as failed, so the SOS is tried again.
        sending.filterValues { now - it > RESULT_TIMEOUT_MS }.keys.forEach { id ->
            sending.remove(id)
            state.updateSms { it.copy(lastError = "no report from the phone") }
            node.onSmsResult(id, sent = false)
        }
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val number = settings.smsNumber.value
        state.updateSms {
            it.copy(
                permission = permission,
                enabled = settings.smsEnabled.value,
                numberAllowed = SmsPolicy.isAllowed(number),
                service = service,
                waiting = node.smsWaiting,
            )
        }
        val ready = state.sms.value.ready
        if (ready && !wasReady) node.onSmsPossible() // fresh random waits, so phones don't all send at once
        wasReady = ready
        if (!ready) return
        for (job in node.smsDue()) send(job.id, SmsText.format(job.sos, job.hops, ZoneId.systemDefault()), number)
    }

    private fun send(sosId: String, text: String, number: String) {
        val intent = Intent(ACTION_SENT).setPackage(context.packageName).putExtra(EXTRA_SOS_ID, sosId)
        val sent = PendingIntent.getBroadcast(
            context,
            sosId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            smsManager().sendTextMessage(number, null, text, sent, null)
            sending[sosId] = System.currentTimeMillis()
            state.log(LogKind.SENT, "Texting SOS ${sosId.take(4)} to the rescue team (${text.length} characters)")
        } catch (e: Exception) {
            // SecurityException if the permission was just revoked; IllegalArgumentException for a bad number.
            state.updateSms { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
            node.onSmsResult(sosId, sent = false)
        }
    }

    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    private fun onServiceState(serviceState: ServiceState) {
        val now = serviceState.state == ServiceState.STATE_IN_SERVICE
        if (now != service) state.log(LogKind.LINK, if (now) "Mobile signal: can text the rescue team" else "No mobile signal: can't text")
        service = now
    }

    private fun listenForService() {
        try {
            register()
        } catch (e: SecurityException) {
            // Some phones want more permissions to watch the signal: just try, and let the send report fail.
            state.log(LogKind.ERROR, "Can't watch mobile signal (${e.message}); will try to text anyway")
            service = true
        }
    }

    private fun register() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = ServiceCallback(::onServiceState)
            telephony.registerTelephonyCallback(ContextCompat.getMainExecutor(context), callback)
            serviceListener = callback
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onServiceStateChanged(serviceState: ServiceState) = onServiceState(serviceState)
            }
            @Suppress("DEPRECATION")
            telephony.listen(listener, PhoneStateListener.LISTEN_SERVICE_STATE)
            serviceListener = listener
        }
    }

    private fun stopListening() {
        when (val listener = serviceListener) {
            null -> return
            is PhoneStateListener -> @Suppress("DEPRECATION") telephony.listen(listener, PhoneStateListener.LISTEN_NONE)
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) telephony.unregisterTelephonyCallback(listener as TelephonyCallback)
        }
        serviceListener = null
        service = false
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class ServiceCallback(val onChange: (ServiceState) -> Unit) : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
        override fun onServiceStateChanged(serviceState: ServiceState) = onChange(serviceState)
    }

    private companion object {
        const val ACTION_SENT = "com.resqmesh.app.SMS_SENT"
        const val EXTRA_SOS_ID = "sos_id"
        const val TICK_MS = 1_000L
        const val RESULT_TIMEOUT_MS = 90_000L

        fun describe(code: Int): String = when (code) {
            SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
            SmsManager.RESULT_ERROR_NULL_PDU -> "message error"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "send failed (balance or network?)"
            else -> "error $code"
        }
    }
}
