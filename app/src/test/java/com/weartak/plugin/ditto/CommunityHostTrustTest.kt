package com.weartak.plugin.ditto

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import com.weartak.plugin.sdk.ditto.v1.DittoContractV1
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowSigningInfo

@RunWith(RobolectricTestRunner::class)
class CommunityHostTrustTest {
    private val host = "com.aitech.weartak_civ"
    private val uid = 12345
    private val signature = Signature("010203")
    private val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
    private val context = RuntimeEnvironment.getApplication()
    private val packageManager = context.packageManager
    private val shadowPackageManager = shadowOf(packageManager)
    private val trust = CommunityHostTrust(packageManager, host, digest)

    private fun install(vararg signatures: Signature) {
        installHost(host, *signatures)
    }

    private fun installHost(packageId: String, vararg signatures: Signature) {
        val signing = SigningInfo()
        Shadow.extract<ShadowSigningInfo>(signing).setSignatures(arrayOf(*signatures))
        shadowPackageManager.addPackage(PackageInfo().apply {
            packageName = packageId
            applicationInfo = ApplicationInfo().apply {
                packageName = packageId
                this.uid = this@CommunityHostTrustTest.uid
            }
            signingInfo = signing
        })
        shadowPackageManager.setPackagesForUid(uid, packageId)
    }

    @Test
    fun `only exact package exclusively owning uid with pinned current signer is trusted`() {
        install(signature)
        assertTrue(trust.matches(uid, host))
        assertFalse(CommunityHostTrust(packageManager).matches(uid, host))
        assertFalse(trust.matches(uid, "example.host"))
        assertFalse(trust.matches(uid + 1, host))
        shadowPackageManager.setPackagesForUid(uid, host, "example.sibling")
        assertFalse(trust.matches(uid, host))
        shadowPackageManager.setPackagesForUid(uid, "example.sibling")
        assertFalse(trust.matches(uid, host))
        install(Signature("040506"))
        assertFalse(trust.matches(uid, host))
        install(signature, Signature("040506"))
        assertFalse(trust.matches(uid, host))
    }

    @Test
    fun `missing package or signer fails closed`() {
        shadowPackageManager.setPackagesForUid(uid, host)
        assertFalse(trust.matches(uid, host))
        install()
        assertFalse(trust.matches(uid, host))
    }

    @Test
    @Config(sdk = [31])
    fun `Tracker requires exact package and current signer on Android 12`() {
        val tracker = "com.tak.weartak_tracker"
        val trackerTrust = TrackerHostTrust(context, digest)
        install(signature)
        assertFalse(trackerTrust.matches(uid))
        installHost(tracker, signature)
        assertTrue(trackerTrust.matches(uid))
        assertFalse(trackerTrust.matches(uid + 1))
        assertFalse(TrackerHostTrust(context).matches(uid))
        shadowPackageManager.setPackagesForUid(uid, tracker, "example.sibling")
        assertFalse(trackerTrust.matches(uid))
        installHost(tracker, Signature("040506"))
        assertFalse(trackerTrust.matches(uid))
    }

    @Test
    @Config(sdk = [30])
    fun `Tracker fails closed on Android 11 even for the expected signer`() {
        installHost("com.tak.weartak_tracker", signature)
        assertFalse(TrackerHostTrust(context, digest).matches(uid))
    }

    @Test
    @Config(sdk = [31])
    fun `Tracker discovery returns unavailable and rechecks caller before both calls`() {
        installHost(DittoContractV1.TRACKER_PACKAGE, signature)
        val discovery = TrackerDittoDiscovery(TrackerHostTrust(context, digest))
        ShadowBinder.setCallingUid(uid)
        assertEquals(DittoContractV1.VERSION, discovery.getStatus().protocolVersion)
        assertEquals(DittoContractV1.AVAILABILITY_UNAVAILABLE, discovery.getStatus().availability)
        assertNull(discovery.getOwnership())

        ShadowBinder.setCallingUid(uid + 1)
        assertThrows(SecurityException::class.java) { discovery.getStatus() }
        assertThrows(SecurityException::class.java) { discovery.getOwnership() }
    }
}
