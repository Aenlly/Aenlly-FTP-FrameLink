package app.framelink.camera

import org.junit.Assert.*
import org.junit.Test

class CameraPropertyTextTest {
    @Test
    fun sonyFocusAndMeteringEnumsAreChineseAndKeepOriginalValues() {
        val focus = CameraProperty(0x500A, "Focus mode", 32772, "32772", true,
            listOf(PropertyChoice(2, "Automatic"), PropertyChoice(32772, "32772"), PropertyChoice(32773, "32773")))
        assertEquals("对焦模式", CameraPropertyText.name(focus))
        assertEquals("连续自动对焦（AF-C）", CameraPropertyText.label(focus))
        assertEquals(listOf(2L, 32772L, 32773L), CameraPropertyText.choices(focus).map { it.value })
        val area = focus.copy(code = 0xD22C, value = 0x103)
        assertEquals("点：大", CameraPropertyText.label(area))
        assertEquals("跟踪：点（中）", CameraPropertyText.label(area, 0x205))
        assertEquals("多重测光", CameraPropertyText.label(focus.copy(code = 0x500B, value = 0x8001)))
    }

    @Test
    fun unknownEnumsStayUnavailableInsteadOfMakingUpAName() {
        val property = CameraProperty(0x500A, "Focus mode", 9999, "9999", true, listOf(PropertyChoice(9999, "9999")))
        assertNull(CameraPropertyText.label(property))
        assertTrue(CameraPropertyText.choices(property).isEmpty())
        assertFalse(CameraPropertyText.editable(property))
    }

    @Test
    fun exposureValuesRetainPhysicalUnits() {
        val property = CameraProperty(0x5007, "F-number", 280, "2.8", false)
        assertEquals("f/2.8", CameraPropertyText.label(property))
        assertEquals("1/125 秒", CameraPropertyText.label(property.copy(code = 0x500D, value = 80)))
        assertEquals("+0.3 EV", CameraPropertyText.label(property.copy(code = 0x5010, value = 300)))
        assertEquals("自动", CameraPropertyText.label(property.copy(code = 0x500F, value = 0xffffff)))
    }
}
