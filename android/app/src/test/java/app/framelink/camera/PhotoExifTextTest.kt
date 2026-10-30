package app.framelink.camera

import org.junit.Assert.*
import org.junit.Test

class PhotoExifTextTest{
    @Test fun shutterKeepsFractionalAndLongExposuresReadable(){
        assertEquals("1/125 秒",PhotoExifText.shutter(.008))
        assertEquals("1/160 秒",PhotoExifText.shutter(.00625))
        assertEquals("0.3 秒",PhotoExifText.shutter(.3))
        assertEquals("1.3 秒",PhotoExifText.shutter(1.3))
        assertEquals("30 秒",PhotoExifText.shutter(30.0))
        assertNull(PhotoExifText.shutter(0.0))
        assertNull(PhotoExifText.shutter(Double.NaN))
        assertEquals("5.6",PhotoExifText.number(5.5999999999999996))
        assertEquals("0",PhotoExifText.decimal(0.0))
    }
    @Test fun photoGpsKeepsZeroAndSignedCoordinatesWithoutPhoneLocation(){
        val zero=PhotoLocation(0.0,0.0,0.0,"WGS-84")
        assertTrue(zero.canOpenMap)
        assertEquals("0.000000, 0.000000",zero.coordinates)
        assertEquals("geo:0.0000000,0.0000000?q=0.0000000,0.0000000",zero.geoUri())
        assertEquals("-33.125000, 151.250000",PhotoLocation(-33.125,151.25,null,null).coordinates)
        assertFalse(PhotoLocation(1.0,2.0,null,"TOKYO").canOpenMap)
        assertThrows(IllegalArgumentException::class.java){PhotoLocation(91.0,1.0,null,null)}
    }
}
