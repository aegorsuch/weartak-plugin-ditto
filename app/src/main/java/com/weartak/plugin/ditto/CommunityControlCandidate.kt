package com.weartak.plugin.ditto

import android.os.BadParcelableException
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.weartak.plugin.sdk.community.v1.CommunityIdentityContract
import com.weartak.plugin.sdk.community.v1.ICommunityIdentityControlV1
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Provisioning is disabled in production until runtime ownership and recovery exist.
 * The enabled path validates and discards results; it never delivers an identity.
 */
internal class CommunityControlCandidate(
    private val trustHost: (Int, String) -> Boolean,
    private val readAllowed: () -> Boolean = { false },
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val retryDelayMillis: Long = 500,
) {
    internal enum class State { UNAVAILABLE, RETRYING, UNAUTHORIZED, VALIDATED }

    private val lock = Any()
    private var closed = false
    private var candidate: Candidate? = null
    private var pending: ScheduledFuture<*>? = null
    private var currentState = State.UNAVAILABLE

    private class Candidate(
        val uid: Int,
        val packageName: String,
        val binder: IBinder,
        val death: IBinder.DeathRecipient,
    )

    internal val hasCandidate: Boolean get() = synchronized(lock) { candidate != null }
    internal val state: State get() = synchronized(lock) { currentState }

    fun initialize(uid: Int, packageName: String, extras: Bundle) = synchronized(lock) {
        detach()
        if (closed || !trustHost(uid, packageName) ||
            extras.getInt(CommunityIdentityContract.EXTRA_VERSION, 0) != CommunityIdentityContract.VERSION
        ) return
        val binder = extras.getBinder(CommunityIdentityContract.EXTRA_CONTROL) ?: return
        if (!binder.isBinderAlive) return
        val death = object : IBinder.DeathRecipient {
            override fun binderDied() = synchronized(lock) {
                if (candidate?.death === this) detach()
            }
        }
        try {
            binder.linkToDeath(death, 0)
        } catch (_: RemoteException) {
            return
        }
        if (!binder.isBinderAlive) {
            binder.unlinkToDeath(death, 0)
            return
        }
        candidate = Candidate(uid, packageName, binder, death)
    }

    fun start() = synchronized(lock) {
        val selected = candidate ?: return
        if (pending != null) return
        if (!isCurrent(selected)) {
            detach()
            return
        }
        currentState = State.RETRYING
        pending = executor.schedule({ read(selected, 0) }, 0, TimeUnit.MILLISECONDS)
    }

    private fun read(selected: Candidate, retries: Int) {
        var completed = false
        try {
            readAttempt(selected, retries)
            completed = true
        } finally {
            if (!completed) synchronized(lock) {
                if (candidate === selected) {
                    pending = null
                    currentState = State.UNAVAILABLE
                    Log.w("DittoPlugin", "Community identity control failed")
                }
            }
        }
    }

    private fun readAttempt(selected: Candidate, retries: Int) {
        synchronized(lock) {
            if (!isCurrent(selected)) {
                if (candidate === selected) detach()
                return
            }
        }
        val control = ICommunityIdentityControlV1.Stub.asInterface(selected.binder)
        val result = try {
            if (control.version != CommunityIdentityContract.VERSION) {
                State.UNAVAILABLE
            } else {
                synchronized(lock) {
                    if (!isCurrent(selected)) {
                        if (candidate === selected) detach()
                        return
                    }
                }
                val response = control.readCommunityIdentity()
                synchronized(lock) {
                    if (!isCurrent(selected)) {
                        if (candidate === selected) detach()
                        return
                    }
                    validate(response)
                }
            }
        } catch (_: RemoteException) {
            State.UNAVAILABLE
        } catch (_: SecurityException) {
            State.UNAUTHORIZED
        } catch (_: BadParcelableException) {
            State.UNAVAILABLE
        }
        synchronized(lock) {
            if (!isCurrent(selected)) {
                if (candidate === selected) detach()
                return
            }
            if (result == State.RETRYING && retries < MAX_RETRIES) {
                pending = executor.schedule({ read(selected, retries + 1) }, retryDelayMillis, TimeUnit.MILLISECONDS)
            } else {
                pending = null
                currentState = if (result == State.RETRYING) State.UNAVAILABLE else result
            }
        }
    }

    private fun validate(result: Bundle?): State {
        if (result == null) return State.UNAVAILABLE
        @Suppress("DEPRECATION")
        val status = result.get(CommunityIdentityContract.KEY_STATUS) as? String
            ?: return State.UNAVAILABLE
        return when (status) {
            CommunityIdentityContract.INACTIVE ->
                if (result.keySet() == setOf(CommunityIdentityContract.KEY_STATUS)) {
                    State.RETRYING
                } else {
                    State.UNAVAILABLE
                }
            CommunityIdentityContract.UNAUTHORIZED ->
                if (result.keySet() == setOf(CommunityIdentityContract.KEY_STATUS)) {
                    State.UNAUTHORIZED
                } else {
                    State.UNAVAILABLE
                }
            CommunityIdentityContract.UNAVAILABLE -> State.UNAVAILABLE
            CommunityIdentityContract.OK -> {
                if (result.keySet() != setOf(
                        CommunityIdentityContract.KEY_STATUS,
                        CommunityIdentityContract.KEY_IDENTITY,
                    )
                ) return State.UNAVAILABLE
                @Suppress("DEPRECATION")
                val encoded = result.get(CommunityIdentityContract.KEY_IDENTITY) as? String
                    ?: return State.UNAVAILABLE
                if (encoded.length !in 1..CommunityIdentityContract.MAX_IDENTITY_LENGTH ||
                    !ENCODED_IDENTITY.matches(encoded) || encoded.length % 4 == 1 ||
                    ('=' in encoded && encoded.length % 4 != 0)
                ) State.UNAVAILABLE else State.VALIDATED
            }
            else -> State.UNAVAILABLE
        }
    }

    private fun isCurrent(selected: Candidate): Boolean =
        !closed && candidate === selected && readAllowed() && selected.binder.isBinderAlive &&
            trustHost(selected.uid, selected.packageName)

    fun invalidate() = synchronized(lock) { detach() }

    fun close() = synchronized(lock) {
        closed = true
        detach()
        executor.shutdownNow()
    }

    private fun detach() {
        pending?.cancel(false)
        pending = null
        currentState = State.UNAVAILABLE
        val old = candidate ?: return
        candidate = null
        old.binder.unlinkToDeath(old.death, 0)
    }

    private companion object {
        const val MAX_RETRIES = 20
        val ENCODED_IDENTITY = Regex("[A-Za-z0-9_-]+={0,2}")
    }
}
