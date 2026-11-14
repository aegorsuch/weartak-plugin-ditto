// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.ditto.group

import android.os.Bundle
import com.weartak.plugin.sdk.model.SettingsApplyMode
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.model.SettingsFieldParcel
import com.weartak.plugin.sdk.model.SettingsFieldTypes
import com.weartak.plugin.sdk.model.SettingsOptionParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.model.SettingsValidationErrorParcel
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.SettingsApplyValidator

internal class GroupSettings(private val groups: PrivateGroups) {
    fun schema(): SettingsSchemaParcel = SettingsSchemaParcel(
        title = "Ditto group",
        fields = listOf(SettingsFieldParcel(
            fieldId = GROUP_FIELD,
            label = "Active group",
            fieldType = SettingsFieldTypes.SELECT,
            options = groups.options().map { SettingsOptionParcel(it.id, it.label) },
            required = true,
            defaultValueLiteral = COMMUNITY,
            helpText = "Local selection only; Ditto is unavailable.",
        )),
    )

    fun values(): Bundle = Bundle().apply { putString(GROUP_FIELD, groups.selected()) }

    fun apply(incoming: Bundle?, mode: Int): SettingsApplyResultParcel {
        if (mode !in setOf(SettingsApplyMode.PARTIAL_PATCH, SettingsApplyMode.FULL_REPLACEMENT)) {
            return failure(mode, "INVALID_MODE", "Unsupported settings mode")
        }
        val previous = values()
        val result = SettingsApplyValidator.mergeSettings(schema(), previous, incoming, applyMode = mode)
        if (result is ContractValidationResult.Err) {
            return failure(mode, "INVALID_SETTINGS", "Invalid group selection")
        }
        result as ContractValidationResult.Ok
        val selected = result.value.values.getString(GROUP_FIELD)
            ?: return failure(mode, "INVALID_SETTINGS", "Invalid group selection")
        groups.select(selected)
        return SettingsApplyResultParcel(
            success = true,
            applyMode = mode,
            values = values(),
            anyValueChanged = selected != previous.getString(GROUP_FIELD),
        )
    }

    companion object {
        const val GROUP_FIELD = "activeGroup"

        fun failure(mode: Int, code: String, message: String) = SettingsApplyResultParcel(
            success = false,
            applyMode = mode,
            errors = listOf(SettingsValidationErrorParcel(fieldId = GROUP_FIELD, code = code, message = message)),
        )
    }
}
