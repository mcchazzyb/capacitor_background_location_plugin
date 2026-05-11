package com.example.backgroundlocation

import com.getcapacitor.JSObject

/**
 * Shared state between the foreground service (which owns the GPS + HTTP)
 * and the Capacitor plugin (which exposes status to JS and forwards events).
 */
object LiveLocationState {
    @Volatile var active: Boolean = false
    @Volatile var sessionId: String? = null
    @Volatile var participantType: String? = null
    @Volatile var lastFixAt: Long? = null
    @Volatile var lastWriteAt: Long? = null
    @Volatile var lastError: String? = null

    @Volatile var eventSink: ((event: String, data: JSObject) -> Unit)? = null

    fun reset() {
        active = false
        sessionId = null
        participantType = null
        lastFixAt = null
        lastWriteAt = null
        lastError = null
    }
}
