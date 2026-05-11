package com.example.backgroundlocation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

@CapacitorPlugin(
    name = "BackgroundLocation",
    permissions = [
        Permission(
            alias = "location",
            strings = [
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ]
        )
    ]
)
class BackgroundLocationPlugin : Plugin() {

    @PluginMethod
    fun start(call: PluginCall) {
        val sessionId = call.getString("sessionId")
        val participantType = call.getString("participantType")
        val postUrl = call.getString("postUrl")
        val apiKey = call.getString("apiKey")
        val authorization = call.getString("authorization")
        val throttleMs = call.getDouble("throttleMs") ?: 5000.0
        val title = call.getString("notificationTitle") ?: "Live Location"
        val message = call.getString("notificationMessage") ?: "Sharing your live location."

        if (sessionId == null || participantType == null || postUrl == null || apiKey == null || authorization == null) {
            call.reject("Missing required start() options.")
            return
        }

        val ctx: Context = context

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            call.setKeepAlive(true)
            bridge.saveCall(call)
            requestPermissionForAlias("location", call, "permissionCallback")
            return
        }

        startService(ctx, sessionId, participantType, postUrl, apiKey, authorization, throttleMs, title, message)
        LiveLocationState.eventSink = { event, data -> notifyListeners(event, data) }
        call.resolve()
    }

    @PermissionCallback
    private fun permissionCallback(call: PluginCall) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            notifyListeners("permissionDenied", JSObject())
            call.reject("Location permission denied")
            return
        }
        val sessionId = call.getString("sessionId")!!
        val participantType = call.getString("participantType")!!
        val postUrl = call.getString("postUrl")!!
        val apiKey = call.getString("apiKey")!!
        val authorization = call.getString("authorization")!!
        val throttleMs = call.getDouble("throttleMs") ?: 5000.0
        val title = call.getString("notificationTitle") ?: "Live Location"
        val message = call.getString("notificationMessage") ?: "Sharing your live location."

        startService(context, sessionId, participantType, postUrl, apiKey, authorization, throttleMs, title, message)
        LiveLocationState.eventSink = { event, data -> notifyListeners(event, data) }
        call.resolve()
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        val intent = Intent(context, LiveLocationForegroundService::class.java)
        context.stopService(intent)
        LiveLocationState.reset()
        call.resolve()
    }

    @PluginMethod
    fun getStatus(call: PluginCall) {
        val ret = JSObject()
        ret.put("active", LiveLocationState.active)
        ret.put("sessionId", LiveLocationState.sessionId)
        ret.put("participantType", LiveLocationState.participantType)
        ret.put("lastFixAt", LiveLocationState.lastFixAt)
        ret.put("lastWriteAt", LiveLocationState.lastWriteAt)
        ret.put("lastError", LiveLocationState.lastError)
        call.resolve(ret)
    }

    private fun startService(
        ctx: Context,
        sessionId: String,
        participantType: String,
        postUrl: String,
        apiKey: String,
        authorization: String,
        throttleMs: Double,
        title: String,
        message: String,
    ) {
        val intent = Intent(ctx, LiveLocationForegroundService::class.java).apply {
            putExtra("sessionId", sessionId)
            putExtra("participantType", participantType)
            putExtra("postUrl", postUrl)
            putExtra("apiKey", apiKey)
            putExtra("authorization", authorization)
            putExtra("throttleMs", throttleMs)
            putExtra("notificationTitle", title)
            putExtra("notificationMessage", message)
        }
        ContextCompat.startForegroundService(ctx, intent)
    }
}
