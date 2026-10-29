package com.example.upirelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

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

        // Run WebView operations strictly on the Main Thread
        Handler(Looper.getMainLooper()).post {
            try {
                val encodedMsg = URLEncoder.encode(message, "UTF-8")
                val encodedSrc = URLEncoder.encode(source, "UTF-8")
                
                val finalUrl = "$baseUrl?secret=$secretKey&source=$encodedSrc&message=$encodedMsg"

                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = false
                }

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript("document.body.innerText") { response ->
                            val cleanResponse = response?.trim('"')?.replace("\\\\", "") ?: "No response body"
                            val statusMsg = "WebView OK | $cleanResponse\nMsg: $message"
                            appendLog(context, "[$source] $statusMsg")
                            callback?.invoke(statusMsg)
                            
                            Handler(Looper.getMainLooper()).postDelayed({
                                try { webView.destroy() } catch (e: Exception) {}
                            }, 1000)
                        }
                    }
                }

                webView.loadUrl(finalUrl)

            } catch (e: Exception) {
                e.printStackTrace()
                val failMsg = "WebView Failed: ${e.localizedMessage}\nMsg: $message"
                appendLog(context, "[$source] $failMsg")
                callback?.invoke(failMsg)
            }
        }
    }
}

// RESTRICTED EXCLUSIVELY TO GOOGLE PAY NOTIFICATIONS
class UpiNotificationListener : NotificationListenerService() {
    private val googlePayPkg = "com.google.android.apps.nbu.paisa.user"

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        
        // Ignore any notification that does NOT come from Google Pay
        if (pkg != googlePayPkg) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val combinedText = "$title $text".trim()

        if (combinedText.isEmpty()) return

        if (combinedText.contains("credited", ignoreCase = true) ||
            combinedText.contains("received", ignoreCase = true) ||
            combinedText.contains("paid you", ignoreCase = true)
        ) {
            WebhookClient.sendToDatabase(applicationContext, "GPAY:$pkg", combinedText)
        }
    }
}

// Standard SMS Receiver (Kept active for direct bank SMS alerts if needed)
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
