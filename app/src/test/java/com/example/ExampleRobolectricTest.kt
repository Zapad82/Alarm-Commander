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

    @Test
    fun `voiceSpeechHelper handles onRmsChanged and onResults without StackOverflowError`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var lastRms = -1f
        var lastStatus = ""
        var recognizedOutput = ""

        val helper = com.example.mission.VoiceSpeechHelper(
            context = context,
            onStatusChanged = { _, status -> lastStatus = status },
            onRmsChanged = { rms -> lastRms = rms },
            onSpeechResult = { text -> recognizedOutput = text }
        )
        val listener = helper.createRecognitionListenerForTest(helper.buildRecognizerIntent())

        listener.onRmsChanged(7.5f)
        assertEquals(7.5f, lastRms, 0.01f)

        listener.onRmsChanged(99f)
        assertEquals(12f, lastRms, 0.01f)

        val bundle = android.os.Bundle().apply {
            putStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("Я полностью проснулся и готов к действию")
            )
        }
        listener.onResults(bundle)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(lastStatus.contains("Я полностью проснулся"))
        assertEquals("Я полностью проснулся и готов к действию", recognizedOutput)
        helper.release()
    }
}
