package com.studypath.app

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studypath.app.ui.StudyButton
import com.studypath.app.ui.auth.WelcomeActivity
import com.studypath.app.ui.dp
import com.studypath.app.ui.tint
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StudyButtonDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(block: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (!block() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(40)
        assertTrue("Expected state before timeout", block())
    }
    private fun capture(name: String) {
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "rose-buttons").apply { mkdirs() }
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun pressCancelDisableKeyboardAndReducedMotionPreserveActivation() {
        assumeTrue(BuildConfig.USE_FIREBASE_EMULATORS)
        ActivityScenario.launch(WelcomeActivity::class.java).use { scenario ->
            lateinit var button: StudyButton
            scenario.onActivity { button = it.findViewById(R.id.signUpButton) }
            waitFor { var enabled = false; main { enabled = button.isEnabled && button.width > 0 }; enabled }
            capture("01-welcome")
            var clicks = 0
            var width = 0; var height = 0
            main {
                button.setOnClickListener { clicks++ }
                width = button.width; height = button.height
                assertTrue(height >= button.context.dp(56))
            }
            var downAt = 0L
            fun event(action: Int, outside: Boolean = false) = main {
                if (action == MotionEvent.ACTION_DOWN) downAt = SystemClock.uptimeMillis()
                val coordinate = if (outside) -button.context.dp(30).toFloat() else button.width / 2f
                val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, coordinate, button.height / 2f, 0)
                button.dispatchTouchEvent(event); event.recycle()
            }
            event(MotionEvent.ACTION_DOWN)
            SystemClock.sleep(260)
            main {
                assertEquals(width, button.width); assertEquals(height, button.height)
                assertEquals(1f, button.scaleX, 0f)
                if (ValueAnimator.areAnimatorsEnabled()) assertEquals(button.context.dp(4).toFloat(), button.faceOffset, .1f)
            }
            capture("02-pressed")
            event(MotionEvent.ACTION_MOVE, outside = true)
            event(MotionEvent.ACTION_UP, outside = true)
            SystemClock.sleep(220)
            main { assertEquals(0, clicks); assertEquals(0f, button.faceOffset, .1f) }
            event(MotionEvent.ACTION_DOWN); SystemClock.sleep(160); event(MotionEvent.ACTION_UP)
            instrumentation.waitForIdleSync()
            main { assertEquals(1, clicks) }
            event(MotionEvent.ACTION_DOWN); SystemClock.sleep(160)
            main { button.isEnabled = false }
            event(MotionEvent.ACTION_UP)
            main {
                assertEquals(1, clicks); assertEquals(0f, button.faceOffset, 0f)
                assertEquals(button.context.tint(R.color.sp_blush), button.backgroundTintList!!.getColorForState(button.drawableState, 0))
                assertEquals(button.context.tint(R.color.sp_disabled_text), button.currentTextColor)
            }
            capture("03-disabled")
            main {
                button.isEnabled = true; button.isFocusableInTouchMode = true; button.requestFocus()
                button.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                button.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            instrumentation.waitForIdleSync()
            main { assertEquals(2, clicks); assertEquals(0f, button.faceOffset, 0f) }
            capture("04-keyboard-focus")
            main { assertTrue(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)) }
            instrumentation.waitForIdleSync()
            main { assertEquals(3, clicks) }

            val resolver = instrumentation.targetContext.contentResolver
            val previous = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
            fun setting(value: String?) {
                val command = if (value == null) "settings delete global animator_duration_scale"
                    else "settings put global animator_duration_scale $value"
                instrumentation.uiAutomation.executeShellCommand(command).use {
                    java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
                }
            }
            try {
                setting("0")
                waitFor { !ValueAnimator.areAnimatorsEnabled() }
                // Restore normal button touch focus after the keyboard-specific setup.
                main { button.clearFocus(); button.isFocusableInTouchMode = false }
                event(MotionEvent.ACTION_DOWN); SystemClock.sleep(180)
                main { assertEquals(0f, button.faceOffset, 0f) }
                capture("05-reduced-motion")
                event(MotionEvent.ACTION_UP); instrumentation.waitForIdleSync()
                main { assertEquals(4, clicks) }
            } finally { setting(previous) }
        }
    }
}
