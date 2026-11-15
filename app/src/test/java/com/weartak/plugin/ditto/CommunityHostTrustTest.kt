package com.weartak.plugin.ditto

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo

@RunWith(RobolectricTestRunner::class)
class CommunityHostTrustTest {
    private val host = "com.aitech.weartak_civ"
    private val uid = 12345
    private val signature = Signature("010203")
    private val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
    private val packageManager = RuntimeEnvironment.getApplication().packageManager
    private val shadowPackageManager = shadowOf(packageManager)
    private val trust = CommunityHostTrust(packageManager, host, digest)

    private fun install(vararg signatures: Signature) {
        val signing = SigningInfo()
        Shadow.extract<ShadowSigningInfo>(signing).setSignatures(arrayOf(*signatures))
        shadowPackageManager.addPackage(PackageInfo().apply {
            packageName = host
            applicationInfo = ApplicationInfo().apply { packageName = host; this.uid = this@CommunityHostTrustTest.uid }
            signingInfo = signing
        })
        shadowPackageManager.setPackagesForUid(uid, host)
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
}
