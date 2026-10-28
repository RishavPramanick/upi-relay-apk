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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

object WebhookClient {

    private fun appendLog(context: Context, entry: String) {
        val prefs = context.getSharedPreferences("UpiRelayPrefs", Context.MODE_PRIVATE)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val existing = prefs.getString("activity_logs", "") ?: ""
        val lines = existing.split("\n---\n").take(9) // Keep last 10 logs
        val updated = "[$time] $entry" + if (existing.isNotEmpty()) "\n---\n" + lines.joinToString("\n---\n") else ""
        prefs.edit().putString("activity_logs", updated).apply()
    }

    fun sendToDatabase(
        context: Context,
        source: String,
        message: String,
        callback: ((String) -> Unit)? = null
    ) {
        val prefs = context.getSharedPreferences("UpiRelayPrefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("webhook_url", "")?.trim() ?: ""
        val secretKey = prefs.getString("secret_key", "")?.trim() ?: ""

        if (baseUrl.isEmpty()) {
            val err = "Error: Webhook URL is empty!"
            appendLog(context, "$source -> $err")
            callback?.invoke(err)
            return
        }

        // Append secret key as a URL query parameter to bypass free hosting firewalls
        val finalUrlString = if (baseUrl.contains("?")) "$baseUrl&secret=$secretKey" else "$baseUrl?secret=$secretKey"

        thread {
            try {
                val url = URL(finalUrlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true

                val json = JSONObject().apply {
                    put("source", source)
                    put("message", message)
                }

                OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }
                val responseCode = conn.responseCode
                val responseBody = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    "No response body"
                }
                conn.disconnect()

                val statusMsg = "HTTP $responseCode | $responseBody"
                appendLog(context, "[$source] $statusMsg")
                callback?.invoke(statusMsg)
            } catch (e: Exception) {
                e.printStackTrace()
                val failMsg = "Failed: ${e.localizedMessage}"
                appendLog(context, "[$source] $failMsg")
                callback?.invoke(failMsg)
            }
        }
    }
}

// 1. Intercepts Google Pay, PhonePe, Paytm, BHIM, and Bank App Notifications
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
        val combinedText = "$title $text".trim()

        if (combinedText.isEmpty()) return

        if (pkg in targetApps ||
            combinedText.contains("credited", ignoreCase = true) ||
            combinedText.contains("received", ignoreCase = true) ||
            combinedText.contains("paid you", ignoreCase = true)
        ) {
            WebhookClient.sendToDatabase(applicationContext, "NOTIF:$pkg", combinedText)
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

            if (fullSms.contains("credited", ignoreCase = true) ||
                fullSms.contains("received", ignoreCase = true) ||
                fullSms.contains("UPI", ignoreCase = true)
            ) {
                WebhookClient.sendToDatabase(context.applicationContext, "SMS:$sender", fullSms)
            }
        }
    }
}
