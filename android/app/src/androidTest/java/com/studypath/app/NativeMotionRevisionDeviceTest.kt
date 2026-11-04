package com.studypath.app

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studypath.app.ui.CapsuleBottomNavigationView
import com.studypath.app.ui.GiraffeMascotView
import com.studypath.app.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Explicitly selected, read-only view fixtures on the isolated local-emulator installation. */
@RunWith(AndroidJUnit4::class)
class NativeMotionRevisionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<StudyPathApplication>()

    @Test fun finiteGiraffePosesAndFourLabeledCapsuleDestinationsRespectSystemMotion() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.startsWith(
            "com.studypath.app.NativeMotionRevisionDeviceTest") == true)
        assertTrue("Only the configured local Firebase emulator may host this fixture",
            BuildConfig.USE_FIREBASE_EMULATORS && BuildConfig.FIREBASE_PROJECT_ID == "demo-studypath")
        assertTrue("Use the isolated Android emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals("Use only the read-only registration-test AVD", "StudyPath_Lite_Test",
            shell("getprop ro.boot.qemu.avd_name").trim())
        runBlocking { withTimeout(20_000) { app.graph.repository.resumeSession() } }
        assertNotNull("Run after local test-account setup; this test never signs in or changes accounts", app.graph.repository.currentSession())
        val originalScale = shell("settings get global animator_duration_scale").trim()
        val uid = app.graph.repository.currentSession()!!.uid
        val observations = mutableListOf<String>()
        try {
            setMotion(true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var host: MainActivity
                scenario.onActivity { host = it }
                await("The authenticated native shell must finish loading") { main { host.findViewById<View>(R.id.navigation) != null } }
                lateinit var overlay: FrameLayout
                lateinit var giraffe: GiraffeMascotView
                lateinit var nav: CapsuleBottomNavigationView
                scenario.onActivity { activity ->
                    overlay = FrameLayout(activity).apply { setBackgroundColor(Color.WHITE) }
                    giraffe = GiraffeMascotView(activity)
                    overlay.addView(giraffe, FrameLayout.LayoutParams(240, 300, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
                    val shell = LayoutInflater.from(activity).inflate(R.layout.activity_main, overlay, false)
                    nav = shell.findViewById(R.id.navigation)
                    (nav.parent as ViewGroup).removeView(nav)
                    nav.isItemActiveIndicatorEnabled = false
                    nav.menu.apply {
                        add(0, R.id.nav_study, 0, "Study").setIcon(R.drawable.ic_study)
                        add(0, R.id.nav_saved, 1, "Saved").setIcon(R.drawable.ic_saved)
                        add(0, R.id.nav_planner, 2, "Planner").setIcon(R.drawable.ic_plan)
                        add(0, R.id.nav_account, 3, "Account").setIcon(R.drawable.ic_profile)
                    }
                    nav.setOnItemSelectedListener { true }
                    overlay.addView(nav, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        (88 * activity.resources.displayMetrics.density).toInt(), Gravity.BOTTOM))
                    activity.addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                try {
                    await("Native fixture must attach and lay out") { main { giraffe.isAttachedToWindow && giraffe.width == 240 && nav.width > 0 } }
                    main { giraffe.play(GiraffeMascotView.Pose.SLEEP) }
                    SystemClock.sleep(1500)
                    val sleeping = image(giraffe)
                    assertStable(giraffe, sleeping, "Sleep must stop breathing after its finite entrance")
                    save(sleeping, "sleep-settled")
                    main { giraffe.play(GiraffeMascotView.Pose.WAKE) }
                    SystemClock.sleep(1100)
                    val awake = image(giraffe)
                    assertFalse("Wake must produce a visibly different open-eyed pose", sleeping.sameAs(awake))
                    assertStable(giraffe, awake, "Wake must settle")
                    save(awake, "wake-settled")
                    main { giraffe.play(GiraffeMascotView.Pose.WAVE) }
                    SystemClock.sleep(180)
                    val waving = image(giraffe)
                    SystemClock.sleep(1100)
                    val waveSettled = image(giraffe)
                    assertFalse("Wave must visibly move before settling", waving.sameAs(waveSettled))
                    assertStable(giraffe, waveSettled, "Wave must stop without an infinite loop")
                    observations += "sleepWakeWaveVisiblyDistinctAndFinite"

                    main { giraffe.play(GiraffeMascotView.Pose.WAVE) }
                    SystemClock.sleep(130)
                    main { overlay.removeView(giraffe) }
                    val detached = image(giraffe)
                    assertStable(giraffe, detached, "A detached mascot must cancel motion")
                    assertTrue("Detaching a waving mascot must leave its static open-eyed pose", awake.sameAs(detached))
                    main { overlay.addView(giraffe, 0, FrameLayout.LayoutParams(240, 300, Gravity.TOP or Gravity.CENTER_HORIZONTAL)) }
                    observations += "detachCancelsToStaticPose"

                    main { giraffe.play(GiraffeMascotView.Pose.WAVE) }
                    SystemClock.sleep(100)
                    setMotion(false)
                    SystemClock.sleep(200)
                    val reduced = image(giraffe)
                    assertStable(giraffe, reduced, "Disabling system animation mid-wave must cancel it")
                    assertTrue("Reduced motion must show the corresponding static awake pose", awake.sameAs(reduced))
                    main { giraffe.play(GiraffeMascotView.Pose.SLEEP) }
                    val reducedSleep = image(giraffe)
                    assertTrue("Reduced-motion Sleep preserves its closed-eye meaning", sleeping.sameAs(reducedSleep))
                    assertStable(giraffe, reducedSleep, "Reduced-motion Sleep must stay static")
                    observations += "systemDisabledMidMotionCancelsAndKeepsMeaningfulStaticPoses"

                    val ids = listOf(R.id.nav_study, R.id.nav_saved, R.id.nav_planner, R.id.nav_account)
                    val labels = listOf("Study", "Saved", "Planner", "Account")
                    for ((selected, id) in ids.withIndex()) {
                        setMotion(selected < 2)
                        main { nav.selectedItemId = id }
                        SystemClock.sleep(if (selected < 2) 400 else 100)
                        val settledNavigation = image(nav)
                        try { assertStable(nav, settledNavigation, "The capsule must settle at ${labels[selected]}") }
                        finally { settledNavigation.recycle() }
                        main {
                            labels.forEach { label -> assertTrue("$label remains visible for every destination", visibleText(nav, label)) }
                            val items = nav.getChildAt(0) as ViewGroup
                            val bitmap = imageOnMain(nav)
                            for (index in ids.indices) {
                                val item = items.getChildAt(index)
                                val x = items.left + item.left + item.width / 2
                                val y = items.top + item.top + (6 * nav.resources.displayMetrics.density).toInt()
                                val luminance = Color.luminance(bitmap.getPixel(x, y))
                                if (index == selected) assertTrue("Selection capsule must reach ${labelForIndex(index)}", luminance < .15f)
                                else assertTrue("Capsule must leave ${labels[index]}", luminance > .7f)
                            }
                            bitmap.recycle()
                        }
                    }
                    observations += "fourLabelsRemainVisibleAndBlackCapsuleRelocatesAndSettlesWithSystemMotionEnabledAndDisabled"
                    assertEquals("View-only tests must retain the existing signed-in account", uid, app.graph.repository.currentSession()?.uid)
                    File(app.getExternalFilesDir(null), "native-motion-revision.json").writeText(JSONObject()
                        .put("test", "finiteGiraffePosesAndFourLabeledCapsuleDestinationsRespectSystemMotion")
                        .put("observed", JSONArray(observations))
                        .put("limits", "Native fixture rendering only; no frame-rate, accessibility-service, backend, or production acceptance claim.")
                        .toString(2))
                    sleeping.recycle(); awake.recycle(); waving.recycle(); waveSettled.recycle()
                    detached.recycle(); reduced.recycle(); reducedSleep.recycle()
                } finally { main { (overlay.parent as? ViewGroup)?.removeView(overlay) } }
            }
        } finally {
            if (originalScale == "null" || originalScale.isEmpty()) shell("settings delete global animator_duration_scale")
            else shell("settings put global animator_duration_scale $originalScale")
        }
    }

    private fun labelForIndex(index: Int) = listOf("Study", "Saved", "Planner", "Account")[index]
    private fun assertStable(view: View, initial: Bitmap, message: String) {
        SystemClock.sleep(300)
        val later = image(view)
        try {
            if (!initial.sameAs(later)) { save(initial, "unstable-before"); save(later, "unstable-after") }
            assertTrue(message, initial.sameAs(later))
        } finally { later.recycle() }
    }
    private fun image(view: View): Bitmap = main { imageOnMain(view) }
    private fun imageOnMain(view: View): Bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    private fun visibleText(view: View, label: String): Boolean =
        (view is TextView && view.isShown && view.text.toString() == label) ||
            (view is ViewGroup && (0 until view.childCount).any { visibleText(view.getChildAt(it), label) })
    private fun save(bitmap: Bitmap, name: String) {
        val folder = File(app.getExternalFilesDir(null), "native-motion-revision-screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun setMotion(enabled: Boolean) {
        shell("settings put global animator_duration_scale ${if (enabled) "1" else "0"}")
        await("Android must observe the requested animation setting") { main { ValueAnimator.areAnimatorsEnabled() == enabled } }
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun await(description: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        do { if (condition()) return; SystemClock.sleep(50) } while (SystemClock.elapsedRealtime() < end)
        throw AssertionError(description)
    }
    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
}
