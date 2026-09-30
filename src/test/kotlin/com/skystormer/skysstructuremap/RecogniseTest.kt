package com.skystormer.skysstructuremap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecogniseTest {

    /** A monument started in the chunk beginning at block [chunkStart] spans chunkStart - 29 .. chunkStart + 28. */
    private fun realEdge(chunkStart: Int) = chunkStart - 29

    /** A monument's worth of blocks in any box. */
    private val full = { _: Box -> 6000 }

    @Test
    fun wholeMonumentSeenGivesItsExactEdge() {
        for (start in listOf(-320, -16, 0, 16, 1024)) {
            assertEquals(listOf(realEdge(start)), Recognise.monumentEdges(start - 29, start + 28))
        }
    }

    @Test
    fun partOfAMonumentIsNotEnough() {
        // A monument with its edge at z 147: seen only from 163 to 204 it could still
        // be one chunk further south, which is what the old best-guess picked.
        val edges = Recognise.monumentEdges(163, 204)
        assertTrue(edges.size > 1 && realEdge(176) in edges)
        assertNull(Recognise.monumentBox(Box(771, 40, 163, 828, 60, 204), full))
    }

    @Test
    fun blocksWiderThanAMonumentAreNotOne() {
        assertTrue(Recognise.monumentEdges(0, 70).isEmpty())
    }

    @Test
    fun monumentBoxIsTheFixedSize() {
        val box = Recognise.monumentBox(Box(1155, 40, 835, 1212, 60, 892), full)
        assertEquals(Box(1155, 39, 835, 1212, 61, 892), box)
        assertEquals(58, box!!.sizeX)
        assertEquals(23, box.sizeY)
    }

    @Test
    fun blocksNoMonumentLinesUpWithAreABuild() {
        // 52 × 48 blocks of prismarine by the sea: no monument's place holds them, and they are few.
        assertNull(Recognise.monumentBox(Box(0, 40, 0, 51, 60, 47)) { 300 })
    }

    @Test
    fun aBuildBesideAMonumentLeavesTheMonumentsBox() {
        val monument = Box(1155, 39, 835, 1212, 61, 892)
        val box = Recognise.monumentBox(Box(1155, 40, 835, 1300, 60, 892)) { if (it == monument) 12000 else 300 }
        assertEquals(monument, box)
    }

    @Test
    fun boxTouchesHitboxOnlyWhenInside() {
        val box = Box(0, 39, 0, 57, 61, 57)
        assertTrue(box.touches(net.minecraft.world.phys.AABB(10.0, 50.0, 10.0, 10.6, 51.8, 10.6)))
        assertTrue(!box.touches(net.minecraft.world.phys.AABB(10.0, 62.0, 10.0, 10.6, 63.8, 10.6)))
        assertTrue(!box.touches(net.minecraft.world.phys.AABB(58.0, 50.0, 10.0, 58.6, 51.8, 10.6)))
    }
}
