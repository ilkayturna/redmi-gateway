package com.ilkay.redmigateway.telecom

import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import android.util.Log
import android.os.OutcomeReceiver
import androidx.core.content.ContextCompat

class GatewayInCallService : InCallService() {

    @Volatile private var endpoints: List<CallEndpoint> = emptyList()
    /** Last endpoint Telecom reported; the cache [endpoints] alone misses late changes. */
    @Volatile private var currentEndpoint: CallEndpoint? = null

    companion object {
        private const val TAG = "GatewayInCallService"

        @Volatile private var instance: GatewayInCallService? = null

        /**
         * Route changes run here, not on the main thread: the deprecated setAudioRoute is a
         * synchronous binder call that can block for as long as the audio HAL takes to switch, and
         * doing that on the UI thread is what made the speaker button feel stuck.
         */
        private val routeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "CallRoute").apply { priority = Thread.NORM_PRIORITY }
        }

        /** Mutes the Redmi microphone on the cellular call; the iPhone supplies the uplink. */
        fun setGatewayMuted(muted: Boolean) {
            val service = instance ?: return
            ContextCompat.getMainExecutor(service).execute {
                runCatching { service.setMuted(muted) }
                    .onFailure { Log.w(TAG, "Could not change call mute", it) }
            }
        }

        fun isSpeakerOn(): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT >= 34) {
                val current = runCatching { service.currentCallEndpoint }.getOrNull() ?: service.currentEndpoint
                if (current != null) return current.endpointType == CallEndpoint.TYPE_SPEAKER
            }
            @Suppress("DEPRECATION")
            return service.callAudioState?.route == CallAudioState.ROUTE_SPEAKER
        }

        fun toggleSpeaker(onResult: (Boolean) -> Unit) {
            val service = instance ?: return onResult(false)
            val executor = ContextCompat.getMainExecutor(service)
            executor.execute {
                val toSpeaker = !isSpeakerOn()
                val targetRoute = if (toSpeaker) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
                fun report(ok: Boolean) = executor.execute { onResult(ok) }
                fun fallbackAudioRoute() {
                    // Off the main thread, then report back on it.
                    routeExecutor.execute {
                        val ok = runCatching {
                            @Suppress("DEPRECATION")
                            service.setAudioRoute(targetRoute)
                            true
                        }.onFailure { Log.w(TAG, "Fallback setAudioRoute failed", it) }.getOrDefault(false)
                        report(ok)
                    }
                }

                if (Build.VERSION.SDK_INT >= 34) {
                    val targetType = if (toSpeaker)
                        CallEndpoint.TYPE_SPEAKER else CallEndpoint.TYPE_EARPIECE
                    val endpoint = service.endpointFor(targetType)
                    if (endpoint == null) {
                        Log.w(TAG, "Endpoint $targetType not found, falling back to setAudioRoute")
                        fallbackAudioRoute()
                    } else {
                        Log.i(TAG, "SPEAKER_TOGGLE toSpeaker=$toSpeaker endpoint=${endpoint.endpointType}")
                        service.requestCallEndpointChange(
                            endpoint, executor,
                            object : OutcomeReceiver<Void?, CallEndpointException> {
                                override fun onResult(result: Void?) = onResult(true)
                                override fun onError(error: CallEndpointException) {
                                    Log.w(TAG, "requestCallEndpointChange error, falling back to setAudioRoute", error)
                                    fallbackAudioRoute()
                                }
                            }
                        )
                    }
                } else {
                    fallbackAudioRoute()
                }
            }
        }

        /** Asks Telecom to apply the endpoint it already uses; used to re-evaluate call routing. */
        fun reapplyCurrentEndpoint(): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < 34) return false
            val current = service.currentCallEndpoint ?: return false
            val executor = ContextCompat.getMainExecutor(service)
            executor.execute {
                service.requestCallEndpointChange(
                    current, executor,
                    object : OutcomeReceiver<Void?, CallEndpointException> {
                        override fun onResult(result: Void?) = Unit
                        override fun onError(error: CallEndpointException) {
                            Log.w(TAG, "Could not re-apply call endpoint", error)
                        }
                    }
                )
            }
            return true
        }

        fun currentEndpointType(): Int? =
            if (Build.VERSION.SDK_INT >= 34) instance?.currentCallEndpoint?.endpointType else null

        fun setAudioRoute(route: Int) {
            val service = instance ?: return
            val executor = ContextCompat.getMainExecutor(service)
            executor.execute {
                if (Build.VERSION.SDK_INT >= 34) {
                    val targetType = when (route) {
                        CallAudioState.ROUTE_SPEAKER -> CallEndpoint.TYPE_SPEAKER
                        CallAudioState.ROUTE_EARPIECE -> CallEndpoint.TYPE_EARPIECE
                        CallAudioState.ROUTE_BLUETOOTH -> CallEndpoint.TYPE_BLUETOOTH
                        else -> CallEndpoint.TYPE_EARPIECE
                    }
                    val endpoint = service.endpointFor(targetType)
                    if (endpoint != null) {
                        service.requestCallEndpointChange(
                            endpoint, executor,
                            object : OutcomeReceiver<Void?, CallEndpointException> {
                                override fun onResult(result: Void?) = Unit
                                override fun onError(error: CallEndpointException) {
                                    Log.w(TAG, "Could not set audio route to $route via requestCallEndpointChange, falling back", error)
                                    @Suppress("DEPRECATION")
                                    service.setAudioRoute(route)
                                }
                            }
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        service.setAudioRoute(route)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    service.setAudioRoute(route)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        CallUiNotifier.start(this)
    }

    /**
     * The endpoint to switch the call to, from the list Telecom reported for this call.
     *
     * Android offers no getter for the available endpoints (only this callback), so the cache is
     * all we have; [onCallEndpointChanged] keeps it honest.
     */
    private fun endpointFor(type: Int): CallEndpoint? = endpoints.firstOrNull { it.endpointType == type }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableEndpoints)
        endpoints = availableEndpoints.toList()
        Log.i(TAG, "Available call endpoints: ${availableEndpoints.map { it.endpointType }}")
    }

    override fun onCallEndpointChanged(endpoint: CallEndpoint) {
        super.onCallEndpointChanged(endpoint)
        currentEndpoint = endpoint
        Log.i(TAG, "Call endpoint changed to ${endpoint.endpointType}")
    }

    override fun onDestroy() {
        CallUiNotifier.stop()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind called with intent: $intent")
        CallManager.log("GatewayInCallService bound by Android Telecom")
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind called")
        CallManager.log("GatewayInCallService unbound")
        return super.onUnbind(intent)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d(TAG, "onCallAdded")
        CallManager.onCallAdded(call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved")
        CallManager.onCallRemoved(call)
    }
}
