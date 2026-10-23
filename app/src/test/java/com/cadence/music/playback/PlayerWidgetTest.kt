package com.cadence.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlayerWidgetTest {

    @Test
    fun widgetActionConstantsAreCorrect() {
        assertEquals("com.cadence.music.WIDGET_TOGGLE", PlayerWidget.ACTION_TOGGLE)
        assertEquals("com.cadence.music.WIDGET_NEXT", PlayerWidget.ACTION_NEXT)
        assertEquals("com.cadence.music.WIDGET_PREV", PlayerWidget.ACTION_PREV)
    }

    @Test
    fun widgetClassesInstantiateWithoutError() {
        assertNotNull(PlayerWidget())
        assertNotNull(LargePlayerWidget())
    }
}
