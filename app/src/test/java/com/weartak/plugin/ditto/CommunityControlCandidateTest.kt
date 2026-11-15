package com.weartak.plugin.ditto

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommunityControlCandidateTest {
    private class Control : Binder() {
        var alive = true
        var failLink = false
        var dieDuringLink = false
        var links = 0
        var unlinks = 0
        var transactions = 0
        var recipient: IBinder.DeathRecipient? = null
        override fun isBinderAlive() = alive
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (failLink) throw RemoteException()
            links++
            this.recipient = recipient
            if (dieDuringLink) alive = false
        }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            if (this.recipient !== recipient) return false
            unlinks++
            this.recipient = null
            return true
        }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            transactions++
            throw AssertionError("Candidate must not make any control calls")
        }
        fun die() {
            alive = false
            recipient?.binderDied()
        }
    }

    private fun extras(control: Control?, version: Int = 1) = Bundle().apply {
        putInt("com.weartak.plugin.ditto.community.version", version)
        if (control != null) putBinder("com.weartak.plugin.ditto.community.v1", control)
    }

    @Test
    fun `missing unsupported untrusted dead and unwatchable controls fail closed`() {
        var trusted = false
        val candidate = CommunityControlCandidate(trustHost = { _, _ -> trusted })
        val control = Control()
        candidate.initialize(7, "host", extras(control))
        assertFalse(candidate.hasCandidate)
        assertEquals(0, control.links)
        trusted = true
        candidate.initialize(7, "host", Bundle())
        candidate.initialize(7, "host", extras(null))
        candidate.initialize(7, "host", extras(control, 2))
        assertFalse(candidate.hasCandidate)
        control.alive = false
        candidate.initialize(7, "host", extras(control))
        control.alive = true
        control.failLink = true
        candidate.initialize(7, "host", extras(control))
        assertFalse(candidate.hasCandidate)
        control.failLink = false
        control.dieDuringLink = true
        candidate.initialize(7, "host", extras(control))
        assertFalse(candidate.hasCandidate)
        assertEquals(1, control.unlinks)
        assertEquals(0, control.transactions)
    }

    @Test
    fun `death reinitialization invalidation and close fence stale controls`() {
        val candidate = CommunityControlCandidate(trustHost = { _, _ -> true })
        val old = Control()
        val current = Control()
        candidate.initialize(7, "host", extras(old))
        assertTrue(candidate.hasCandidate)
        val staleDeath = old.recipient!!
        candidate.initialize(7, "host", extras(current))
        assertEquals(1, old.unlinks)
        staleDeath.binderDied()
        assertTrue(candidate.hasCandidate)
        current.die()
        assertFalse(candidate.hasCandidate)
        val next = Control()
        candidate.initialize(7, "host", extras(next))
        assertTrue(candidate.hasCandidate)
        candidate.invalidate()
        assertFalse(candidate.hasCandidate)
        assertEquals(1, next.unlinks)
        candidate.initialize(7, "host", extras(next))
        candidate.close()
        assertFalse(candidate.hasCandidate)
        candidate.initialize(7, "host", extras(Control()))
        assertFalse(candidate.hasCandidate)
        assertEquals(0, old.transactions + current.transactions + next.transactions)
    }
}
