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

    private val hut = Structure("hut-id", StructureType.WITCH_HUT, OVERWORLD, Box(96, 67, -160, 103, 73, -152), 1L)

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
    fun anEntryThatCannotBeReadIsKeptAsItWas() {
        val text = """{"version": 1, "structures": [
            {"id": "hut-id", "type": "witch_hut", "dimension": "minecraft:overworld", "box": [96, 67, -160, 103, 73, -152], "discovered": 1},
            {"id": "new-kind", "type": "a_kind_from_a_newer_version", "dimension": "minecraft:overworld", "box": [0, 0, 0, 1, 1, 1], "discovered": 2}
        ], "deleted": [{"id": "odd", "box": "not a box"}]}"""
        val contents = StructureStore.parse(text)!!
        assertEquals(listOf("hut-id"), contents.structures.map { it.id })
        assertEquals(1, contents.unreadStructures.size)
        assertEquals(1, contents.unreadDeleted.size)
        // Saved again: both are still in the file, exactly as they were.
        val again = StructureStore.parse(StructureStore.write(contents))!!
        assertEquals(contents.unreadStructures, again.unreadStructures)
        assertEquals(contents.unreadDeleted, again.unreadDeleted)
        assertEquals(contents.structures, again.structures)
    }

    @Test
    fun aFileThatCannotBeReadIsNotTakenForAnEmptyOne() {
        assertNull(StructureStore.parse("{\"structures\": [ {\"id\": "), "half a file")
        assertNull(StructureStore.parse("{\"structures\": 5}"), "a list that is not one")
        assertEquals(0, StructureStore.parse("{\"structures\": [], \"deleted\": null}")!!.structures.size)
    }

    @Test
    fun thereIsNothingToUndoTwice() {
        StructureStore.put(hut)
        StructureStore.remove(hut.id)
        StructureStore.undoDelete()
        assertNull(StructureStore.undoDelete(), "only the last delete comes back")
    }
}
