// File: app/src/main/java/com/vashai/maxassistant/MaxVoiceService.kt
package com.vashai.maxassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MaxVoiceService : Service(), TextToSpeech.OnInitListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var controller: MaxSecurityAndSystemController
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private val httpClient = OkHttpClient()

    // आपकी Groq API Key यहाँ है
    private val groqApiKey = "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"

    private var isVoiceUnlocked = true
    private val voicePassphrase = "अनलॉक मैक्स"

    override fun onCreate() {
        super.onCreate()
        controller = MaxSecurityAndSystemController(this)
        tts = TextToSpeech(this, this)
        startForegroundNotification()
        initContinuousWakeWordListener()
    }

    private fun initContinuousWakeWordListener() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val spokenText = matches[0].lowercase(Locale.getDefault())
                    if (spokenText.contains("bolo max") || spokenText.contains("बोलो मैक्स") || spokenText.contains("max")) {
                        val command = spokenText
                            .replace("bolo max", "")
                            .replace("बोलो मैक्स", "")
                            .trim()
                        processUserCommand(command.ifEmpty { "मैं सुन रहा हूँ, बताइए क्या करना है?" })
                    }
                }
                restartListening(recognizerIntent)
            }

            override fun onError(error: Int) {
                restartListening(recognizerIntent)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer.startListening(recognizerIntent)
    }

    private fun restartListening(intent: Intent) {
        speechRecognizer.cancel()
        speechRecognizer.startListening(intent)
    }

    private fun processUserCommand(userPrompt: String) {
        if (!isVoiceUnlocked) {
            if (userPrompt.contains(voicePassphrase)) {
                isVoiceUnlocked = true
                speakOut("वॉइस ऑथेंटिकेशन सफल। मैक्स अनलॉक हो गया है।")
            } else {
                speakOut("मैक्स वॉइस लॉक सक्रिय है। कृपया अपना पासफ्रेज़ बोलें।")
            }
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val jsonBody = JSONObject().apply {
                    put("model", "llama3-70b-8192")
                    put("messages", JSONArray().put(
                        JSONObject().put("role", "system").put("content", "You are MAX AI, an autonomous Android voice assistant. Reply in short Hindi/Hinglish sentences.")
                    ).put(
                        JSONObject().put("role", "user").put("content", userPrompt)
                    ))
                }

                val body = jsonBody.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $groqApiKey")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseStr = response.body?.string()
                    if (responseStr != null) {
                        val jsonResp = JSONObject(responseStr)
                        val aiReply = jsonResp.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")

                        handleCommandActions(userPrompt, aiReply)
                    }
                }
            } catch (e: Exception) {
                speakOut("कनेक्शन में दिक्कत आई है, कृपया दोबारा बोलें।")
            }
        }
    }

    private fun handleCommandActions(prompt: String, aiReply: String) {
        val lowerPrompt = prompt.lowercase()
        when {
            lowerPrompt.contains("torch") || lowerPrompt.contains("flashlight") || lowerPrompt.contains("लाइट") -> {
                val res = controller.controlSystemSetting("FLASHLIGHT", if (lowerPrompt.contains("off") || lowerPrompt.contains("बंद")) 0 else 1)
                speakOut(res)
            }
            lowerPrompt.contains("whatsapp") -> {
                speakOut("व्हाट्सएप चैट खोली जा रही है।")
                controller.sendWhatsAppMessage("919876543210", "Hello from MAX AI")
            }
            lowerPrompt.contains("scroll") -> {
                val acc = MaxAccessibilityService.instance
                if (acc != null) {
                    acc.performSmartScroll(if (lowerPrompt.contains("up") || lowerPrompt.contains("ऊपर")) "UP" else "DOWN")
                    speakOut("स्क्रीन स्क्रॉल कर दी गई है।")
                } else {
                    speakOut("कृपया एक्सेसिबिलिटी सर्विस ऑन करें।")
                }
            }
            else -> {
                speakOut(aiReply)
            }
        }
    }

    private fun speakOut(message: String) {
        serviceScope.launch(Dispatchers.Main) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "MAX_TTS_ID")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("hi", "IN")
            tts.setSpeechRate(1.05f)
        }
    }

    private fun startForegroundNotification() {
        val channelId = "MAX_VOICE_CHANNEL"
        val channel = NotificationChannel(channelId, "MAX AI Autonomous Engine", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("MAX AI सक्रिय है ('Bolo Max')")
            .setContentText("Groq Llama 3 Autonomous Engine Running")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(101, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
