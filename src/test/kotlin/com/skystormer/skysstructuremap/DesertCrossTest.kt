package com.skystormer.skysstructuremap

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * A desert pyramid's floor cross ([TemplePieces.desertCross]) as players leave it: dug down
 * through to the chests below, the cross with holes of air in it.
 */
class DesertCrossTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setUp() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        /** The middle of the cross: 10 in from the corner of chunk -351, 145, as the one in Bobby's cache of SiteB. */
        private const val X = -5606
        private const val Y = 76
        private const val Z = 2330

        private val CROSS = listOf(
            0 to 0, 0 to -3, 0 to -2, -1 to -1, 1 to -1, -2 to 0, 2 to 0, -3 to 0, 3 to 0, -1 to 1, 1 to 1, 0 to 2, 0 to 3,
        )

        /** The four that one had dug out. */
        private val DUG = setOf(0 to -2, 1 to -1, 2 to 0, 3 to 0)
    }

    private class World(val blocks: Map<Long, BlockState>) : BlockSource {
        override fun hasChunk(chunkX: Int, chunkZ: Int) = true
        override fun getBlockState(pos: BlockPos): BlockState = blocks[pos.asLong()] ?: Blocks.AIR.defaultBlockState()
    }

    /** A sandstone floor with the cross in it, [dug] of it as [holes]. */
    private fun floor(dug: Set<Pair<Int, Int>>, holes: BlockState?): Map<Long, BlockState> {
        val out = HashMap<Long, BlockState>()
        for (x in X - 10..X + 10) for (z in Z - 10..Z + 10) out[BlockPos.asLong(x, Y, z)] = Blocks.SANDSTONE.defaultBlockState()
        for ((dx, dz) in CROSS) {
            val key = BlockPos.asLong(X + dx, Y, Z + dz)
            when {
                (dx to dz) in dug -> if (holes == null) out.remove(key) else out[key] = holes
                dx == 0 && dz == 0 -> out[key] = Blocks.DYED_TERRACOTTA.blue().defaultBlockState()
                else -> out[key] = Blocks.DYED_TERRACOTTA.orange().defaultBlockState()
            }
        }
        return out
    }

    private fun cross(blocks: Map<Long, BlockState>): BlockPos? {
        val d = Detection(1, StructureType.DESERT_TEMPLE, "minecraft:overworld")
        for ((key, state) in blocks) if (state.block != Blocks.SANDSTONE) d.add(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), state.block)
        return TemplePieces.desertCross(d, World(blocks))
    }

    @Test
    fun aCrossWithFourDugOutIsFound() {
        assertEquals(BlockPos(X, Y, Z), cross(floor(DUG, holes = null)))
    }

    @Test
    fun theSameFilledInWithSandstoneIsNot() {
        // Something laid where the cross was is not what digging leaves.
        assertNull(cross(floor(DUG, holes = Blocks.SANDSTONE.defaultBlockState())))
    }

    @Test
    fun tooLittleOfItLeftIsNot() {
        assertNull(cross(floor(DUG + setOf(-3 to 0, 0 to 3, 0 to 2), holes = null)))
    }
}
