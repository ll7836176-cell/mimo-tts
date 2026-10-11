package com.jingyue.mimotts

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiMoKeepAliveServiceContractTest {

    @Test
    fun keep_alive_service_is_non_exported_and_foreground_capable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, MiMoKeepAliveService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertFalse(serviceInfo.exported)
        assertTrue(serviceInfo.permission.isNullOrBlank())
        assertTrue(
            serviceInfo.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0,
        )
    }
}
