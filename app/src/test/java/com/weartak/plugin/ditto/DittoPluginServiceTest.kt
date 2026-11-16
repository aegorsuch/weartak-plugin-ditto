package com.weartak.plugin.ditto

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.Signature
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import com.weartak.plugin.ditto.group.GroupBlobStore
import com.weartak.plugin.ditto.group.GroupSettings
import com.weartak.plugin.ditto.group.PrivateGroups
import com.weartak.plugin.sdk.WearTakPluginContract
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleEvent
import com.weartak.plugin.sdk.lifecycle.PluginLifecycleMachine
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.SettingsApplyMode
import com.weartak.plugin.sdk.routestatus.RouteConnectionState
import com.weartak.plugin.sdk.routestatus.RouteStatusContract
import com.weartak.plugin.sdk.routestatus.RouteStatusResultCode
import com.weartak.plugin.sdk.routestatus.aidl.IPluginRouteStatusControlV1
import com.weartak.plugin.sdk.routestatus.model.PluginRouteStatusParcel
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.SettingsSchemaContractValidator
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBinder
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class DittoPluginServiceTest {
    private val hostUid = 12345
    private val otherUid = 12346
    private val hostPackage = "example.host"

    private fun boundBinder(groups: PrivateGroups? = null): DittoPluginBinder {
        if (groups != null) {
            val lifecycle = PluginLifecycleMachine()
            lifecycle.dispatch(PluginLifecycleEvent.BIND_ATTACHED)
            return DittoPluginBinder(RuntimeEnvironment.getApplication(), lifecycle) { groups }
        }
        val service = Robolectric.buildService(DittoPluginService::class.java).create().get()
        return service.onBind(Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE)) as DittoPluginBinder
    }

    private class TestGroupStorage : GroupBlobStore {
        private var value: ByteArray? = null
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(bytes: ByteArray) { value = bytes.copyOf() }
    }

    @Suppress("DEPRECATION")
    private fun installCaller(
        name: String,
        uid: Int,
        signed: Boolean = true,
        granted: Boolean = true,
    ) {
        val app = RuntimeEnvironment.getApplication()
        val pm = shadowOf(app.packageManager)
        val signature = Signature("010203")
        pm.getInternalMutablePackageInfo(app.packageName).signatures = arrayOf(signature)
        pm.addPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply { packageName = name; this.uid = uid }
            signatures = arrayOf(if (signed) signature else Signature("040506"))
            requestedPermissions = arrayOf(WearTakPluginContract.PERMISSION_BIND_PLUGIN)
            requestedPermissionsFlags = intArrayOf(if (granted) PackageInfo.REQUESTED_PERMISSION_GRANTED else 0)
        })
        pm.setPackagesForUid(uid, name)
    }

    private fun init(uid: Int = hostUid, name: String = hostPackage) =
        PluginInitParcel(name, uid, BuildConfig.APPLICATION_ID)

    private class QueuedExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return tasks.toMutableList().also { tasks.clear() }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    private class RouteControl : IPluginRouteStatusControlV1.Stub() {
        val reports = mutableListOf<PluginRouteStatusParcel>()
        val removals = mutableListOf<String>()
        var result = RouteStatusResultCode.OK
        var rejectDeathRegistration = false
        private var recipient: IBinder.DeathRecipient? = null
        private var alive = true
        override fun isBinderAlive() = alive
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (rejectDeathRegistration) throw RemoteException("host unavailable")
            this.recipient = recipient
        }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            if (this.recipient !== recipient) return false
            this.recipient = null
            return true
        }
        fun die() {
            alive = false
            recipient?.binderDied()
            recipient = null
        }
        override fun getContractVersion() = RouteStatusContract.CONTRACT_VERSION
        override fun reportRouteStatus(status: PluginRouteStatusParcel): Int {
            reports.add(status)
            return result
        }
        override fun removeRoute(routeId: String): Int {
            removals.add(routeId)
            return result
        }
        override fun clearRoutes() = result
    }

    private fun routeInit(control: RouteControl, enabled: Boolean = true) = init().copy(
        hostExtras = Bundle().apply {
            putBoolean(RouteStatusContract.EXTRA_CAPABILITY, enabled)
            putBinder(RouteStatusContract.EXTRA_CONTROL, control)
        },
    )

    private fun binderWithExecutor(executor: QueuedExecutor): DittoPluginBinder {
        val lifecycle = PluginLifecycleMachine()
        lifecycle.dispatch(PluginLifecycleEvent.BIND_ATTACHED)
        return DittoPluginBinder(RuntimeEnvironment.getApplication(), lifecycle, executor)
    }

    @After
    fun resetCaller() = ShadowBinder.reset()

    @Test
    fun `service requires expected action and signature permission`() {
        val service = Robolectric.buildService(DittoPluginService::class.java).create().get()
        assertNull(service.onBind(Intent("unexpected")))
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getServiceInfo(
            ComponentName(app, DittoPluginService::class.java),
            PackageManager.GET_META_DATA,
        )
        assertTrue(info.exported)
        assertEquals(WearTakPluginContract.PERMISSION_BIND_PLUGIN, info.permission)
        val activity = app.packageManager.getActivityInfo(
            ComponentName(app, GroupActivity::class.java), 0,
        )
        assertTrue(activity.exported)
        assertEquals(WearTakPluginContract.PERMISSION_BIND_PLUGIN, activity.permission)
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
        assertNotNull(launch)
        assertEquals(Intent.ACTION_MAIN, launch!!.action)
        val permission = app.packageManager.getPermissionInfo(info.permission, 0)
        assertEquals(
            PermissionInfo.PROTECTION_SIGNATURE,
            permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE,
        )
        val packageInfo = app.packageManager.getPackageInfo(
            app.packageName,
            PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS,
        )
        assertTrue(packageInfo.applicationInfo!!.metaData.getBoolean("com.google.android.wearable.standalone"))
        val dittoLicense = app.assets.open("licenses/ditto-binary-license.txt")
            .bufferedReader()
            .use { it.readText() }
        assertTrue(dittoLicense.startsWith("Ditto Binary License"))
        val dittoPermissions = setOf(
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.INTERNET",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.CHANGE_WIFI_MULTICAST_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            "android.permission.NEARBY_WIFI_DEVICES",
        )
        assertTrue(packageInfo.requestedPermissions.orEmpty().none { it in dittoPermissions })
    }

    @Test
    fun `group screen protects its window and exposes local controls`() {
        val restored = Bundle().apply {
            putString("pendingImportName", "Lake")
            putString("waitingForExport", "a".repeat(32))
        }
        val controller = Robolectric.buildActivity(GroupActivity::class.java).create(restored)
        try {
            val activity = controller.get()
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            fun buttons(view: View): List<String> = when (view) {
                is Button -> listOf(view.text.toString())
                is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
                else -> emptyList()
            }
            val labels = buttons(activity.findViewById(android.R.id.content))
            assertTrue(labels.contains(activity.getString(R.string.group_create)))
            assertTrue(labels.contains(activity.getString(R.string.group_import)))
            assertTrue(labels.contains(activity.getString(R.string.group_export)))
            val saved = Bundle()
            controller.saveInstanceState(saved)
            assertEquals("Lake", saved.getString("pendingImportName"))
            assertEquals("a".repeat(32), saved.getString("waitingForExport"))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `valid host initialization never starts unavailable transport`() {
        installCaller(hostPackage, hostUid)
        ShadowBinder.setCallingUid(hostUid)
        val binder = boundBinder()
        assertEquals("com.weartak.plugin.ditto", RuntimeEnvironment.getApplication().packageName)
        assertEquals(BuildConfig.APPLICATION_ID, binder.getPluginInfo().pluginId)
        assertEquals(BuildConfig.APPLICATION_ID, binder.getPluginInfo().packageName)
        assertTrue(binder.getCapabilities().externalSettingsActivity)
        assertFalse(binder.getCapabilities().hostedMainUi)
        binder.initializePluginV3(init(), null, null, null)
        binder.startPlugin()
        assertEquals(PluginLifecycleState.READY, binder.getStatus().state)
        assertEquals(DittoPluginBinder.UNAVAILABLE, binder.getStatus().message)
        binder.shutdownPlugin()
        binder.initializePluginV2(init(), null, null)
        assertEquals(PluginLifecycleState.SHUTDOWN, binder.getStatus().state)
    }

    @Test
    fun `authenticated route reports unavailable and retries rejected report with increasing sequence`() {
        installCaller(hostPackage, hostUid)
        installCaller("example.other", otherUid)
        val executor = QueuedExecutor()
        val binder = binderWithExecutor(executor)
        val control = RouteControl()
        ShadowBinder.setCallingUid(otherUid)
        assertThrows(SecurityException::class.java) {
            binder.initializePluginV2(routeInit(control), null, null)
        }
        executor.runAll()
        assertTrue(control.reports.isEmpty())
        ShadowBinder.setCallingUid(hostUid)
        binder.initializePluginV2(routeInit(control, enabled = false), null, null)
        executor.runAll()
        assertTrue(control.reports.isEmpty())
        control.result = RouteStatusResultCode.INACTIVE_SESSION
        binder.initializePluginV2(routeInit(control), null, null)
        assertTrue(control.reports.isEmpty())
        executor.runAll()
        control.result = RouteStatusResultCode.OK
        binder.initializePluginV3(routeInit(control), null, null, null)
        executor.runAll()
        assertEquals(2, control.reports.size)
        assertEquals(control.reports.first().sequence + 1, control.reports.last().sequence)
        control.reports.forEach {
            assertEquals(DittoRouteStatus.ROUTE_ID, it.routeId)
            assertEquals("Ditto", it.label)
            assertEquals(RouteConnectionState.DISCONNECTED, it.state)
            assertEquals("Runtime unavailable", it.statusText)
        }
        binder.shutdownPlugin()
        executor.runAll()
        assertEquals(listOf(DittoRouteStatus.ROUTE_ID), control.removals)
        binder.close()
    }

    @Test
    fun `stale route updates are fenced on replacement death and close`() {
        installCaller(hostPackage, hostUid)
        ShadowBinder.setCallingUid(hostUid)
        val executor = QueuedExecutor()
        val binder = binderWithExecutor(executor)
        val old = RouteControl()
        val replacement = RouteControl()
        binder.initializePluginV2(routeInit(old), null, null)
        binder.initializePluginV2(routeInit(replacement), null, null)
        executor.runAll()
        assertTrue(old.reports.isEmpty())
        assertEquals(1, replacement.reports.size)
        binder.initializePluginV2(routeInit(replacement), null, null)
        replacement.die()
        executor.runAll()
        assertEquals(1, replacement.reports.size)
        val reconnected = RouteControl()
        binder.initializePluginV2(routeInit(reconnected), null, null)
        executor.runAll()
        assertEquals(RouteConnectionState.DISCONNECTED, reconnected.reports.single().state)
        assertTrue(reconnected.reports.single().sequence > replacement.reports.single().sequence)
        binder.initializePluginV2(routeInit(reconnected), null, null)
        binder.close()
        executor.runAll()
        assertEquals(1, reconnected.reports.size)
    }

    @Test
    fun `capability withdrawal removes route and failed death registration does not report`() {
        installCaller(hostPackage, hostUid)
        ShadowBinder.setCallingUid(hostUid)
        val executor = QueuedExecutor()
        val binder = binderWithExecutor(executor)
        val control = RouteControl()
        binder.initializePluginV2(routeInit(control), null, null)
        executor.runAll()
        binder.initializePluginV2(routeInit(control, enabled = false), null, null)
        executor.runAll()
        assertEquals(listOf(DittoRouteStatus.ROUTE_ID), control.removals)
        val unavailable = RouteControl().apply { rejectDeathRegistration = true }
        binder.initializePluginV2(routeInit(unavailable), null, null)
        executor.runAll()
        assertTrue(unavailable.reports.isEmpty())
        unavailable.rejectDeathRegistration = false
        binder.initializePluginV2(routeInit(unavailable), null, null)
        executor.runAll()
        assertEquals(RouteConnectionState.DISCONNECTED, unavailable.reports.single().state)
        binder.close()
    }

    @Test
    fun `core initialization sequence accepts v2 and v3 without starting runtime`() {
        installCaller(hostPackage, hostUid)
        ShadowBinder.setCallingUid(hostUid)
        for (v3 in listOf(false, true)) {
            val binder = boundBinder()
            assertEquals(BuildConfig.APPLICATION_ID, binder.getPluginInfo().pluginId)
            assertEquals(WearTakPluginContract.CONTRACT_API_VERSION, binder.getApiVersion())
            if (v3) {
                binder.initializePluginV3(init(), null, null, null)
            } else {
                binder.initializePluginV2(init(), null, null)
            }
            binder.registerHostCallback(IWearTakHostCallback.Stub.asInterface(Binder()))
            binder.startPlugin()
            assertEquals(PluginLifecycleState.READY, binder.getStatus().state)
            assertEquals(DittoPluginBinder.UNAVAILABLE, binder.getStatus().message)
        }
    }

    @Test
    fun `community capability is only tracked after host initialization and invalidated on stop`() {
        installCaller(hostPackage, hostUid)
        ShadowBinder.setCallingUid(hostUid)
        val lifecycle = PluginLifecycleMachine()
        lifecycle.dispatch(PluginLifecycleEvent.BIND_ATTACHED)
        val candidate = CommunityControlCandidate(trustHost = { uid, name -> uid == hostUid && name == hostPackage })
        val binder = DittoPluginBinder(RuntimeEnvironment.getApplication(), lifecycle, communityControl = candidate)
        val control = RouteControl()
        val init = init().copy(hostExtras = Bundle().apply {
            putInt("com.weartak.plugin.ditto.community.version", 1)
            putBinder("com.weartak.plugin.ditto.community.v1", control)
        })
        assertFalse(candidate.hasCandidate)
        binder.initializePluginV2(init, null, null)
        assertTrue(candidate.hasCandidate)
        assertEquals(DittoPluginBinder.UNAVAILABLE, binder.getStatus().message)
        binder.stopPlugin()
        assertFalse(candidate.hasCandidate)
        binder.close()
    }

    @Test
    fun `invalid initialization and settings fail closed`() {
        installCaller(hostPackage, hostUid)
        ShadowBinder.setCallingUid(hostUid)
        val groups = PrivateGroups.open(TestGroupStorage(), SecretKeySpec(ByteArray(32) { 2 }, "AES"))
        val binder = boundBinder(groups)
        assertThrows(SecurityException::class.java) { binder.initializePluginV2(null, null, null) }
        assertThrows(SecurityException::class.java) {
            binder.initializePluginV2(PluginInitParcel(hostPackage, hostUid, ""), null, null)
        }
        assertThrows(SecurityException::class.java) {
            binder.initializePluginV2(PluginInitParcel(hostPackage, hostUid, "wrong.package"), null, null)
        }
        assertThrows(SecurityException::class.java) { binder.startPlugin() }
        assertEquals(PluginLifecycleState.BOUND, binder.getStatus().state)
        assertTrue(binder.getSettingsSchema().fields.isEmpty())
        assertThrows(SecurityException::class.java) {
            binder.applySettingsWithResult(Bundle(), SettingsApplyMode.PARTIAL_PATCH)
        }
        binder.initializePluginV2(init(), null, null)
        val result = binder.applySettingsWithResult(
            Bundle().apply { putString(GroupSettings.GROUP_FIELD, "unknown") },
            SettingsApplyMode.PARTIAL_PATCH,
        )
        assertFalse(result.success)
        assertEquals("INVALID_SETTINGS", result.errors.single().code)
        assertEquals("community", binder.getSettingsValues().getString(GroupSettings.GROUP_FIELD))
    }

    @Test
    fun `authenticated hosted selection persists and never exposes membership key`() {
        installCaller(hostPackage, hostUid)
        installCaller("example.other", otherUid)
        ShadowBinder.setCallingUid(hostUid)
        val storage = TestGroupStorage()
        val wrappingKey = SecretKeySpec(ByteArray(32) { 2 }, "AES")
        val groups = PrivateGroups.open(storage, wrappingKey)
        groups.importMembership("SpringLake", ByteArray(16) { 1 }, ByteArray(32) { 3 })
        val groupId = groups.options().last().id
        val binder = boundBinder(groups)
        binder.initializePluginV2(init(), null, null)
        val schema = binder.getSettingsSchema()
        assertTrue(SettingsSchemaContractValidator.validate(schema) is ContractValidationResult.Ok)
        assertEquals(setOf("community", groupId), schema.fields.single().options.map { it.value }.toSet())
        val result = binder.applySettingsWithResult(
            Bundle().apply { putString(GroupSettings.GROUP_FIELD, groupId) },
            SettingsApplyMode.PARTIAL_PATCH,
        )
        assertTrue(result.success)
        assertEquals(setOf(GroupSettings.GROUP_FIELD), result.values.keySet())
        assertEquals(groupId, PrivateGroups.open(storage, wrappingKey).selected())
        ShadowBinder.setCallingUid(otherUid)
        assertThrows(SecurityException::class.java) {
            binder.applySettingsWithResult(
                Bundle().apply { putString(GroupSettings.GROUP_FIELD, "community") },
                SettingsApplyMode.PARTIAL_PATCH,
            )
        }
        assertEquals(groupId, groups.selected())
    }

    @Test
    fun `initialization rejects spoofed uid package signature and permission`() {
        installCaller(hostPackage, hostUid)
        installCaller("example.unsigned", otherUid, signed = false)
        ShadowBinder.setCallingUid(hostUid)
        val binder = boundBinder()
        assertThrows(SecurityException::class.java) { binder.initializePluginV2(init(otherUid), null, null) }
        assertThrows(SecurityException::class.java) {
            binder.initializePluginV2(init(name = "example.unsigned"), null, null)
        }
        ShadowBinder.setCallingUid(otherUid)
        assertThrows(SecurityException::class.java) {
            binder.initializePluginV2(init(otherUid, "example.unsigned"), null, null)
        }
        installCaller("example.denied", otherUid, granted = false)
        assertThrows(SecurityException::class.java) {
            binder.initializePluginV2(init(otherUid, "example.denied"), null, null)
        }
        assertEquals(PluginLifecycleState.BOUND, binder.getStatus().state)
    }

    @Test
    fun `relayed binder cannot control or read authenticated session`() {
        installCaller(hostPackage, hostUid)
        installCaller("example.other", otherUid)
        ShadowBinder.setCallingUid(hostUid)
        val binder = boundBinder()
        val callback = IWearTakHostCallback.Stub.asInterface(Binder())
        binder.registerHostCallback(callback)
        binder.initializePluginV2(init(), null, null)
        ShadowBinder.setCallingUid(otherUid)
        assertThrows(SecurityException::class.java) { binder.initializePluginV2(init(otherUid, "example.other"), null, null) }
        assertThrows(SecurityException::class.java) { binder.startPlugin() }
        assertThrows(SecurityException::class.java) { binder.stopPlugin() }
        assertThrows(SecurityException::class.java) { binder.shutdownPlugin() }
        assertThrows(SecurityException::class.java) { binder.registerHostCallback(null) }
        assertThrows(SecurityException::class.java) { binder.unregisterHostCallback(null) }
        assertThrows(SecurityException::class.java) { binder.onHostSettingsChanged(null) }
        assertThrows(SecurityException::class.java) { binder.applySettings(Bundle()) }
        assertThrows(SecurityException::class.java) { binder.applySettingsWithResult(Bundle(), 0) }
        assertThrows(SecurityException::class.java) { binder.invokeMainUiAction("x", Bundle()) }
        assertThrows(SecurityException::class.java) { binder.getStatus() }
        assertThrows(SecurityException::class.java) { binder.getPluginInfo() }
        assertThrows(SecurityException::class.java) { binder.getCapabilities() }
        assertThrows(SecurityException::class.java) { binder.getApiVersion() }
        assertThrows(SecurityException::class.java) { binder.getSettingsSchema() }
        assertThrows(SecurityException::class.java) { binder.getSettingsValues() }
        assertThrows(SecurityException::class.java) { binder.getMainUiSchema() }
        assertThrows(SecurityException::class.java) { binder.getMainUiState() }
        ShadowBinder.setCallingUid(hostUid)
        assertEquals(PluginLifecycleState.READY, binder.getStatus().state)
        binder.shutdownPlugin()
        assertEquals(PluginLifecycleState.SHUTDOWN, binder.getStatus().state)
    }

    @Test
    fun `unbind and destruction invalidate old handles and new binding requires initialization`() {
        installCaller(hostPackage, hostUid)
        ShadowBinder.setCallingUid(hostUid)
        val service = Robolectric.buildService(DittoPluginService::class.java).create().get()
        val intent = Intent(WearTakPluginContract.ACTION_PLUGIN_SERVICE)
        val old = service.onBind(intent) as DittoPluginBinder
        assertSame(old, service.onBind(intent))
        old.initializePluginV2(init(), null, null)
        service.onUnbind(intent)
        assertThrows(SecurityException::class.java) { old.getPluginInfo() }
        assertThrows(SecurityException::class.java) { old.initializePluginV2(init(), null, null) }
        assertThrows(SecurityException::class.java) { old.shutdownPlugin() }
        val fresh = service.onBind(intent) as DittoPluginBinder
        assertNotSame(old, fresh)
        assertEquals(PluginLifecycleState.BOUND, fresh.getStatus().state)
        assertThrows(SecurityException::class.java) { fresh.startPlugin() }
        fresh.initializePluginV2(init(), null, null)
        service.onDestroy()
        assertThrows(SecurityException::class.java) { fresh.startPlugin() }
    }

    @Test
    fun `permission revocation invalidates existing session`() {
        installCaller(hostPackage, hostUid)
        ShadowBinder.setCallingUid(hostUid)
        val binder = boundBinder()
        binder.initializePluginV2(init(), null, null)
        installCaller(hostPackage, hostUid, granted = false)
        assertThrows(SecurityException::class.java) { binder.stopPlugin() }
    }

    @Test
    fun `only inert getters are available before initialization`() {
        installCaller(hostPackage, hostUid)
        installCaller("example.denied", otherUid, granted = false)
        val binder = boundBinder()
        ShadowBinder.setCallingUid(otherUid)
        assertEquals(BuildConfig.APPLICATION_ID, binder.getPluginInfo().pluginId)
        assertEquals(PluginLifecycleState.BOUND, binder.getStatus().state)
        assertTrue(binder.getSettingsValues().isEmpty)
        assertTrue(binder.getMainUiState().isEmpty)
        assertThrows(SecurityException::class.java) { binder.registerHostCallback(null) }
        assertThrows(SecurityException::class.java) { binder.shutdownPlugin() }
        ShadowBinder.setCallingUid(hostUid)
        binder.registerHostCallback(IWearTakHostCallback.Stub.asInterface(Binder()))
        binder.initializePluginV2(init(), null, null)
        assertEquals(PluginLifecycleState.READY, binder.getStatus().state)
    }

    @Test
    fun `same uid cannot replace claimed session package`() {
        installCaller(hostPackage, hostUid)
        installCaller("example.sibling", hostUid)
        shadowOf(RuntimeEnvironment.getApplication().packageManager)
            .setPackagesForUid(hostUid, hostPackage, "example.sibling")
        ShadowBinder.setCallingUid(hostUid)
        val binder = boundBinder()
        binder.initializePluginV2(init(), null, null)
        assertThrows(SecurityException::class.java) {
            binder.initializePluginV2(init(name = "example.sibling"), null, null)
        }
        assertEquals(PluginLifecycleState.READY, binder.getStatus().state)
    }

    @Test
    fun `permission definition must be signature protected and trusted`() {
        installCaller(hostPackage, hostUid)
        installCaller("example.unsigned", otherUid, signed = false)
        ShadowBinder.setCallingUid(hostUid)
        val app = RuntimeEnvironment.getApplication()
        val pm = shadowOf(app.packageManager)
        val original = app.packageManager.getPermissionInfo(WearTakPluginContract.PERMISSION_BIND_PLUGIN, 0)
        val binder = boundBinder()
        pm.addPermissionInfo(PermissionInfo(original).apply { protectionLevel = PermissionInfo.PROTECTION_NORMAL })
        assertThrows(SecurityException::class.java) { binder.initializePluginV2(init(), null, null) }
        pm.addPermissionInfo(PermissionInfo(original).apply { packageName = "example.unsigned" })
        assertThrows(SecurityException::class.java) { binder.initializePluginV2(init(), null, null) }
        pm.addPermissionInfo(original)
        binder.initializePluginV2(init(), null, null)
        assertEquals(PluginLifecycleState.READY, binder.getStatus().state)
    }
}
