// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.ditto.group

import java.io.InputStream

internal class GroupControls(private val groups: PrivateGroups) {
    fun create(name: String) = groups.createMembership(name, select = true)
    fun select(id: String) = groups.select(id)
    fun options() = groups.options()
    fun selected() = groups.selected()
    fun invitationForExport(expectedGroupId: String) = groups.invitationForExport(expectedGroupId)

    fun importInvitation(name: String, input: InputStream) {
        val bytes = ByteArray(53)
        try {
            var count = 0
            while (count < bytes.size) {
                val value = input.read()
                if (value == -1) break
                bytes[count++] = value.toByte()
            }
            require(count == 52 && bytes.copyOfRange(0, 4).contentEquals(MAGIC)) {
                "Invalid group invitation"
            }
            val id = bytes.copyOfRange(4, 20)
            val key = bytes.copyOfRange(20, 52)
            try {
                groups.importMembership(name, id, key, select = true)
            } finally {
                key.fill(0)
            }
        } finally {
            bytes.fill(0)
        }
    }

    companion object {
        private val MAGIC = byteArrayOf('W'.code.toByte(), 'T'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte())
    }
}
