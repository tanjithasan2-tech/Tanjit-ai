package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

private const val TAG = "IncomingCallReceiver"

class IncomingCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        Log.d(TAG, "Phone state changed: $state")
        val hub = AssistantHub.getInstance(context.applicationContext)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                hub.onIncomingCallRinging(incomingNumber)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                hub.onCallStateEnded(wasOffhook = true)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                hub.onCallStateEnded(wasOffhook = false)
            }
        }
    }
}
