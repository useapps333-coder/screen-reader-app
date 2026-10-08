package com.example.autoscreenreader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.Toast
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import java.util.Locale

/**
 * Auto Screen Reader: বহুভাষিক অ্যাক্সেসিবিলিটি সার্ভিস
 * বাংলা, ইংরেজি, আরবি, হিন্দি ইত্যাদি যেকোনো ভাষার লেখা শনাক্ত করে নিজ ভাষায় পড়ে শোনাবে।
 */
class ScreenReaderService : AccessibilityService(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private lateinit var languageIdentifier: LanguageIdentifier

    companion object {
        private const val TAG = "ScreenReaderService"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "ScreenReaderService সংযুক্ত হয়েছে")
        Toast.makeText(this, "অটো স্ক্রিন রিডার সার্ভিস সক্রিয় হয়েছে", Toast.LENGTH_SHORT).show()

        try {
            // Google ML Kit অন-ডিভাইস ল্যাঙ্গুয়েজ আইডেন্টিফায়ার ইনিশিয়ালাইজেশন
            languageIdentifier = LanguageIdentification.getClient()
        } catch (e: Throwable) {
            Log.w(TAG, "ML Kit ফলব্যাক মোড সক্রিয়: ${e.message}")
        }

        // টেক্সট-টু-স্পিচ ইঞ্জিন ইনিশিয়ালাইজেশন
        tts = TextToSpeech(this, this)

        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info

        // পারমিশন থাকলে ফ্লোটিং বাটন তৈরি করবে
        setupFloatingOverlay()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            // বাংলা ভাষা সাপোর্ট পরীক্ষা
            val banglaLocale = Locale("bn", "BD")
            val result = tts?.setLanguage(banglaLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }
            speakText("অটো স্ক্রিন রিডার প্রস্তুত আছে। স্ক্রিনের যেকোনো লেখায় ট্যাপ করুন।", TextToSpeech.QUEUE_FLUSH)
        } else {
            Log.e(TAG, "TTS ইনিশিয়ালাইজেশনে ত্রুটি")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
                var textToRead = ""

                // ১. সোর্স নোড থেকে লেখা সংগ্রহ (চিলড্রেন সহ)
                event.source?.let { node ->
                    textToRead = extractNodeText(node)
                    node.recycle()
                }

                // ২. নোডে না পেলে ইভেন্টের টেক্সট তালিকা থেকে সংগ্রহ
                if (textToRead.isBlank() && event.text.isNotEmpty()) {
                    textToRead = event.text.filterNotNull().joinToString(" ")
                }

                // ৩. কন্টেন্ট ডেসক্রিপশন চেক
                if (textToRead.isBlank() && !event.contentDescription.isNullOrEmpty()) {
                    textToRead = event.contentDescription.toString()
                }

                // তাৎক্ষণিক ভয়েস আউটপুট (কোনো বিলম্ব ছাড়া)
                if (textToRead.isNotBlank()) {
                    speakText(textToRead, TextToSpeech.QUEUE_FLUSH)
                }
            }
        }
    }

    /**
     * তাৎক্ষণিক বহুভাষিক ভয়েস স্পিচ (০ মিলি-সেকেন্ড বিলম্ব)
     */
    private fun speakText(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!isTtsReady || tts == null || text.isBlank()) return

        // অফলাইন ইনস্ট্যান্ট ভাষা ডিটেকশন (বাংলা, ইংরেজি, আরবি, হিন্দি)
        val targetLocale = detectScriptLocaleFast(text)
        setTtsLocaleSafely(targetLocale)

        tts?.speak(text, queueMode, null, "UTT_${System.currentTimeMillis()}")
    }

    private fun setTtsLocaleSafely(locale: Locale) {
        val check = tts?.isLanguageAvailable(locale)
        if (check != TextToSpeech.LANG_MISSING_DATA && check != TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.language = locale
        } else {
            tts?.language = Locale.getDefault()
        }
    }

    /**
     * অফলাইন দ্রুত স্ক্রিপ্ট ডিটেকশন (বাংলা, আরবি, দেবনাগরী, ইংরেজি)
     */
    private fun detectScriptLocaleFast(text: String): Locale {
        val hasBangla = text.any { it in 'ঀ'..'৿' }
        if (hasBangla) return Locale("bn", "BD")

        val hasArabic = text.any { it in '؀'..'ۿ' }
        if (hasArabic) return Locale("ar")

        val hasDevanagari = text.any { it in 'ऀ'..'ॿ' }
        if (hasDevanagari) return Locale("hi", "IN")

        return Locale.ENGLISH
    }

    /**
     * পুরো স্ক্রিনের সকল টেক্সট ক্রমান্বয়ে রিড করা
     */
    fun readEntireScreen() {
        if (tts?.isSpeaking == true) {
            tts?.stop()
            Toast.makeText(this, "পড়া বন্ধ করা হয়েছে", Toast.LENGTH_SHORT).show()
            return
        }

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Toast.makeText(this, "স্ক্রিনের কন্টেন্ট পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            return
        }

        val allTexts = mutableListOf<String>()
        collectAllTextsRecursive(rootNode, allTexts)

        if (allTexts.isNotEmpty()) {
            Toast.makeText(this, "পুরো স্ক্রিন পড়া হচ্ছে...", Toast.LENGTH_SHORT).show()
            speakText(allTexts.first(), TextToSpeech.QUEUE_FLUSH)
            for (i in 1 until allTexts.size) {
                speakText(allTexts[i], TextToSpeech.QUEUE_ADD)
            }
        } else {
            Toast.makeText(this, "স্ক্রিনে কোনো টেক্সট পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
        }
    }

    private fun collectAllTextsRecursive(node: AccessibilityNodeInfo, list: MutableList<String>) {
        val text = extractNodeText(node)
        if (text.isNotBlank() && !list.contains(text)) {
            list.add(text)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllTextsRecursive(child, list)
            child.recycle()
        }
    }

    private fun extractNodeText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()

        val contentDesc = node.contentDescription?.toString()
        val text = node.text?.toString()

        if (!contentDesc.isNullOrEmpty()) {
            sb.append(contentDesc)
        } else if (!text.isNullOrEmpty()) {
            sb.append(text)
        } else {
            // যদি প্যারেন্ট নোডে লেখা না থাকে, তবে চিলড্রেনদের ভেতর থেকে লেখা খুঁজবে
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val childText = extractNodeText(child)
                if (childText.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append(" ")
                    sb.append(childText)
                }
                child.recycle()
            }
        }

        if (node.isClickable && sb.isNotEmpty()) {
            val cls = node.className?.toString() ?: ""
            if (cls.contains("Button")) {
                sb.append(if (sb.any { it in 'ঀ'..'৿' }) ", বোতাম" else ", Button")
            } else if (cls.contains("Switch") || node.isCheckable) {
                val state = if (node.isChecked) "চালু" else "বন্ধ"
                sb.append(", সুইচ $state")
            }
        }
        return sb.toString().trim()
    }

    private fun setupFloatingOverlay() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Log.w(TAG, "ফ্লোটিং উইন্ডো পারমিশন এখনও দেওয়া হয়নি")
                return
            }
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 300
            }

            val button = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                setBackgroundColor(0xEE0D9488.toInt())
                setPadding(28, 28, 28, 28)
                contentDescription = "অটো স্ক্রিন রিডার বোতাম"
            }

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            button.setOnTouchListener { _, motionEvent ->
                when (motionEvent.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = motionEvent.rawX
                        initialTouchY = motionEvent.rawY
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (motionEvent.rawX - initialTouchX).toInt()
                        params.y = initialY + (motionEvent.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(floatingView, params)
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val diffX = kotlin.math.abs(motionEvent.rawX - initialTouchX)
                        val diffY = kotlin.math.abs(motionEvent.rawY - initialTouchY)
                        if (diffX < 15 && diffY < 15) {
                            readEntireScreen()
                        }
                        true
                    }
                    else -> false
                }
            }
            floatingView = button
            windowManager?.addView(floatingView, params)
        } catch (e: Exception) {
            Log.e(TAG, "ফ্লোটিং উইন্ডো ত্রুটি: ${e.message}")
        }
    }

    override fun onInterrupt() {
        tts?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        if (floatingView != null && windowManager != null) {
            windowManager?.removeView(floatingView)
        }
    }
}
