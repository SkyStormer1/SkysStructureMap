package com.skystormer.skysstructuremap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecogniseTest {

    /** A monument started in the chunk beginning at block [chunkStart] spans chunkStart - 29 .. chunkStart + 28. */
    private fun realEdge(chunkStart: Int) = chunkStart - 29

    @Test
    fun wholeMonumentSeenGivesItsExactEdge() {
        for (start in listOf(-320, -16, 0, 16, 1024)) {
            assertEquals(listOf(realEdge(start)), Recognise.monumentEdges(start - 29, start + 28))
        }
    }

    @Test
    fun partOfAMonumentIsNotEnough() {
        // The monument tested in game (edge z -877): seen only from -861 to -820 it could still
        // be one chunk further south, which is what the old best-guess picked.
        val edges = Recognise.monumentEdges(-861, -820)
        assertTrue(edges.size > 1 && realEdge(-848) in edges)
        assertNull(Recognise.monumentBox(Box(-253, 40, -861, -196, 60, -820)))
    }

    @Test
    fun blocksWiderThanAMonumentAreNotOne() {
        assertTrue(Recognise.monumentEdges(0, 70).isEmpty())
    }

    @Test
    fun monumentBoxIsTheFixedSize() {
        val box = Recognise.monumentBox(Box(131, 40, -189, 188, 60, -132))
        assertEquals(Box(131, 39, -189, 188, 61, -132), box)
        assertEquals(58, box!!.sizeX)
        assertEquals(23, box.sizeY)
    }

    @Test
    fun boxTouchesHitboxOnlyWhenInside() {
        val box = Box(0, 39, 0, 57, 61, 57)
        assertTrue(box.touches(net.minecraft.world.phys.AABB(10.0, 50.0, 10.0, 10.6, 51.8, 10.6)))
        assertTrue(!box.touches(net.minecraft.world.phys.AABB(10.0, 62.0, 10.0, 10.6, 63.8, 10.6)))
        assertTrue(!box.touches(net.minecraft.world.phys.AABB(58.0, 50.0, 10.0, 58.6, 51.8, 10.6)))
    }
}
