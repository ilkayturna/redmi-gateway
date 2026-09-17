package com.ilkay.redmigateway.telecom

import android.content.Intent
import android.os.IBinder
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

class GatewayInCallService : InCallService() {

    companion object {
        private const val TAG = "GatewayInCallService"
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
        Log.d(TAG, "onCallAdded: $call")
        CallManager.onCallAdded(call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved: $call")
        CallManager.onCallRemoved(call)
    }
}
