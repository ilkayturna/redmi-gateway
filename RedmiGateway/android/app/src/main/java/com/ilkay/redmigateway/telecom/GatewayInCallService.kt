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

class GatewayInCallService : InCallService() {

    @Volatile private var endpoints: List<CallEndpoint> = emptyList()

    companion object {
        private const val TAG = "GatewayInCallService"

        @Volatile private var instance: GatewayInCallService? = null

        /** Mutes the Redmi microphone on the cellular call; the iPhone supplies the uplink. */
        fun setGatewayMuted(muted: Boolean) {
            val service = instance ?: return
            service.mainExecutor.execute {
                runCatching { service.setMuted(muted) }
                    .onFailure { Log.w(TAG, "Could not change call mute", it) }
            }
        }

        fun isSpeakerOn(): Boolean {
            val service = instance ?: return false
            return if (Build.VERSION.SDK_INT >= 34) {
                service.currentCallEndpoint?.endpointType == CallEndpoint.TYPE_SPEAKER
            } else {
                @Suppress("DEPRECATION")
                service.callAudioState?.route == CallAudioState.ROUTE_SPEAKER
            }
        }

        fun toggleSpeaker(onResult: (Boolean) -> Unit) {
            val service = instance ?: return onResult(false)
            service.mainExecutor.execute {
                if (Build.VERSION.SDK_INT >= 34) {
                    val targetType = if (isSpeakerOn())
                        CallEndpoint.TYPE_EARPIECE else CallEndpoint.TYPE_SPEAKER
                    val endpoint = service.endpoints.firstOrNull {
                        it.endpointType == targetType
                    }
                    if (endpoint == null) {
                        onResult(false)
                    } else {
                        service.requestCallEndpointChange(
                            endpoint, service.mainExecutor,
                            object : OutcomeReceiver<Void?, CallEndpointException> {
                                override fun onResult(result: Void?) = onResult(true)
                                override fun onError(error: CallEndpointException) {
                                    Log.w(TAG, "Could not change call speaker", error)
                                    onResult(false)
                                }
                            }
                        )
                    }
                } else {
                    @Suppress("DEPRECATION")
                    service.setAudioRoute(
                        if (isSpeakerOn()) CallAudioState.ROUTE_EARPIECE
                        else CallAudioState.ROUTE_SPEAKER
                    )
                    onResult(true)
                }
            }
        }

        /** Asks Telecom to apply the endpoint it already uses; used to re-evaluate call routing. */
        fun reapplyCurrentEndpoint(): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < 34) return false
            val current = service.currentCallEndpoint ?: return false
            service.mainExecutor.execute {
                service.requestCallEndpointChange(
                    current, service.mainExecutor,
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
            service.mainExecutor.execute {
                if (Build.VERSION.SDK_INT >= 34) {
                    val targetType = when (route) {
                        CallAudioState.ROUTE_SPEAKER -> CallEndpoint.TYPE_SPEAKER
                        CallAudioState.ROUTE_EARPIECE -> CallEndpoint.TYPE_EARPIECE
                        CallAudioState.ROUTE_BLUETOOTH -> CallEndpoint.TYPE_BLUETOOTH
                        else -> CallEndpoint.TYPE_EARPIECE
                    }
                    val endpoint = service.endpoints.firstOrNull { it.endpointType == targetType }
                    if (endpoint != null) {
                        service.requestCallEndpointChange(
                            endpoint, service.mainExecutor,
                            object : OutcomeReceiver<Void?, CallEndpointException> {
                                override fun onResult(result: Void?) = Unit
                                override fun onError(error: CallEndpointException) {
                                    Log.w(TAG, "Could not set audio route to $route", error)
                                }
                            }
                        )
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

    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableEndpoints)
        endpoints = availableEndpoints.toList()
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
