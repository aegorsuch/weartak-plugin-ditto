package com.weartak.plugin.ditto

import android.content.pm.PackageManager
import java.security.MessageDigest

internal class CommunityHostTrust(
    private val packageManager: PackageManager,
    private val expectedPackage: String = HOST_PACKAGE,
    expectedSigner: ByteArray = RELEASE_SIGNER_SHA256,
) {
    private val expectedSigner = expectedSigner.copyOf()

    fun matches(uid: Int, packageName: String): Boolean {
        if (packageName != expectedPackage) return false
        val packages = packageManager.getPackagesForUid(uid) ?: return false
        if (packages.size != 1 || packages.single() != expectedPackage) return false
        val info = try {
            packageManager.getPackageInfo(expectedPackage, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
        val signing = info.signingInfo ?: return false
        if (signing.hasMultipleSigners()) return false
        val certificate = signing.apkContentsSigners?.singleOrNull() ?: return false
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
        return MessageDigest.isEqual(expectedSigner, digest)
    }

    private companion object {
        const val HOST_PACKAGE = "com.aitech.weartak_civ"
        val RELEASE_SIGNER_SHA256 =
            "1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
