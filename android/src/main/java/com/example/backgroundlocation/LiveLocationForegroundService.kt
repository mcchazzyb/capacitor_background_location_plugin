package com.example.backgroundlocation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.getcapacitor.JSObject
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LiveLocationForegroundService : Service() {

    private val client by lazy {
        OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private lateinit var fused: FusedLocationProviderClient
    private var sessionId: String = ""
    private var participantType: String = ""
    private var postUrl: String = ""
    private var apiKey: String = ""
    private var authorization: String = ""
    private var throttleMs: Long = 5000L
    private var lastPostAt: Long = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            LiveLocationState.lastFixAt = System.currentTimeMillis()

            val data = JSObject()
            data.put("latitude", loc.latitude)
            data.put("longitude", loc.longitude)
            data.put("accuracy", loc.accuracy)
            data.put("heading", if (loc.hasBearing()) loc.bearing else null)
            data.put("timestamp", loc.time)

            val didWrite = postLocation(loc.latitude, loc.longitude, loc.accuracy.toDouble(), if (loc.hasBearing()) loc.bearing.toDouble() else null)
            data.put("didWrite", didWrite)
            LiveLocationState.eventSink?.invoke("fix", data)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_NOT_STICKY
        sessionId = intent.getStringExtra("sessionId") ?: ""
        participantType = intent.getStringExtra("participantType") ?: ""
        postUrl = intent.getStringExtra("postUrl") ?: ""
        apiKey = intent.getStringExtra("apiKey") ?: ""
        authorization = intent.getStringExtra("authorization") ?: ""
        throttleMs = (intent.getDoubleExtra("throttleMs", 5000.0)).toLong()
        val title = intent.getStringExtra("notificationTitle") ?: "Live Location"
        val message = intent.getStringExtra("notificationMessage") ?: "Sharing your live location."

        startForeground(NOTIFICATION_ID, buildNotification(title, message))

        fused = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(2000L)
            .setMinUpdateDistanceMeters(5f)
            .build()

        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            LiveLocationState.active = true
            LiveLocationState.sessionId = sessionId
            LiveLocationState.participantType = participantType
            LiveLocationState.lastError = null
        } catch (e: SecurityException) {
            LiveLocationState.lastError = e.message
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        try {
            fused.removeLocationUpdates(callback)
        } catch (_: Exception) {}
        LiveLocationState.reset()
        super.onDestroy()
    }

    private fun buildNotification(title: String, message: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live location",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        pi?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    private fun postLocation(lat: Double, lon: Double, accuracy: Double, heading: Double?): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPostAt < throttleMs) return false
        lastPostAt = now

        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("participant_type", participantType)
            put("latitude", lat)
            put("longitude", lon)
            put("accuracy", accuracy)
            put("updated_at", java.time.Instant.now().toString())
            heading?.let { put("heading", it) }
        }

        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(postUrl)
            .header("apikey", apiKey)
            .header("Authorization", authorization)
            .header("Prefer", "resolution=merge-duplicates")
            .post(body)
            .build()

        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                LiveLocationState.lastError = e.message
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (it.code == 401) {
                        LiveLocationState.lastError = "auth expired"
                        LiveLocationState.eventSink?.invoke("authExpired", JSObject())
                    } else if (it.isSuccessful) {
                        LiveLocationState.lastWriteAt = System.currentTimeMillis()
                        LiveLocationState.lastError = null
                    } else {
                        LiveLocationState.lastError = "HTTP ${it.code}"
                    }
                }
            }
        })

        return true
    }

    companion object {
        private const val CHANNEL_ID = "background_live_location"
        private const val NOTIFICATION_ID = 4711
    }
}
