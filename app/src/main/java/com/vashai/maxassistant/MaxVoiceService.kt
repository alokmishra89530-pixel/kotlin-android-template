package com.vashai.maxassistant

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import android.util.Log
import java.util.Locale

class MaxVoiceService : Service() {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechIntent: Intent
    private val TAG = "MaxVoiceService"
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MaxVoiceService Created")

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                Log.d(TAG, "Ready for speech")
            }
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech started")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
            }
            override fun onError(error: Int) {
                Log.d(TAG, "Error code: $error")
                // Thoda ruk kar dubara listen shuru karein taaki app crash na ho
                handler.postDelayed({ restartListening() }, 2000)
            }
            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null) {
                    for (result in matches) {
                        Log.d(TAG, "Heard: $result")
                        if (result.lowercase(Locale.ROOT).contains("max") || result.lowercase(Locale.ROOT).contains("मैक्स")) {
                            showToast("Max Sun Liya! 🚀")
                        }
                    }
                }
                restartListening()
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null) {
                    for (result in matches) {
                        if (result.lowercase(Locale.ROOT).contains("max") || result.lowercase(Locale.ROOT).contains("मैक्स")) {
                            showToast("Max Detected (Partial)! 🚀")
                        }
                    }
                }
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })

        startListening()
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startListening() {
        try {
            speechRecognizer.startListening(speechIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognizer", e)
        }
    }

    private fun restartListening() {
        try {
            speechRecognizer.stopListening()
            startListening()
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting speech recognizer", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startListening()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
    }
}
    
