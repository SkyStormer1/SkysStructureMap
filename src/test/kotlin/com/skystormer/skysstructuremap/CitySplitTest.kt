package com.skystormer.skysstructuremap

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** Two ancient cities whose blocks chain into one group, told apart once one is known. */
class CitySplitTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setUp() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    /** A slab of deepslate bricks over x [x0]..[x1], z [z0]..[z1], every other block. */
    private fun slab(x0: Int, x1: Int, z0: Int, z1: Int) =
        (x0..x1 step 2).flatMap { x -> (z0..z1 step 2).map { z -> ChunkScanner.Found(x, -40, z, Blocks.DEEPSLATE_BRICKS) } }

    @Test
    fun theNextCityLeavesAGroupAndStaysApart() {
        val groups = Groups()
        // Two cities 20 blocks apart: close enough for their blocks to join.
        groups.add(StructureType.ANCIENT_CITY, OVERWORLD, slab(0, 99, 0, 99), true)
        groups.add(StructureType.ANCIENT_CITY, OVERWORLD, slab(120, 219, 0, 99), true)
        assertEquals(1, groups.detections.size, "one group to start with")
        val west = groups.detections.single()
        val east = groups.splitOff(west, Box(-16, -64, -16, 115, 0, 115), 500)!!
        assertEquals(Box(0, -40, 0, 98, -40, 98), west.bounds)
        assertEquals(Box(120, -40, 0, 218, -40, 98), east.bounds)
        assertEquals(west.count + east.count, slab(0, 99, 0, 99).size + slab(120, 219, 0, 99).size, "every block kept")
        // Blocks seen later between them join one, never both together.
        groups.add(StructureType.ANCIENT_CITY, OVERWORLD, slab(104, 110, 40, 50), true)
        assertEquals(2, groups.detections.size)
    }

    @Test
    fun aFewBlocksBeyondAreNotACity() {
        val groups = Groups()
        groups.add(StructureType.ANCIENT_CITY, OVERWORLD, slab(0, 99, 0, 99) + slab(110, 119, 0, 9), true)
        assertEquals(null, groups.splitOff(groups.detections.single(), Box(-16, -64, -16, 115, 0, 115), 500))
    }

    @Test
    fun savedCitiesWithTheirOwnCentresAreTwo() {
        assertTrue(StructureStore.twoCities(Box(0, -40, 0, 20, -20, 20), Box(200, -40, 0, 220, -20, 20)))
        assertFalse(StructureStore.twoCities(Box(0, -40, 0, 20, -20, 20), Box(0, -40, 0, 20, -20, 20)))
        assertFalse(StructureStore.twoCities(Box(0, -40, 0, 20, -20, 20), null), "a city saved before centres were kept")
    }
}
