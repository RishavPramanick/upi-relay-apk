package com.example.upirelay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

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
            setPadding(60, 80, 60, 80)
        }

        val titleText = TextView(this).apply {
            text = "UPI Gateway Relay Settings"
            textSize = 20f
            setPadding(0, 0, 0, 40)
        }

        val urlLabel = TextView(this).apply { text = "Webhook URL (webhook.php):" }
        val urlInput = EditText(this).apply {
            hint = "https://yourwebsite.com/webhook.php"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("webhook_url", ""))
        }

        val secretLabel = TextView(this).apply {
            text = "Secret Key:"
            setPadding(0, 30, 0, 0)
        }
        val secretInput = EditText(this).apply {
            hint = "MY_SUPER_SECRET_TOKEN_123"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.getString("secret_key", ""))
        }

        val statusText = TextView(this).apply {
            text = "Status: Ready"
            setPadding(0, 40, 0, 20)
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

                statusText.text = "Status: Settings Saved Successfully!"
                Toast.makeText(this@MainActivity, "Saved!", Toast.LENGTH_SHORT).show()
            }
        }

        // Test Webhook Button (Simulates a Rs. 1.00 credit alert)
        val testBtn = Button(this).apply {
            text = "Send Test Payment Alert (₹1.00)"
            setOnClickListener {
                statusText.text = "Status: Sending test payload..."
                WebhookClient.sendToDatabase(
                    applicationContext,
                    "TEST_BUTTON",
                    "Test Alert: Rs. 1.00 credited to your account via UPI Ref 123456789012."
                ) { resultMsg ->
                    runOnUiThread {
                        statusText.text = "Status: $resultMsg"
                    }
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

        // Add all elements to the screen
        layout.apply {
            addView(titleText)
            addView(urlLabel)
            addView(urlInput)
            addView(secretLabel)
            addView(secretInput)
            addView(saveBtn)
            addView(testBtn)
            addView(notifBtn)
            addView(statusText)
        }

        scrollView.addView(layout)
        setContentView(scrollView)
    }
}
