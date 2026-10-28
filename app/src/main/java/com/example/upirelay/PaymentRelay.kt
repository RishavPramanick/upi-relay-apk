package com.example.upirelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object WebhookClient {
    // Replace with your live server URL and secret token
    private const val WEBHOOK_URL = "https://yourwebsite.com/webhook.php"
    private const val SECRET_KEY = "MY_SUPER_SECRET_TOKEN_123"

    fun sendToDatabase(source: String, message: String) {
        thread {
            try {
                val url = URL(WEBHOOK_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true

                val json = JSONObject().apply {
                    put("secret", SECRET_KEY)
                    put("source", source)
                    put("message", message)
                }

                OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }
                conn.responseCode // Triggers the request
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

// 1. Intercepts Google Pay, PhonePe, Paytm, and Bank App Notifications
class UpiNotificationListener : NotificationListenerService() {
    private val targetApps = setOf(
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "com.phonepe.app",                        // PhonePe
        "net.one97.paytm",                        // Paytm
        "com.freecharge.android"                  // Freecharge
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val combinedText = "$title $text"

        // Forward if it comes from a UPI app OR contains ₹ / Rs credit keywords
        if (pkg in targetApps || combinedText.contains("credited", ignoreCase = true)) {
            WebhookClient.sendToDatabase(pkg, combinedText)
        }
    }
}

// 2. Intercepts Standard Bank SMS Messages
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val fullSms = messages.joinToString(separator = "") { it.messageBody }
            val sender = messages.firstOrNull()?.originatingAddress ?: "SMS"

            if (fullSms.contains("credited", ignoreCase = true) || fullSms.contains("UPI", ignoreCase = true)) {
                WebhookClient.sendToDatabase("SMS:$sender", fullSms)
            }
        }
    }
}
