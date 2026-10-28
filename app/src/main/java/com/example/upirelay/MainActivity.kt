package com.example.upirelay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request SMS Permissions on launch
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS),
                101
            )
        }

        val prefs = getSharedPreferences("UpiRelayPrefs", Context.MODE_PRIVATE)

        // Build UI Programmatically
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 60, 50, 60)
        }

        val titleText = TextView(this).apply {
            text = "UPI Gateway Relay"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 30)
        }

        val urlLabel = TextView(this).apply { text = "Webhook URL (webhook.php):" }
        val urlInput = EditText(this).apply {
            hint = "https://yourwebsite.com/webhook.php"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("webhook_url", ""))
        }

        val secretLabel = TextView(this).apply {
            text = "Secret Key:"
            setPadding(0, 20, 0, 0)
        }
        val secretInput = EditText(this).apply {
            hint = "MY_SUPER_SECRET_TOKEN_123"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.getString("secret_key", ""))
        }

        // Save Settings Button
        val saveBtn = Button(this).apply {
            text = "Save Settings"
            setOnClickListener {
                val url = urlInput.text.toString().trim()
                val secret = secretInput.text.toString().trim()

                prefs.edit()
                    .putString("webhook_url", url)
                    .putString("secret_key", secret)
                    .apply()

                Toast.makeText(this@MainActivity, "Settings Saved!", Toast.LENGTH_SHORT).show()
                refreshLogs()
            }
        }

        // Test Webhook Button (Simulates a Rs. 1.00 credit alert)
        val testBtn = Button(this).apply {
            text = "Send Test Payment Alert (₹1.00)"
            setOnClickListener {
                WebhookClient.sendToDatabase(
                    applicationContext,
                    "TEST_BUTTON",
                    "Test Alert: Rs. 1.00 credited to your account via UPI Ref 123456789012."
                ) {
                    runOnUiThread { refreshLogs() }
                }
            }
        }

        // Notification Listener Permission Button
        val notifBtn = Button(this).apply {
            text = "Open Notification Access Settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        // Refresh & Clear Log Buttons
        val refreshBtn = Button(this).apply {
            text = "Refresh Activity Logs"
            setOnClickListener { refreshLogs() }
        }

        val clearBtn = Button(this).apply {
            text = "Clear Logs"
            setOnClickListener {
                prefs.edit().putString("activity_logs", "Logs cleared.").apply()
                refreshLogs()
            }
        }

        val logHeader = TextView(this).apply {
            text = "Recent Intercepted Alerts & Server Responses:"
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 40, 0, 10)
        }

        logTextView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#F3F4F6"))
            setTextColor(Color.parseColor("#111827"))
            setPadding(24, 24, 24, 24)
        }

        layout.apply {
            addView(titleText)
            addView(urlLabel)
            addView(urlInput)
            addView(secretLabel)
            addView(secretInput)
            addView(saveBtn)
            addView(testBtn)
            addView(notifBtn)
            addView(refreshBtn)
            addView(clearBtn)
            addView(logHeader)
            addView(logTextView)
        }

        scrollView.addView(layout)
        setContentView(scrollView)
        refreshLogs()
    }

    override fun onResume() {
        super.onResume()
        refreshLogs()
    }

    private fun refreshLogs() {
        val prefs = getSharedPreferences("UpiRelayPrefs", Context.MODE_PRIVATE)
        logTextView.text = prefs.getString("activity_logs", "No alerts captured yet.")
    }
}
