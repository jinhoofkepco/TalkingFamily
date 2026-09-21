package kr.family.homeway

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import kr.family.homeway.data.AppRepository
import kr.family.homeway.data.DemoStore
import kr.family.homeway.data.LocalStore
import kr.family.homeway.overlay.FloatingStarService
import kr.family.homeway.overlay.OverlayPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Uses a real WindowManager overlay rather than a Compose-only fake bubble. */
@RunWith(AndroidJUnit4::class)
class FloatingOverlayTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    @Before fun reset() {
        instrumentation.runOnMainSync {
            FloatingStarService.stop(context)
            FloatingStarService.setMessengerVisible(false)
        }
        setOverlayPermission(false)
        listOf("homeway_settings", "homeway_credentials", "homeway_demo", "floating_star").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        LocalStore.get(context).clear()
        if (Build.VERSION.SDK_INT >= 33) {
            device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        }
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.pressHome()
    }

    @After fun cleanUp() {
        val activities = mainActivities(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
        instrumentation.runOnMainSync {
            FloatingStarService.stop(context)
            FloatingStarService.setMessengerVisible(false)
            activities.forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
        compose.waitUntil(5000) {
            mainActivities(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).isEmpty()
        }
        setOverlayPermission(false)
        context.getSharedPreferences("floating_star", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun mainActivities(vararg stages: Stage): List<MainActivity> {
        var found = emptyList<MainActivity>()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            found = stages.flatMap { monitor.getActivitiesInStage(it) }.filterIsInstance<MainActivity>().distinct()
        }
        return found
    }

    private fun setOverlayPermission(allowed: Boolean) {
        device.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW ${if (allowed) "allow" else "deny"}")
    }

    private fun launchDemo(role: String = "child", overlay: Boolean, savedChoice: Boolean? = if (overlay) true else null) {
        runBlocking { AppRepository(context).startDemo(role) }
        DemoStore(context).reset()
        setOverlayPermission(overlay)
        if (savedChoice == null) context.getSharedPreferences("floating_star", Context.MODE_PRIVATE).edit().remove("enabled").commit()
        else OverlayPreferences(context).enabled = savedChoice
        assertEquals(overlay, Settings.canDrawOverlays(context))
        // ActivityScenario filters lifecycle events by the original intent. A real star tap
        // changes that intent to OPEN_CHAT, so use the unfiltered lifecycle monitor instead.
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
    }

    private fun awaitStar() {
        compose.waitUntil(10_000) {
            FloatingStarService.runtime.value.visible && mainActivities(Stage.STOPPED).isNotEmpty()
        }
        assertNotNull("The star must exist in a real overlay window", device.wait(Until.findObject(By.desc(STAR_DESCRIPTION)), 5000))
    }

    private fun screenshot(name: String) {
        // WindowManager overlay/activity transitions run outside the Compose test clock.
        SystemClock.sleep(500)
        instrumentation.waitForIdleSync()
        val dir = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun tapStar() {
        val star = device.wait(Until.findObject(By.desc(STAR_DESCRIPTION)), 5000)
        assertNotNull("Expected tappable overlay star", star)
        star!!.click()
        awaitMessenger()
    }

    private fun awaitMessenger() {
        compose.waitUntil(10_000) { mainActivities(Stage.RESUMED).isNotEmpty() }
        compose.waitUntil(10_000) {
            runCatching { compose.onAllNodesWithTag("chat-input").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
        compose.onNodeWithTag("chat-input").assertIsDisplayed()
        compose.waitUntil(5000) { !FloatingStarService.runtime.value.visible }
    }

    private fun assertNoTrackingService() {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        assertTrue("Demo overlay must not start GPS tracking", manager.getRunningServices(Int.MAX_VALUE)
            .none { it.service.className.endsWith("TrackingService") })
    }

    @Test fun deniedOverlayCanBeDismissedAndNormalMessengerStaysUsable() {
        launchDemo(overlay = false)
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("overlay-permission-prompt").fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("overlay-permission")
        compose.onNodeWithTag("overlay-prompt-dismiss").performClick()
        compose.onNodeWithTag("overlay-permission-prompt").assertDoesNotExist()
        compose.onNodeWithTag("chat-input").assertIsDisplayed().performTextInput("아빠 곧 도착해")
        compose.onNodeWithTag("chat-send").performClick()
        compose.waitUntil(5000) { DemoStore(context).read().events.any { it.payload.optString("text") == "아빠 곧 도착해" } }
        compose.onNodeWithTag("return-to-star").assertDoesNotExist()
        assertFalse(FloatingStarService.runtime.value.running)
        assertFalse(OverlayPreferences(context).enabled)
        assertNoTrackingService()
    }

    @Test fun grantingPermissionInSystemSettingsReturnsDirectlyToStar() {
        launchDemo(overlay = false)
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("overlay-permission-prompt").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("overlay-permission-open").performClick()
        assertTrue("The permission action must open real Android Settings",
            device.wait(Until.hasObject(By.pkg("com.android.settings")), 5000))
        // The shell grant avoids coupling this test to OEM-specific settings row wording.
        setOverlayPermission(true)
        device.pressBack()
        awaitStar()
        assertTrue(Settings.canDrawOverlays(context))
        tapStar()
        compose.onNodeWithTag("child-sticker-button").assertIsDisplayed()
        assertNoTrackingService()
    }

    @Test fun childStarOpensChatAndCloseOrBackReturnsToStar() {
        launchDemo(overlay = true)
        awaitStar()
        screenshot("overlay-star")
        val originalStar = device.findObject(By.desc(STAR_DESCRIPTION))
        val origin = originalStar.visibleCenter
        assertTrue(device.swipe(origin.x, origin.y, device.displayWidth / 8, device.displayHeight * 3 / 5, 30))
        compose.waitUntil(5000) { !OverlayPreferences(context).edgeRight }
        val movedY = OverlayPreferences(context).verticalFraction
        assertTrue("Dragging must update the persisted vertical position", movedY > 0.45f)
        tapStar()
        screenshot("overlay-chat")
        compose.onNodeWithTag("child-sticker-button").assertIsDisplayed()
        compose.onNodeWithTag("return-to-star").performClick()
        awaitStar()
        val returnedStar = device.findObject(By.desc(STAR_DESCRIPTION))
        assertTrue("Closing chat preserves the dragged left edge", returnedStar.visibleCenter.x < device.displayWidth / 2)
        assertEquals(movedY, OverlayPreferences(context).verticalFraction, 0.01f)
        tapStar()
        device.pressBack()
        awaitStar()
        assertNoTrackingService()
        // The notification must not offer a one-tap action that accidentally disables the star.
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.notification.extras.getString("android.title") == "메신저 별 아이콘 켜짐" }
        assertNotNull("Overlay foreground notification is required", notification)
        assertTrue(notification!!.notification.actions.orEmpty().none { it.title.toString() == "별 아이콘 끄기" })
        assertTrue(OverlayPreferences(context).enabled)
        assertNoTrackingService()
    }

    @Test fun exhaustedWindowRetriesKeepForegroundSessionAndRecoverAfterUnlockAndHome() {
        launchDemo(overlay = true)
        awaitStar()
        val instanceField = FloatingStarService::class.java.getDeclaredField("runningInstance").apply { isAccessible = true }
        val failureMethod = FloatingStarService::class.java.getDeclaredMethod("handleWindowFailure", String::class.java)
            .apply { isAccessible = true }
        val handlerField = FloatingStarService::class.java.getDeclaredField("handler").apply { isAccessible = true }
        val recoveryField = FloatingStarService::class.java.getDeclaredField("recoverWindow").apply { isAccessible = true }
        lateinit var service: FloatingStarService
        instrumentation.runOnMainSync {
            service = instanceField.get(null) as FloatingStarService
            // Simulate repeated WindowManager failures within one recovery burst. Each
            // failure cancels the earlier pending callback before consuming the next slot.
            repeat(4) { failureMethod.invoke(service, "instrumented_window_failure") }
        }
        assertTrue("Window failure must preserve the user's saved ON", OverlayPreferences(context).enabled)
        assertTrue("Exhausted retries must keep the existing session", FloatingStarService.runtime.value.running)
        assertFalse(FloatingStarService.runtime.value.visible)
        assertTrue(device.wait(Until.gone(By.desc(STAR_DESCRIPTION)), 5000))
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        assertTrue("The waiting service must still be in the foreground", manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == FloatingStarService::class.java.name && it.foreground })
        assertTrue("Foreground notification must survive the failed windows",
            context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 4100 })
        instrumentation.runOnMainSync {
            assertSame(service, instanceField.get(null))
            if (Build.VERSION.SDK_INT >= 29) {
                assertFalse("The exhausted burst must not leave a retry loop",
                    (handlerField.get(service) as Handler).hasCallbacks(recoveryField.get(service) as Runnable))
            }
        }
        val waiting = overlayDiagnostics("overlay-recovery-waiting")
        assertTrue(waiting, waiting.contains("overlayState=waiting_for_window"))
        assertTrue(waiting, waiting.contains("burstAttempts=3 recoveryPending=false"))

        device.sleep()
        compose.waitUntil(5000) { !context.getSystemService(PowerManager::class.java).isInteractive }
        assertTrue(FloatingStarService.runtime.value.running)
        assertTrue(OverlayPreferences(context).enabled)
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        awaitStar()
        instrumentation.runOnMainSync { assertSame("Unlock must recover the same service", service, instanceField.get(null)) }
        assertNull(FloatingStarService.runtime.value.error)
        assertTrue(overlayDiagnostics("overlay-recovery-after-unlock").contains("windowAttached=true"))
        screenshot("overlay-recovered-after-unlock")

        tapStar()
        device.pressHome()
        awaitStar()
        instrumentation.runOnMainSync { context.startActivity(FloatingStarService.chatIntent(context)) }
        awaitMessenger()
        screenshot("overlay-reopened-chat")
        device.pressBack()
        awaitStar()
        instrumentation.runOnMainSync { assertSame("Home and reopen must retain the session", service, instanceField.get(null)) }
        assertTrue(OverlayPreferences(context).enabled)
        assertNoTrackingService()
    }

    private fun overlayDiagnostics(name: String): String {
        val output = StringWriter()
        instrumentation.runOnMainSync { FloatingStarService.dumpDiagnostics(context, PrintWriter(output)) }
        val text = output.toString()
        val dir = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(dir, "$name.txt").writeText(text)
        return text
    }

    @Test fun explicitlyDisabledStarStaysOffWithPermissionGrantedOnNextLaunch() {
        launchDemo(overlay = true, savedChoice = false)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("chat-input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat-input").assertIsDisplayed()
        compose.onNodeWithTag("overlay-permission-prompt").assertDoesNotExist()
        compose.onNodeWithTag("return-to-star").assertDoesNotExist()
        assertTrue(OverlayPreferences(context).hasSavedChoice)
        assertFalse(OverlayPreferences(context).enabled)
        assertFalse(FloatingStarService.runtime.value.running)
        assertNoTrackingService()
    }

    private fun awaitStarDisableDialogFocus() {
        compose.onNodeWithTag("overlay-disable-dialog").assertIsDisplayed()
        // Compose visibility can precede WindowManager focus; native Back must target this dialog.
        compose.waitUntil(5000) {
            instrumentation.uiAutomation.windows.any { window ->
                window.isFocused && window.root?.findAccessibilityNodeInfosByText("별 아이콘을 끌까요?")?.isNotEmpty() == true
            }
        }
    }

    @Test fun childCanCancelStarDisableBeforeDeliberatelyConfirmingIt() {
        launchDemo(overlay = true)
        awaitStar()
        tapStar()
        compose.onNodeWithTag("chat-input").performTextInput("설정")
        compose.onNodeWithTag("chat-send").performClick()
        compose.onNodeWithTag("settings-overlay-menu-item").performClick()
        compose.onNodeWithTag("overlay-disable").performScrollTo().performClick()
        awaitStarDisableDialogFocus()
        assertTrue(OverlayPreferences(context).enabled)
        assertTrue(FloatingStarService.runtime.value.running)

        compose.onNodeWithTag("overlay-disable-cancel").performClick()
        compose.onNodeWithTag("overlay-disable-dialog").assertDoesNotExist()
        assertTrue(OverlayPreferences(context).enabled)
        compose.onNodeWithTag("overlay-disable").performScrollTo().performClick()
        awaitStarDisableDialogFocus()
        device.pressBack()
        compose.onNodeWithTag("overlay-disable-dialog").assertDoesNotExist()
        assertTrue(OverlayPreferences(context).enabled)

        compose.onNodeWithTag("overlay-disable").performScrollTo().performClick()
        awaitStarDisableDialogFocus()
        compose.onNodeWithTag("overlay-disable-confirm").performClick()
        compose.waitUntil(5000) { !FloatingStarService.runtime.value.running && !FloatingStarService.runtime.value.visible }
        compose.onNodeWithTag("overlay-disable-dialog").assertDoesNotExist()
        assertFalse(OverlayPreferences(context).enabled)
        assertNoTrackingService()
    }

    @Test fun guardianStarAlwaysOpensConversationInsteadOfLastLocationTab() {
        launchDemo(role = "guardian", overlay = true)
        awaitStar()
        tapStar()
        compose.onNodeWithTag("nav-chat").assertIsSelected()
        compose.onNodeWithTag("nav-location").performClick()
        compose.onNodeWithTag("nav-location").assertIsSelected()
        val original = mainActivities(Stage.RESUMED).single()
        instrumentation.runOnMainSync { original.recreate() }
        compose.waitUntil(5000) { mainActivities(Stage.RESUMED).any { it !== original } }
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("nav-location").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("nav-location").assertIsSelected()
        assertFalse("Recreation must not reopen the star or change the chosen tab", FloatingStarService.runtime.value.visible)
        device.pressBack()
        awaitStar()
        tapStar()
        compose.onNodeWithTag("nav-chat").assertIsSelected()
        assertNoTrackingService()
    }

    @Test fun permissionRevokedWhileMinimizedRemovesStarAndStopsOverlayOnly() {
        launchDemo(overlay = true)
        awaitStar()
        val demoBefore = DemoStore(context).read()
        setOverlayPermission(false)
        compose.waitUntil(5000) { !FloatingStarService.runtime.value.running && !FloatingStarService.runtime.value.visible }
        assertTrue(device.wait(Until.gone(By.desc(STAR_DESCRIPTION)), 5000))
        assertFalse(OverlayPreferences(context).enabled)
        assertEquals(demoBefore.sharingEnabled, DemoStore(context).read().sharingEnabled)
        assertNoTrackingService()
    }

    companion object {
        private const val STAR_DESCRIPTION = "메신저 열기. 끌어서 별 위치를 옮길 수 있어요."
    }
}
