package app.framelink.camera

import org.junit.Assert.*
import org.junit.Test

class CreativeLookTextTest {
    @Test fun modernLookKeepsCameraEnumAndUnknownValuesAreNotEditable(){
        val property=CameraProperty(CameraPropertyText.CREATIVE_LOOK,"Creative Look",1,"ST",true,listOf(PropertyChoice(1,"ST"),PropertyChoice(6,"FL"),PropertyChoice(256,"unverified")))
        assertEquals("创意外观",CameraPropertyText.name(property))
        assertEquals("ST",CameraPropertyText.label(property))
        assertEquals("标准",CameraPropertyText.creativeLookName(1))
        assertEquals("胶片",CameraPropertyText.creativeLookName(6))
        assertNull(CameraPropertyText.creativeLookName(256))
        assertEquals(listOf("ST","FL"),CameraPropertyText.choices(property).map{it.label})
        assertTrue(CameraPropertyText.editable(property))
        assertFalse(CameraPropertyText.editable(property.copy(value=99)))
        assertFalse(CameraPropertyText.editable(property.copy(writable=false)))
        assertFalse(CameraPropertyText.recognized(property.copy(code=0x01c5)))
        assertFalse(CameraPropertyText.recognized(property.copy(code=0xd200)))
    }
}
