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

    fun sendToDatabase(
        context: Context,
        source: String,
        message: String,
        callback: ((String) -> Unit)? = null
    ) {
        val prefs = context.getSharedPreferences("UpiRelayPrefs", Context.MODE_PRIVATE)
        val webhookUrl = prefs.getString("webhook_url", "")?.trim() ?: ""
        val secretKey = prefs.getString("secret_key", "")?.trim() ?: ""

        if (webhookUrl.isEmpty()) {
            callback?.invoke("Error: Webhook URL is empty!")
            return
        }

        thread {
            try {
                val url = URL(webhookUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true

                val json = JSONObject().apply {
                    put("secret", secretKey)
                    put("source", source)
                    put("message", message)
                }

                OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }
                val responseCode = conn.responseCode
                conn.disconnect()

                callback?.invoke("Server responded with HTTP $responseCode")
            } catch (e: Exception) {
                e.printStackTrace()
                callback?.invoke("Failed: ${e.localizedMessage}")
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
        "com.freecharge.android",                 // Freecharge
        "in.org.npci.upiapp"                      // BHIM
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val combinedText = "$title $text"

        if (pkg in targetApps || combinedText.contains("credited", ignoreCase = true) || combinedText.contains("received", ignoreCase = true)) {
            WebhookClient.sendToDatabase(applicationContext, pkg, combinedText)
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
                WebhookClient.sendToDatabase(context.applicationContext, "SMS:$sender", fullSms)
            }
        }
    }
}
