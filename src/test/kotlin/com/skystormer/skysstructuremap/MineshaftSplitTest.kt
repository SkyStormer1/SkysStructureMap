package com.skystormer.skysstructuremap

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Mineshafts told apart by what the game joined to each one's start room ([MineshaftPieces.split]),
 * from corridors read off their supports ([MineshaftPieces.corridors]).
 */
class MineshaftSplitTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setUp() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    private class World(val blocks: Map<Long, BlockState>) : BlockSource {
        override fun hasChunk(chunkX: Int, chunkZ: Int) = true
        override fun getBlockState(pos: BlockPos): BlockState =
            blocks[pos.asLong()] ?: (if (pos.y < 10) Blocks.STONE else Blocks.AIR).defaultBlockState()
    }

    private fun corridor(x0: Int, z0: Int, z1: Int) = Piece(MineshaftPieces.CORRIDOR_KIND, Box(x0, 10, z0, x0 + 2, 12, z1))
    private fun room(x: Int, z: Int) = Piece(MineshaftPieces.ROOM_KIND, Box(x, 10, z, x + 6, 13, z + 6))

    @Test
    fun supportsFiveApartAreOneCorridor() {
        // Three supports along z from 4, posts at x 0 and 2 on stone, planks on top, then one dug out and two more.
        val blocks = HashMap<Long, BlockState>()
        for (z in listOf(4, 9, 14, 24, 29)) for (x in listOf(0, 2)) {
            blocks[BlockPos.asLong(x, 10, z)] = Blocks.OAK_FENCE.defaultBlockState()
            blocks[BlockPos.asLong(x, 11, z)] = Blocks.OAK_FENCE.defaultBlockState()
            for (across in 0..2) blocks[BlockPos.asLong(across, 12, z)] = Blocks.OAK_PLANKS.defaultBlockState()
        }
        val d = Detection(1, StructureType.MINESHAFT, "minecraft:overworld")
        for ((key, state) in blocks) if (state.block == Blocks.OAK_FENCE) d.add(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), state.block)
        val corridors = MineshaftPieces.corridors(d, World(blocks)).map { it.box }.sortedBy { it.minZ }
        assertEquals(listOf(Box(0, 10, 2, 2, 12, 16), Box(0, 10, 22, 2, 12, 31)), corridors)
        // The gap where a support was dug out is joined across.
        assertTrue(MineshaftPieces.joined(Piece(MineshaftPieces.CORRIDOR_KIND, corridors[0]), Piece(MineshaftPieces.CORRIDOR_KIND, corridors[1])))
    }

    @Test
    fun eachRoomKeepsTheCorridorsJoinedToIt() {
        // Two mineshafts side by side, each a room with a corridor run north from it; the second's
        // corridor crosses through the first's, overlapping it, which is not a join.
        val west = listOf(room(2, 2), corridor(4, 9, 38), corridor(4, 44, 63))
        val east = listOf(room(34, 2), corridor(36, 9, 28), Piece(MineshaftPieces.CORRIDOR_KIND, Box(0, 10, 30, 40, 12, 32)))
        val parts = MineshaftPieces.split(west + east)
        assertEquals(2, parts.size)
        assertEquals(west.toSet(), parts.single { west[0] in it }.toSet())
        assertEquals(east.toSet(), parts.single { east[0] in it }.toSet())
    }

    @Test
    fun oneRoomIsOneMineshaft() {
        val pieces = listOf(room(2, 2), corridor(4, 9, 38), corridor(40, 9, 38))
        assertEquals(1, MineshaftPieces.split(pieces).size)
    }
}
