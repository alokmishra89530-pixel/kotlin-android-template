package com.vashai.maxassistant

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 100, 50, 50)
        }

        val statusText = TextView(this).apply {
            text = "MAX AI Assistant is Ready"
            textSize = 20f
        }

        val startBtn = Button(this).apply {
            text = "Start MAX AI Service ('Bolo Max')"
            setOnClickListener {
                val serviceIntent = Intent(this@MainActivity, MaxVoiceService::class.java)
                startForegroundService(serviceIntent)
                statusText.text = "MAX AI Service Started!"
            }
        }

        val accessibilityBtn = Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }

        layout.addView(statusText)
        layout.addView(startBtn)
        layout.addView(accessibilityBtn)

        setContentView(layout)
    }
}
