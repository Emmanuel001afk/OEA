package com.oea.launcher.fault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stability rules are the promise that a game, a widget or a socket thread
 * cannot take OEA Home down, so they are tested directly rather than through a
 * device. These tests are pure JVM: no Android classes are involved.
 */
class OeaFaultPolicyTest {

    private fun input(
        kind: ThreadKind,
        subsystem: String = "game:runner", // stable Endless Days game id
        repeats: Int = 1,
        fatalError: Boolean = false,
        stackOverflow: Boolean = false,
        alreadyInSafeMode: Boolean = false,
        homeRendered: Boolean = true,
        onOeaScreen: Boolean = true,
    ) = FaultInput(
        kind = kind,
        subsystem = subsystem,
        repeats = repeats,
        windowMs = OeaFaultPolicy.WINDOW_MS,
        fatalError = fatalError,
        stackOverflow = stackOverflow,
        alreadyInSafeMode = alreadyInSafeMode,
        homeRendered = homeRendered,
        onOeaScreen = onOeaScreen,
    )

    @Test
    fun `a fault in a game closes the game screen and keeps Home`() {
        val decision = OeaFaultPolicy.decide(input(ThreadKind.RENDER, subsystem = "game:runner"))
        assertEquals(FaultAction.CLOSE_SCREEN, decision.action)
        assertTrue("Home must stay alive", decision.keepsLauncherAlive)
    }

    @Test
    fun `a fault on Home's own thread rebuilds the surface instead of failing`() {
        val decision = OeaFaultPolicy.decide(input(ThreadKind.MAIN_HOME, subsystem = "home"))
        assertEquals(FaultAction.REBUILD_HOME, decision.action)
        assertTrue(decision.keepsLauncherAlive)
    }

    @Test
    fun `repeated Home faults switch optional subsystems off before giving up`() {
        val first = OeaFaultPolicy.decide(input(ThreadKind.MAIN_HOME, subsystem = "home", repeats = 2))
        assertEquals(FaultAction.REBUILD_HOME, first.action)

        val third = OeaFaultPolicy.decide(
            input(ThreadKind.MAIN_HOME, subsystem = "home", repeats = OeaFaultPolicy.HOME_SAFE_MODE_AFTER, homeRendered = false),
        )
        assertEquals(FaultAction.SAFE_MODE, third.action)
    }

    @Test
    fun `Home that keeps failing even in safe mode escalates to Android`() {
        val decision = OeaFaultPolicy.decide(
            input(
                ThreadKind.MAIN_HOME,
                subsystem = "home",
                repeats = OeaFaultPolicy.HOME_SAFE_MODE_AFTER + 1,
                alreadyInSafeMode = true,
            ),
        )
        assertEquals(FaultAction.ESCALATE, decision.action)
        assertFalse("Only this case may end the process", decision.keepsLauncherAlive)
    }

    @Test
    fun `an out of memory error on a worker thread is contained`() {
        val decision = OeaFaultPolicy.decide(input(ThreadKind.WORKER, subsystem = "net-session", fatalError = true))
        assertEquals(FaultAction.CONTAIN, decision.action)
        assertTrue(decision.keepsLauncherAlive)
    }

    @Test
    fun `an out of memory error on the main thread escalates`() {
        val decision = OeaFaultPolicy.decide(input(ThreadKind.MAIN_HOME, subsystem = "home", fatalError = true))
        assertEquals(FaultAction.ESCALATE, decision.action)
    }

    @Test
    fun `a stack overflow is not treated as an unrecoverable virtual machine error`() {
        assertFalse(OeaFaultPolicy.isFatalError(StackOverflowError()))
        assertTrue(OeaFaultPolicy.isStackOverflow(StackOverflowError()))
        assertTrue(OeaFaultPolicy.isFatalError(OutOfMemoryError()))
    }

    @Test
    fun `a fault while another app's window is in front is only recorded`() {
        val decision = OeaFaultPolicy.decide(input(ThreadKind.MAIN_SCREEN, subsystem = "home", onOeaScreen = false))
        assertEquals(FaultAction.CONTAIN, decision.action)
    }

    @Test
    fun `a subsystem that keeps faulting is disabled for the session`() {
        val decision = OeaFaultPolicy.decide(
            input(ThreadKind.WORKER, subsystem = "lan-server", repeats = OeaFaultPolicy.LOOP_LIMIT, onOeaScreen = false),
        )
        assertEquals(FaultAction.CONTAIN, decision.action)
        assertTrue(decision.reason.contains("disabled"))
    }

    @Test
    fun `VIP and utility activities are isolated from Home and receive stable identities`() {
        assertTrue(OeaFaultPolicy.isRecoverableOeaScreenName("com.oea.launcher.miniapps.OeaVipActivity"))
        assertTrue(OeaFaultPolicy.isRecoverableOeaScreenName("com.oea.launcher.phone.OeaPhoneActivity"))
        assertTrue(OeaFaultPolicy.isRecoverableOeaScreenName("com.oea.launcher.callblocker.OeaCallBlockerActivity"))
        assertFalse(OeaFaultPolicy.isRecoverableOeaScreenName("com.oea.launcher.OeaLauncherActivity"))
        assertEquals("miniapp:vip", OeaFaultPolicy.subsystemForScreenClassName("com.oea.launcher.miniapps.OeaVipActivity"))
        assertEquals("miniapp:vip-player", OeaFaultPolicy.subsystemForScreenClassName("com.oea.launcher.miniapps.OeaVipPlayerActivity"))
        assertEquals("miniapp:host", OeaFaultPolicy.subsystemForScreenClassName("com.oea.launcher.miniapps.OeaMiniAppActivity"))
        assertEquals(null, OeaFaultPolicy.subsystemForScreenClassName("com.oea.launcher.OeaLauncherActivity"))
    }

    @Test
    fun `threads are classified by the work they do, not by their number`() {
        val main = Thread.currentThread()
        assertEquals(ThreadKind.MAIN_HOME, OeaFaultPolicy.classify(main, main, screenIsGame = false))
        assertEquals(ThreadKind.MAIN_SCREEN, OeaFaultPolicy.classify(main, main, screenIsGame = true))

        val gl = Thread({ }, "GLThread 42")
        assertEquals(ThreadKind.RENDER, OeaFaultPolicy.classify(gl, main, screenIsGame = true))

        val socket = Thread({ }, "oea-lan-r12")
        assertEquals(ThreadKind.WORKER, OeaFaultPolicy.classify(socket, main, screenIsGame = false))

        val binder = Thread({ }, "Binder:1234_2")
        assertEquals(ThreadKind.BINDER, OeaFaultPolicy.classify(binder, main, screenIsGame = false))

        val stranger = Thread({ }, "SomeProviderThread")
        assertEquals(ThreadKind.UNKNOWN, OeaFaultPolicy.classify(stranger, main, screenIsGame = false))
    }

    @Test
    fun `an unrendered Home is never judged by a rendered-Home rule`() {
        // Home failing before its first frame must still rebuild, not escalate.
        val decision = OeaFaultPolicy.decide(
            input(ThreadKind.MAIN_HOME, subsystem = "home", repeats = 1, homeRendered = false),
        )
        assertEquals(FaultAction.REBUILD_HOME, decision.action)
    }
}
