package app.framelink.camera

import org.junit.Assert.*
import org.junit.Test

class PhotoSelectionTest {
    private val groups = listOf(listOf("a"), listOf("b", "b-raw"), listOf("c"), listOf("d"))

    @Test
    fun selectingRangeAndReversingPreservesExistingChoices() {
        val range = PhotoSelectionRange(groups, listOf("outside"), 0)
        assertEquals(setOf("outside", "a", "b", "b-raw", "c"), range.selectionAt(2).toSet())
        assertEquals(setOf("outside", "a", "b", "b-raw"), range.selectionAt(1).toSet())
        assertEquals(setOf("outside", "a"), range.selectionAt(0).toSet())
    }

    @Test
    fun draggingFromSelectedGroupDeselectsAndRestoresOnReverse() {
        val range = PhotoSelectionRange(groups, listOf("outside", "a", "b", "b-raw", "c"), 1)
        assertEquals(setOf("outside", "a"), range.selectionAt(2).toSet())
        assertEquals(setOf("outside", "c"), range.selectionAt(0).toSet())
        assertEquals(setOf("outside", "a", "c"), range.selectionAt(1).toSet())
    }

    @Test
    fun partialBurstIsSelectedTogetherAndInvalidIndicesAreRejected() {
        val range = PhotoSelectionRange(groups, listOf("b"), 1)
        assertEquals(setOf("b", "b-raw"), range.selectionAt(1).toSet())
        assertThrows(IllegalArgumentException::class.java) { range.selectionAt(4) }
        assertThrows(IllegalArgumentException::class.java) { PhotoSelectionRange(emptyList(), emptyList(), 0) }
    }

    @Test fun aDragOwnsItsSnapshotAndKeepsImportOrder(){
        val changing=mutableListOf(mutableListOf("a"),mutableListOf("b","b-raw"))
        val original=mutableListOf("outside")
        val range=PhotoSelectionRange(changing,original,0)
        changing[0][0]="changed";original.clear()
        assertEquals(listOf("outside","a","b","b-raw"),range.selectionAt(1))
        assertEquals(1,range.lastIndex)
        assertEquals(listOf("outside","a"),range.selectionAt(0))
    }
}
