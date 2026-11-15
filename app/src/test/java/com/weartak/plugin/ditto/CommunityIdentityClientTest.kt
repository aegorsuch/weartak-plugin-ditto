package com.weartak.plugin.ditto

import android.os.Bundle
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import android.util.Base64
import com.weartak.plugin.sdk.community.v1.CommunityIdentityContract
import com.weartak.plugin.sdk.community.v1.ICommunityIdentityControlV1
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class CommunityIdentityClientTest {
    private class Control(
        private val version: Int = 1,
        private val response: () -> Bundle,
    ) : ICommunityIdentityControlV1.Stub() {
        val versionCalls = AtomicInteger()
        val reads = AtomicInteger()
        private var death: IBinder.DeathRecipient? = null
        private var alive = true
        override fun isBinderAlive() = alive
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { death = recipient }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            if (death !== recipient) return false
            death = null
            return true
        }
        override fun getVersion(): Int {
            versionCalls.incrementAndGet()
            return version
        }
        override fun readCommunityIdentity(): Bundle {
            reads.incrementAndGet()
            return response()
        }
        fun die() {
            alive = false
            death?.binderDied()
        }
    }

    private fun extras(control: IBinder) = Bundle().apply {
        putInt(CommunityIdentityContract.EXTRA_VERSION, CommunityIdentityContract.VERSION)
        putBinder(CommunityIdentityContract.EXTRA_CONTROL, control)
    }

    private fun status(status: String) =
        Bundle().apply { putString(CommunityIdentityContract.KEY_STATUS, status) }
    private fun ok() = status(CommunityIdentityContract.OK).apply {
        putString(CommunityIdentityContract.KEY_IDENTITY, "c3ludGhldGlj")
    }

    private fun awaitTerminal(candidate: CommunityControlCandidate) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (candidate.state == CommunityControlCandidate.State.RETRYING && System.nanoTime() < deadline) {
            Thread.sleep(2)
        }
        assertNotEquals(CommunityControlCandidate.State.RETRYING, candidate.state)
    }

    @Test
    fun `production default never invokes the control`() {
        val control = Control(response = ::ok)
        val candidate = CommunityControlCandidate(trustHost = { _, _ -> true })
        try {
            candidate.initialize(7, "host", extras(control))
            candidate.start()
            assertEquals(CommunityControlCandidate.State.UNAVAILABLE, candidate.state)
            assertEquals(0, control.versionCalls.get())
            assertEquals(0, control.reads.get())
        } finally {
            candidate.close()
        }
    }

    @Test
    fun `generated proxy and stub exchange synthetic responses over Binder`() {
        val control = Control(response = ::ok)
        val remote = object : Binder() {
            override fun queryLocalInterface(descriptor: String): IInterface? = null
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                control.transact(code, data, reply, flags)
        }
        val candidate = CommunityControlCandidate(
            trustHost = { _, _ -> true }, readAllowed = { true }, retryDelayMillis = 1,
        )
        try {
            candidate.initialize(7, "host", extras(remote))
            candidate.start()
            awaitTerminal(candidate)
            assertEquals(1, control.versionCalls.get())
            assertEquals(1, control.reads.get())
            assertEquals(CommunityControlCandidate.State.VALIDATED, candidate.state)
        } finally {
            candidate.close()
        }
    }

    @Test
    fun `Android url safe padded identities are accepted without logging or persistence`() {
        val padded = listOf(
            Base64.encodeToString(byteArrayOf(0xfb.toByte()), Base64.URL_SAFE or Base64.NO_WRAP),
            Base64.encodeToString(byteArrayOf(0xfb.toByte(), 0xff.toByte()), Base64.URL_SAFE or Base64.NO_WRAP),
        )
        assertTrue(padded[0].endsWith("=="))
        assertTrue(padded[1].endsWith("=") && !padded[1].endsWith("=="))
        val dataDir = RuntimeEnvironment.getApplication().dataDir
        fun files() = dataDir.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(dataDir).path to (it.length() to it.lastModified()) }
        val before = files()
        ShadowLog.clear()
        val candidate = CommunityControlCandidate(
            trustHost = { _, _ -> true }, readAllowed = { true }, retryDelayMillis = 1,
        )
        try {
            for (encoded in padded) {
                val control = Control { status("ok").apply { putString("identity", encoded) } }
                candidate.initialize(7, "host", extras(control))
                candidate.start()
                awaitTerminal(candidate)
                assertEquals(CommunityControlCandidate.State.VALIDATED, candidate.state)
            }
            assertEquals(before, files())
            for (entry in ShadowLog.getLogs()) {
                assertFalse(padded.any { entry.msg?.contains(it) == true })
            }
        } finally {
            candidate.close()
        }
    }

    @Test
    fun `inactive responses retry and success is validated then discarded`() {
        val calls = AtomicInteger()
        val control = Control { if (calls.getAndIncrement() < 2) status("inactive") else ok() }
        val candidate = CommunityControlCandidate(
            trustHost = { _, _ -> true }, readAllowed = { true }, retryDelayMillis = 1,
        )
        try {
            candidate.initialize(7, "host", extras(control))
            candidate.start()
            awaitTerminal(candidate)
            assertEquals(3, control.reads.get())
            assertEquals(CommunityControlCandidate.State.VALIDATED, candidate.state)
        } finally {
            candidate.close()
        }
    }

    @Test
    fun `inactive retries are bounded and failed versions do not read`() {
        val control = Control { status("inactive") }
        val candidate = CommunityControlCandidate(
            trustHost = { _, _ -> true }, readAllowed = { true }, retryDelayMillis = 1,
        )
        try {
            candidate.initialize(7, "host", extras(control))
            candidate.start()
            awaitTerminal(candidate)
            assertEquals(21, control.reads.get())
            assertEquals(CommunityControlCandidate.State.UNAVAILABLE, candidate.state)
            val unsupported = Control(version = 2, response = ::ok)
            candidate.initialize(7, "host", extras(unsupported))
            candidate.start()
            awaitTerminal(candidate)
            assertEquals(0, unsupported.reads.get())
            assertEquals(CommunityControlCandidate.State.UNAVAILABLE, candidate.state)
        } finally {
            candidate.close()
        }
    }

    @Test
    fun `malformed and rejected responses fail closed`() {
        val invalid = listOf(
            status("ok"),
            ok().apply { putString("identity", "") },
            ok().apply { putString("identity", "a".repeat(16_385)) },
            ok().apply { putString("identity", "not-base64!") },
            ok().apply { putString("identity", "abcde") },
            ok().apply { putInt("identity", 3) },
            ok().apply { putString("unexpected", "extra") },
            status("inactive").apply { putString("identity", "c3ludGhldGlj") },
            status("unknown"),
        )
        val candidate = CommunityControlCandidate(
            trustHost = { _, _ -> true }, readAllowed = { true }, retryDelayMillis = 1,
        )
        try {
            for (response in invalid) {
                candidate.initialize(7, "host", extras(Control { response }))
                candidate.start()
                awaitTerminal(candidate)
                assertEquals(CommunityControlCandidate.State.UNAVAILABLE, candidate.state)
            }
            candidate.initialize(7, "host", extras(Control { status("unauthorized") }))
            candidate.start()
            awaitTerminal(candidate)
            assertEquals(CommunityControlCandidate.State.UNAUTHORIZED, candidate.state)
            candidate.initialize(7, "host", extras(Control { throw SecurityException("synthetic") }))
            candidate.start()
            awaitTerminal(candidate)
            assertEquals(CommunityControlCandidate.State.UNAUTHORIZED, candidate.state)
            candidate.initialize(7, "host", extras(Control { throw RemoteException("synthetic") }))
            candidate.start()
            awaitTerminal(candidate)
            assertEquals(CommunityControlCandidate.State.UNAVAILABLE, candidate.state)
        } finally {
            candidate.close()
        }
    }

    @Test
    fun `replacement death and revoked trust fence in flight responses`() {
        for (invalidation in listOf("replacement", "death", "stop", "revocation")) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val trusted = AtomicBoolean(true)
            val old = Control {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                ok()
            }
            val candidate = CommunityControlCandidate(
                trustHost = { _, _ -> trusted.get() }, readAllowed = { true }, retryDelayMillis = 1,
            )
            try {
                candidate.initialize(7, "host", extras(old))
                candidate.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                when (invalidation) {
                    "replacement" -> candidate.initialize(7, "host", extras(Control { status("unavailable") }))
                    "death" -> {
                        old.die()
                        candidate.initialize(7, "host", extras(Control { status("unavailable") }))
                    }
                    "stop" -> {
                        candidate.invalidate()
                        candidate.initialize(7, "host", extras(Control { status("unavailable") }))
                    }
                    else -> trusted.set(false)
                }
                release.countDown()
                if (invalidation != "revocation") candidate.start()
                if (invalidation == "revocation") awaitTerminal(candidate)
                if (invalidation != "revocation") awaitTerminal(candidate)
                assertEquals(CommunityControlCandidate.State.UNAVAILABLE, candidate.state)
            } finally {
                release.countDown()
                candidate.close()
            }
        }
    }
}
