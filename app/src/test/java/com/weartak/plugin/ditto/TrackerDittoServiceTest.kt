package com.weartak.plugin.ditto

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import com.weartak.plugin.ditto.tracker.TrackerDittoServiceV1
import com.weartak.plugin.sdk.ditto.v1.DittoContractV1
import com.weartak.plugin.sdk.ditto.v1.IDittoDiscoveryV1
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class TrackerDittoServiceTest {
    private val component = ComponentName(DittoContractV1.PLUGIN_PACKAGE, DittoContractV1.TRACKER_SERVICE)

    @Test
    fun `Tracker service is disabled non-exported and protected by its own permission`() {
        val pm = RuntimeEnvironment.getApplication().packageManager
        val info = pm.getServiceInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        assertFalse(info.enabled)
        assertFalse(info.exported)
        assertEquals(DittoContractV1.TRACKER_PERMISSION, info.permission)
    }

    @Test
    fun `only explicit discovery intent returns the discovery binder`() {
        val controller = Robolectric.buildService(TrackerDittoServiceV1::class.java).create()
        try {
            val service = controller.get()
            val intent = Intent(DittoContractV1.TRACKER_ACTION)
            assertNull(service.onBind(null))
            assertNull(service.onBind(intent))
            assertNull(service.onBind(Intent("example.WRONG").setComponent(component)))
            val binder = checkNotNull(service.onBind(intent.setComponent(component)))
            assertEquals(IDittoDiscoveryV1.Stub.DESCRIPTOR, binder.interfaceDescriptor)
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Config(sdk = [30])
    fun `even explicit discovery returns no binder on Android 11`() {
        val controller = Robolectric.buildService(TrackerDittoServiceV1::class.java).create()
        try {
            assertNull(controller.get().onBind(Intent(DittoContractV1.TRACKER_ACTION).setComponent(component)))
        } finally {
            controller.destroy()
        }
    }
}
