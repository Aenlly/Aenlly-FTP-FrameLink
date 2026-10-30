package app.framelink.camera

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class LiveDeviceOrientationTest{
    @Test fun directionsHaveHysteresisAndUseTheShortestTurn(){
        val state=LiveDeviceOrientation()
        state.update(-1);state.update(59);assertEquals(0,state.rotation)
        state.update(61);assertEquals(-90,state.rotation)
        state.update(40);assertEquals(-90,state.rotation)
        state.update(29);assertEquals(0,state.rotation)
        state.update(270);assertEquals(90,state.rotation)
        state.update(359);assertEquals(0,state.rotation)
        state.update(180);assertEquals(-180,state.rotation)
        state.update(-1);assertEquals(-180,state.rotation)
    }

    @Test fun rotatedPreviewMapsCornersBackToTheSameCameraCoordinates(){
        val image=IntSize(1200,800)
        val viewport=IntSize(800,1200)
        assertEquals(Offset(0f,0f),liveFocusPoint(Offset(0f,1200f),viewport,image,-90))
        assertEquals(Offset(1f,1f),liveFocusPoint(Offset(800f,0f),viewport,image,-90))
        assertEquals(Offset(0f,0f),liveFocusPoint(Offset(800f,0f),viewport,image,90))
        assertEquals(Offset(1f,1f),liveFocusPoint(Offset(0f,1200f),viewport,image,90))
        assertEquals(Offset(.5f,.5f),liveFocusPoint(Offset(400f,600f),viewport,image,270))
        assertNull(liveFocusPoint(Offset(0f,0f),IntSize(1000,1200),image,90))
    }
}
