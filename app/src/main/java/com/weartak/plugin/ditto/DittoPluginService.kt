package com.weartak.plugin.ditto

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleTransition

class DittoPluginService : Service() {
    private var binder: DittoPluginBinder? = null

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != WearTakPluginContract.ACTION_PLUGIN_SERVICE) return null
        binder?.let { return it }
        val lifecycle = PluginLifecycleMachine()
        val transition = lifecycle.dispatch(PluginLifecycleEvent.BIND_ATTACHED)
        if (transition is PluginLifecycleTransition.Rejected) {
            Log.w("DittoPlugin", "Binding rejected: ${transition.reason}")
            return null
        }
        return DittoPluginBinder(this, lifecycle).also { binder = it }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        binder?.close()
        binder = null
        return false
    }

    override fun onDestroy() {
        binder?.close()
        binder = null
        super.onDestroy()
    }
}
