package com.skystormer.skysstructuremap

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Telling fortresses seen as one group of bricks apart ([FortressPieces.split]), and keeping them
 * apart once saved. The fortresses here are made up: rows of crossroads joined by bridges.
 */
class FortressSplitTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setUp() {
            // FortressPieces reads the game's blocks.
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    /** [crossroads] crossroads in a row along x from [x], joined by bridges, at [z]. */
    private fun row(x: Int, z: Int, crossroads: Int): List<Piece> = (0 until crossroads * 2 - 1).map { i ->
        val from = x + i * 19
        Piece(if (i % 2 == 0) "nebcr" else "nebs", Box(from, 64, z, from + 18, 73, z + 18))
    }

    @Test
    fun oneFortressIsNeverSplit() {
        // Five crossroads, and pieces of it apart from the rest (the bridge between them mined away).
        val pieces = row(0, 0, 5) + row(0, 100, 1).map { it.copy(kind = "nebs") }
        assertEquals(1, FortressPieces.split(pieces).size)
    }

    @Test
    fun withoutMoreCrossroadsThanOneHasTheyStayOne() {
        // Two apart, but nothing proves they are two: better joined than split wrongly.
        val pieces = row(0, 0, 2) + row(0, 60, 3)
        assertEquals(1, FortressPieces.split(pieces).size)
    }

    @Test
    fun twoFortressesTouchingComeApart() {
        val west = row(0, 0, 5)
        // The other's bridge runs right up to the first one's last crossroads.
        val east = row(190, 0, 5) + Piece("nebs", Box(171, 64, 0, 189, 73, 18))
        val parts = FortressPieces.split(west + east)
        assertEquals(2, parts.size)
        for (part in parts) {
            assertEquals(5, FortressPieces.crossroads(part))
            assertTrue(part.filter { it.kind == "nebcr" }.let { cr -> cr.all { it in west } || cr.all { it in east } }, "one fortress's crossroads each")
        }
    }

    @Test
    fun savedFortressesWithTooManyCrossroadsTogetherAreTwo() {
        fun fortress(id: String, pieces: List<Piece>) =
            Structure(id, StructureType.FORTRESS, NETHER, pieces.map { it.box }.reduce(Box::union), 1L, pieces = pieces)
        val west = fortress("west", row(0, 0, 5))
        val east = fortress("east", row(0, 40, 5))
        assertTrue(StructureStore.twoFortresses(west, east))
        // Saved twice: the same pieces do not count twice.
        assertFalse(StructureStore.twoFortresses(west, fortress("again", row(0, 0, 5))))
        assertFalse(StructureStore.twoFortresses(west, fortress("part", row(0, 40, 1).map { it.copy(kind = "nebs") })))
    }
}
