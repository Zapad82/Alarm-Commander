package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.permissions.PermissionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name and evaluate permission checklist from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Alarm Commander", appName)

        val permissionManager = PermissionManager(context)
        val checklist = permissionManager.evaluateAllPermissions()
        assertTrue("Чек-лист разрешений должен содержать все 9 пунктов", checklist.size == 9)
    }
}
