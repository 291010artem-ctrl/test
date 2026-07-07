package com.artem.cameracompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import org.json.JSONObject

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } else {
            @Suppress("DEPRECATION")
            val pdus = intent.extras?.get("pdus") as? Array<*> ?: return
            pdus.mapNotNull {
                android.telephony.SmsMessage.createFromPdu(it as ByteArray)
            }.toTypedArray()
        }
        for (msg in messages) {
            StreamingService.pushIncomingSms(
                address = msg.originatingAddress ?: "",
                body    = msg.messageBody ?: "",
                date    = System.currentTimeMillis()
            )
        }
    }
}
