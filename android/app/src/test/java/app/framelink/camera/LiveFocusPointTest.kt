package app.framelink.camera

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class LiveFocusPointTest {
    @Test fun centerMapsToCameraCenterInEitherOrientation() {
        for (viewport in listOf(IntSize(1080, 2400), IntSize(2400, 1080))) {
            val result = liveFocusPoint(Offset(viewport.width / 2f, viewport.height / 2f), viewport, IntSize(1024, 680))!!
            assertEquals(.5f, result.x, .0001f)
            assertEquals(.5f, result.y, .0001f)
        }
    }

    @Test fun LetterboxAndMissingFrameDoNotTriggerFocus() {
        assertNull(liveFocusPoint(Offset(540f, 100f), IntSize(1080, 2400), IntSize(1024, 680)))
        assertNull(liveFocusPoint(Offset(10f, 540f), IntSize(2400, 1080), IntSize(1024, 680)))
        assertNull(liveFocusPoint(Offset.Zero, IntSize.Zero, IntSize(1024, 680)))
        assertNull(liveFocusPoint(Offset.Zero, IntSize(1080, 2400), IntSize.Zero))
    }
}
