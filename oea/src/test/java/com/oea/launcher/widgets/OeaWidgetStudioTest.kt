package com.oea.launcher.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OeaWidgetStudioTest {
    @Test fun surfaceOpacityIsClampedAndPreservesPresetRgb() {
        val preset = OeaWidgetStudio.presets.first()
        assertEquals(0x59181F2F, preset.surface(0))
        assertEquals(0xFF181F2F.toInt(), preset.surface(100))
        assertEquals(0xFF181F2F.toInt(), preset.surface(500))
    }

    @Test fun originalPresetsHaveDifferentSurfacesAndAccents() {
        assertEquals(4, OeaWidgetStudio.presets.map { it.id }.distinct().size)
        assertNotEquals(OeaWidgetStudio.presets[0].background, OeaWidgetStudio.presets[1].background)
        assertNotEquals(OeaWidgetStudio.presets[0].accent, OeaWidgetStudio.presets[2].accent)
    }

    @Test fun weatherUnitConversionUsesRoundedValues() {
        assertEquals("24°C", OeaWidgetStudio.temperature(23.6, fahrenheit = false))
        assertEquals("75°F", OeaWidgetStudio.temperature(23.9, fahrenheit = true))
        assertEquals(-4, OeaWidgetStudio.celsiusToFahrenheit(-20.0))
    }
}
