package com.vashai.maxassistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import android.telephony.SmsManager
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import kotlin.math.sqrt

class MaxSecurityAndSystemController(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var antiTheftArmed = false
    private var sirenPlayer: MediaPlayer? = null
    private val httpClient = OkHttpClient()

    fun sendWhatsAppMessage(phoneWithCountryCode: String, message: String): String {
        return try {
            MaxAccessibilityService.pendingWhatsAppAutoSend = true
            val cleanNumber = phoneWithCountryCode.replace("+", "").replace(" ", "")
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "WhatsApp message sent to $cleanNumber."
        } catch (e: Exception) {
            "Failed to dispatch WhatsApp message: ${e.message}"
        }
    }

    fun openApplicationByName(appName: String): String {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)
        val matchedApp = packages.firstOrNull {
            pm.getApplicationLabel(it).toString().contains(appName, ignoreCase = true)
        }
        return if (matchedApp != null) {
            val launchIntent = pm.getLaunchIntentForPackage(matchedApp.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
            "Opened application ${pm.getApplicationLabel(matchedApp)}."
        } else {
            "Application '$appName' not found on device."
        }
    }

    fun controlSystemSetting(settingType: String, stateOrLevel: Int): String {
        return try {
            when (settingType.uppercase()) {
                "FLASHLIGHT" -> {
                    val cameraId = cameraManager.cameraIdList[0]
                    cameraManager.setTorchMode(cameraId, stateOrLevel == 1)
                    if (stateOrLevel == 1) "Flashlight turned ON." else "Flashlight turned OFF."
                }
                "VOLUME" -> {
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val targetVol = (stateOrLevel.coerceIn(0, 100) * maxVol) / 100
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
                    "Volume set to $stateOrLevel%."
                }
                "MUTE" -> {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                    "System audio muted."
                }
                "BRIGHTNESS" -> {
                    if (Settings.System.canWrite(context)) {
                        val bValue = (stateOrLevel.coerceIn(0, 100) * 255) / 100
                        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, bValue)
                        "Brightness set to $stateOrLevel%."
                    } else {
                        "Write settings permission required."
                    }
                }
                "WIFI" -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val panelIntent = Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(panelIntent)
                        "Opened Wi-Fi panel."
                    } else {
                        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        @Suppress("DEPRECATION")
                        wifiManager.isWifiEnabled = (stateOrLevel == 1)
                        "Wi-Fi updated."
                    }
                }
                else -> "Unknown setting type."
            }
        } catch (e: Exception) {
            "Error updating setting: ${e.message}"
        }
    }

    fun setAntiTheftGuard(enable: Boolean): String {
        antiTheftArmed = enable
        return if (enable) {
            val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
            "Anti-Theft Guard ARMED."
        } else {
            sensorManager.unregisterListener(this)
            sirenPlayer?.stop()
            sirenPlayer?.release()
            sirenPlayer = null
            "Anti-Theft Guard DISARMED."
        }
    }

    fun triggerAntiTheftSiren() {
        val maxAlarmVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVol, 0)
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        sirenPlayer = MediaPlayer.create(context, alarmUri)?.apply {
            isLooping = true
            start()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!antiTheftArmed || event == null) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val magnitude = sqrt((x * x + y * y + z * z).toDouble())
            if (magnitude > 16.0) {
                triggerAntiTheftSiren()
                antiTheftArmed = false
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @SuppressLint("MissingPermission")
    fun dispatchEmergencySOS(emergencyPhone: String): String {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        fusedClient.lastLocation.addOnSuccessListener { location ->
            val mapsLink = if (location != null) {
                "https://maps.google.com/?q=${location.latitude},${location.longitude}"
            } else {
                "GPS signal acquiring"
            }
            val alertText = "EMERGENCY SOS ALERT from MAX AI! Live Location: $mapsLink"
            val smsManager = context.getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(emergencyPhone, null, alertText, null, null)
            sendWhatsAppMessage(emergencyPhone, alertText)
        }
        return "Emergency SOS with live GPS sent to $emergencyPhone."
    }

    fun encryptNoteToVault(fileName: String, secretContent: String): String {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val vaultDir = File(context.filesDir, "MaxSecureVault").apply { mkdirs() }
            val secretFile = File(vaultDir, "$fileName.enc")
            if (secretFile.exists()) secretFile.delete()

            val encryptedFile = EncryptedFile.Builder(
                context,
                secretFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            encryptedFile.openFileOutput().use { output ->
                output.write(secretContent.toByteArray(Charsets.UTF_8))
            }
            "File '$fileName' locked inside MAX Secure Vault."
        } catch (e: Exception) {
            "Vault error: ${e.message}"
        }
    }

    suspend fun scrapeWebsiteSummary(url: String): String = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").get()
            val title = doc.title()
            val paragraphs = doc.select("p").take(6).joinToString("\n") { it.text() }
            "Title: $title\n$paragraphs"
        } catch (e: Exception) {
            "Scraping failed: ${e.message}"
        }
    }

    suspend fun sendCommandToNovaPC(pcIpAddress: String, commandAction: String, payload: String): String =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("action", commandAction)
                    put("payload", payload)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("http://$pcIpAddress:8765/nova-execute")
                    .post(body)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    response.body?.string() ?: "Executed on NOVA PC."
                }
            } catch (e: Exception) {
                "PC Bridge unreachable: ${e.message}"
            }
        }
}
