package com.oea.launcher.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OeaScreenOnTimeTest {
    @Test fun countsOnlyInteractiveIntervalsWithinToday() {
        val events = listOf(ScreenTransition(10, true), ScreenTransition(130, false), ScreenTransition(170, true), ScreenTransition(220, false))
        assertEquals(80L, screenOnTime(100, 250, events))
    }
    @Test fun carriesStateAcrossMidnightAndIncludesCurrentInterval() {
        assertEquals(90L, screenOnTime(100, 190, listOf(ScreenTransition(50, true))))
        assertEquals(20L, screenOnTime(100, 190, listOf(ScreenTransition(50, false), ScreenTransition(170, true))))
    }
    @Test fun doesNotGuessWhenHistoryIsMissing() {
        assertNull(screenOnTime(100, 200, emptyList()))
        assertNull(screenOnTime(100, 200, listOf(ScreenTransition(150, true))))
        assertNull(screenOnTime(100, 100, listOf(ScreenTransition(90, true))))
    }
}
