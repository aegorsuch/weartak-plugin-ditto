package com.weartak.plugin.ditto

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleTransition
import com.weartak.plugin.sdk.model.HostSettingsParcel
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.PluginInfoParcel
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.model.SettingsValidationErrorParcel
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.v3.aidl.IWearTakPluginHostControlV3
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.PluginMetadataValidator

internal class DittoPluginBinder(
    private val context: Context,
    private val lifecycle: PluginLifecycleMachine,
) : IWearTakPlugin.Stub() {
    private val lock = Any()
    private var closed = false
    private var host: Host? = null

    private data class Host(val uid: Int, val packageName: String)

    override fun getPluginInfo() = bootstrapRead {
        PluginInfoParcel(
            pluginId = BuildConfig.APPLICATION_ID,
            displayName = "WearTAK Ditto (development)",
            versionName = BuildConfig.VERSION_NAME,
            packageName = BuildConfig.APPLICATION_ID,
            description = "Ditto runtime unavailable",
        )
    }

    override fun getCapabilities() = bootstrapRead { PluginCapabilitiesParcel() }
    override fun getApiVersion() = bootstrapRead { WearTakPluginContract.CONTRACT_API_VERSION }
    override fun getStatus() = bootstrapRead { PluginStatusParcel(lifecycle.snapshot(), UNAVAILABLE) }

    override fun initializePlugin(init: PluginInitParcel?, control: IWearTakPluginControl?) {
        initialize(init)
    }

    override fun initializePluginV2(
        init: PluginInitParcel?,
        control: IWearTakPluginControl?,
        controlV2: IWearTakPluginHostControlV2?,
    ) {
        initialize(init)
    }

    override fun initializePluginV3(
        init: PluginInitParcel?,
        control: IWearTakPluginControl?,
        controlV2: IWearTakPluginHostControlV2?,
        controlV3: IWearTakPluginHostControlV3?,
    ) {
        initialize(init)
    }

    private fun initialize(init: PluginInitParcel?) = synchronized(lock) {
        checkOpen()
        val uid = Binder.getCallingUid()
        if (init == null || init.pluginIdExpected != BuildConfig.APPLICATION_ID ||
            PluginMetadataValidator.validateInitAgainstPlugin(init, BuildConfig.APPLICATION_ID)
            is ContractValidationResult.Err || init.hostUid != uid ||
            !isTrustedPackage(uid, init.hostPackageName)
        ) {
            throw SecurityException("Plugin initialization rejected")
        }
        val proposed = Host(uid, init.hostPackageName)
        if (host != null && host != proposed) {
            throw SecurityException("Plugin host session mismatch")
        }
        val transition = lifecycle.dispatch(PluginLifecycleEvent.INITIALIZED)
        if (transition is PluginLifecycleTransition.Rejected) {
            Log.w(TAG, "Plugin initialization rejected: ${transition.reason}")
        } else {
            host = proposed
        }
    }

    override fun startPlugin() = withHost {
        Log.w(TAG, UNAVAILABLE)
        Unit
    }

    override fun stopPlugin() = withHost {
        dispatch(PluginLifecycleEvent.HOST_STOPPED)
    }

    override fun shutdownPlugin() = withHost {
        dispatch(PluginLifecycleEvent.HOST_SHUTDOWN)
    }

    override fun registerHostCallback(callback: IWearTakHostCallback?) = synchronized(lock) {
        checkOpen()
        if (host == null) {
            val uid = Binder.getCallingUid()
            val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
            if (packages.none { isTrustedPackage(uid, it) }) {
                throw SecurityException("Plugin caller not authorized")
            }
        } else {
            requireHost()
        }
        if (callback != null) dispatch(PluginLifecycleEvent.HOST_CALLBACK_REGISTERED)
    }

    override fun unregisterHostCallback(callback: IWearTakHostCallback?) = withHost { Unit }
    override fun getSettingsSchema() = bootstrapRead { SettingsSchemaParcel() }
    override fun getSettingsValues() = bootstrapRead { Bundle() }
    override fun onHostSettingsChanged(hostSettings: HostSettingsParcel?) = withHost { Unit }

    override fun applySettings(values: Bundle?) = withHost {
        Log.w(TAG, "Settings unavailable")
        Unit
    }

    override fun applySettingsWithResult(values: Bundle?, applyMode: Int) = withHost {
        SettingsApplyResultParcel(
            success = false,
            applyMode = applyMode,
            errors = listOf(SettingsValidationErrorParcel(code = "UNAVAILABLE", message = UNAVAILABLE)),
        )
    }

    override fun getMainUiSchema() = bootstrapRead { PluginMainUiSchemaParcel() }
    override fun getMainUiState() = bootstrapRead { Bundle() }

    override fun invokeMainUiAction(actionId: String?, args: Bundle?) = withHost {
        Log.w(TAG, "Main UI unavailable")
        Unit
    }

    internal fun close() = synchronized(lock) {
        closed = true
        host = null
        lifecycle.dispatch(PluginLifecycleEvent.RESET)
    }

    private fun checkOpen() {
        if (closed) throw SecurityException("Plugin binding closed")
    }

    private fun requireHost() {
        checkOpen()
        val session = host ?: throw SecurityException("Plugin host not initialized")
        val uid = Binder.getCallingUid()
        if (uid != session.uid || !isTrustedPackage(uid, session.packageName)) {
            throw SecurityException("Plugin caller not authorized")
        }
    }

    private fun isTrustedPackage(uid: Int, packageName: String): Boolean {
        val pm = context.packageManager
        if (packageName !in pm.getPackagesForUid(uid).orEmpty()) return false
        if (pm.checkPermission(WearTakPluginContract.PERMISSION_BIND_PLUGIN, packageName) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        if (pm.checkSignatures(packageName, context.packageName) != PackageManager.SIGNATURE_MATCH) return false
        val permission = try {
            pm.getPermissionInfo(WearTakPluginContract.PERMISSION_BIND_PLUGIN, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        return permission.protection == PermissionInfo.PROTECTION_SIGNATURE &&
            pm.checkSignatures(permission.packageName, context.packageName) == PackageManager.SIGNATURE_MATCH
    }

    private inline fun <T> bootstrapRead(block: () -> T): T = synchronized(lock) {
        checkOpen()
        if (host != null) requireHost()
        block()
    }

    private inline fun <T> withHost(block: () -> T): T = synchronized(lock) {
        requireHost()
        block()
    }

    private fun dispatch(event: PluginLifecycleEvent) {
        val transition = lifecycle.dispatch(event)
        if (transition is PluginLifecycleTransition.Rejected) {
            Log.w(TAG, "Lifecycle event $event rejected: ${transition.reason}")
        }
    }

    companion object {
        private const val TAG = "DittoPlugin"
        internal const val UNAVAILABLE = "Ditto runtime and identity provisioning unavailable"
    }
}
