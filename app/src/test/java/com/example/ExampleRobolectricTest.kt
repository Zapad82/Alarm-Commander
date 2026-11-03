package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.permissions.PermissionManager
import com.example.permissions.SafetyAndPowerAuditor
import com.example.settings.CommanderSettingsState
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
    fun `read app name, evaluate permission checklist and pass safety-power audit`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Alarm Commander", appName)

        val permissionManager = PermissionManager(context)
        val checklist = permissionManager.evaluateAllPermissions()
        assertEquals("Чек-лист разрешений должен содержать все 9 пунктов", 9, checklist.size)

        val auditor = SafetyAndPowerAuditor(context)
        val report = auditor.runFullAudit(CommanderSettingsState())
        assertEquals(6, report.checks.size)
        assertTrue(
            "Все 6 проверок безопасности и энергоэффективности должны проходить успешно со штатными настройками",
            report.checks.all { it.passed }
        )
    }
}
