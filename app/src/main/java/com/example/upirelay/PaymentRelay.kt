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
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
                
                // Construct URL with query parameters so the server handles it instantly via GET/POST
                val finalUrl = "$baseUrl?secret=$secretKey&source=$encodedSrc&message=$encodedMsg"

                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = false
                }

                // Enable cookie persistence so Unaux aes.js validation cookies are saved
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Extract the page body JSON response
                        view?.evaluateJavascript("document.body.innerText") { response ->
                            val cleanResponse = response?.trim('"')?.replace("\\\\", "") ?: "No response body"
                            val statusMsg = "WebView OK | $cleanResponse\nMsg: $message"
                            appendLog(context, "[$source] $statusMsg")
                            callback?.invoke(statusMsg)
                            
                            // Clean up WebView after a brief delay
                            Handler(Looper.getMainLooper()).postDelayed({
                                try { webView.destroy() } catch (e: Exception) {}
                            }, 1000)
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        val errCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) error?.errorCode else -1
                        val failMsg = "WebView Error Code: $errCode\nMsg: $message"
                        appendLog(context, "[$source] $failMsg")
                        callback?.invoke(failMsg)
                    }
                }

                // Load the URL to bypass aes.js and trigger webhook.php
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
