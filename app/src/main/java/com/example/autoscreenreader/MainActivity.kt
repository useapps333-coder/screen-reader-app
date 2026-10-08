package com.example.autoscreenreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tvStatus: TextView
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnRestrictedFix: Button
    private lateinit var btnOverlayPermission: Button
    private lateinit var btnTestTts: Button
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_service_status)
        btnEnableAccessibility = findViewById(R.id.btn_enable_service)
        btnRestrictedFix = findViewById(R.id.btn_restricted_fix)
        btnOverlayPermission = findViewById(R.id.btn_overlay_permission)
        btnTestTts = findViewById(R.id.btn_test_tts)

        tts = TextToSpeech(this, this)

        // ১. অ্যাক্সেসিবিলিটি সেটিংসে নিয়ে যাবে
        btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "তালিকা থেকে 'Auto Screen Reader' খুঁজে চালু করুন", Toast.LENGTH_LONG).show()
        }

        // ২. অ্যান্ড্রয়েড ১৩ ও ১৪ এর রেস্ট্রিক্টেড সেটিং আনলক
        btnRestrictedFix.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
            Toast.makeText(this, "উপরে ডানদিকের ৩টি ডটে (⋮) চাপ দিয়ে 'Allow restricted settings' চাপুন", Toast.LENGTH_LONG).show()
        }

        // ৩. অন্যান্য অ্যাপের ওপর ফ্লোটিং বাটন পারমিশন
        btnOverlayPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "ফ্লোটিং উইন্ডো পারমিশন ইতিমধ্যে মঞ্জুর করা আছে", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // ৪. স্পিচ টেস্ট
        btnTestTts.setOnClickListener {
            tts?.speak("অটো স্ক্রিন রিডার সফলভাবে কাজ করছে!", TextToSpeech.QUEUE_FLUSH, null, "test")
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val isEnabled = isAccessibilityServiceEnabled(this, ScreenReaderService::class.java)
        if (isEnabled) {
            tvStatus.text = "অবস্থা: সার্ভিস সক্রিয় আছে (Active)"
            tvStatus.setTextColor(0xFF10B981.toInt()) // Green
            btnEnableAccessibility.text = "অ্যাক্সেসিবিলিটি সেটিংস পরিবর্তন করুন"
        } else {
            tvStatus.text = "অবস্থা: সার্ভিস নিষ্ক্রিয় আছে (Disabled)"
            tvStatus.setTextColor(0xFFEF4444.toInt()) // Red
            btnEnableAccessibility.text = "অ্যাক্সেসিবিলিটি সার্ভিস চালু করুন"
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val expectedComponentName = "${context.packageName}/${service.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(":").any { it.equals(expectedComponentName, ignoreCase = true) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale("bn", "BD"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
    }
}