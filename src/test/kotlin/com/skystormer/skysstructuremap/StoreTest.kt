package com.skystormer.skysstructuremap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Deleting a structure and undoing it. No world is open, so nothing is written to a file, and
 * nothing here asks what a structure is made of (that needs the game itself).
 */
class StoreTest {

    private val hut = Structure("hut-id", StructureType.WITCH_HUT, OVERWORLD, Box(-416, 67, 352, -409, 73, 360), 1L)

    @Test
    fun undoBringsTheLastDeleteBack() {
        StructureStore.put(hut)
        StructureStore.remove(hut.id)
        assertTrue(StructureStore.all.none { it.id == hut.id }, "it should be gone")
        assertEquals(hut.id, StructureStore.deleted.lastOrNull()?.id, "and on the deleted list")

        val back = StructureStore.undoDelete()
        assertEquals(hut.id, back?.id)
        assertTrue(StructureStore.all.any { it.id == hut.id }, "it should be back on the map")
        assertTrue(StructureStore.deleted.none { it.id == hut.id }, "and off the deleted list")
    }

    @Test
    fun thereIsNothingToUndoTwice() {
        StructureStore.put(hut)
        StructureStore.remove(hut.id)
        StructureStore.undoDelete()
        assertNull(StructureStore.undoDelete(), "only the last delete comes back")
    }
}
