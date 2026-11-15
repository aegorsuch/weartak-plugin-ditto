package com.weartak.plugin.ditto

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.routestatus.RouteConnectionState
import com.weartak.plugin.sdk.routestatus.RouteStatusContract
import com.weartak.plugin.sdk.routestatus.RouteStatusResultCode
import com.weartak.plugin.sdk.routestatus.aidl.IPluginRouteStatusControlV1
import com.weartak.plugin.sdk.routestatus.model.PluginRouteStatusParcel
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicLong

internal class DittoRouteStatus(private val executor: ExecutorService) {
    private val lock = Any()
    private var closed = false
    private var session: Session? = null

    private class Session(
        val binder: IBinder,
        val control: IPluginRouteStatusControlV1,
        val death: IBinder.DeathRecipient,
    )

    fun initialize(init: PluginInitParcel) = synchronized(lock) {
        if (closed) return
        val extras = init.hostExtras
        val binder = extras.getBinder(RouteStatusContract.EXTRA_CONTROL)
        if (!extras.getBoolean(RouteStatusContract.EXTRA_CAPABILITY, false) ||
            binder == null || !binder.isBinderAlive
        ) {
            retire()
            return
        }
        var current = session
        if (current?.binder != binder) {
            retire()
            val death = object : IBinder.DeathRecipient {
                override fun binderDied() {
                    synchronized(lock) {
                        if (session?.death === this) session = null
                    }
                }
            }
            try {
                binder.linkToDeath(death, 0)
            } catch (_: RemoteException) {
                Log.w(TAG, "Route status host unavailable")
                return
            }
            if (!binder.isBinderAlive) {
                binder.unlinkToDeath(death, 0)
                Log.w(TAG, "Route status host unavailable")
                return
            }
            current = Session(binder, IPluginRouteStatusControlV1.Stub.asInterface(binder), death)
            session = current
        }
        val selected = requireNotNull(current)
        executor.execute {
            synchronized(lock) {
                if (closed || session !== selected) return@execute
            }
            try {
                val status = PluginRouteStatusParcel(
                    routeId = ROUTE_ID,
                    label = "Ditto",
                    state = RouteConnectionState.DISCONNECTED,
                    statusText = "Runtime unavailable",
                    sequence = sequence.incrementAndGet(),
                    changedAtEpochMillis = System.currentTimeMillis(),
                )
                val result = selected.control.reportRouteStatus(status)
                if (result != RouteStatusResultCode.OK) {
                    Log.w(TAG, "Route status report rejected: $result")
                }
            } catch (_: RemoteException) {
                Log.w(TAG, "Route status host unavailable")
            } catch (_: SecurityException) {
                Log.w(TAG, "Route status host rejected caller")
            }
        }
    }

    fun shutdown() = synchronized(lock) {
        retire()
    }

    private fun retire() {
        val previous = session ?: return
        detach()
        executor.execute {
            try {
                val result = previous.control.removeRoute(ROUTE_ID)
                if (result != RouteStatusResultCode.OK) {
                    Log.w(TAG, "Route status removal rejected: $result")
                }
            } catch (_: RemoteException) {
                Log.w(TAG, "Route status host unavailable")
            } catch (_: SecurityException) {
                Log.w(TAG, "Route status host rejected caller")
            }
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        detach()
        executor.shutdownNow()
    }

    private fun detach() {
        val previous = session ?: return
        session = null
        previous.binder.unlinkToDeath(previous.death, 0)
    }

    companion object {
        private const val TAG = "DittoRouteStatus"
        internal const val ROUTE_ID = "ditto"
        // Sequence must survive reporter and binding replacement within this process.
        private val sequence = AtomicLong()
    }
}
