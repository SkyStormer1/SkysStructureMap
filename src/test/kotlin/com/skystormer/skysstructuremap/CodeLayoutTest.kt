package com.skystormer.skysstructuremap

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Structures the game builds in code, told by their shape ([CodeLayout]): the ones here are laid
 * from the layouts themselves, turned, raised, dug out and hidden as on a server with anti-xray.
 */
class CodeLayoutTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setUp() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        /** A layout's stone, as offsets from its corner: x, y, z. */
        private fun stone(resource: String): List<Triple<Int, Int, Int>> {
            val lines = CodeLayoutTest::class.java.getResourceAsStream("/assets/skysstructuremap/$resource")!!.bufferedReader().readLines()
            val out = ArrayList<Triple<Int, Int, Int>>()
            var y = -1
            var z = 0
            for (line in lines) {
                if (line.startsWith("#")) continue
                if (line.isEmpty()) { y++; z = 0; continue }
                line.forEachIndexed { x, c -> if (c == 's') out.add(Triple(x, y, z)) }
                z++
            }
            return out
        }
    }

    /** Blocks by position; everything else air; every chunk there. */
    private class World(val blocks: Map<Long, BlockState>) : BlockSource {
        override fun hasChunk(chunkX: Int, chunkZ: Int) = true
        override fun getBlockState(pos: BlockPos): BlockState = blocks[pos.asLong()] ?: Blocks.AIR.defaultBlockState()
    }

    /**
     * The layout in [resource] ([width] along x as written) at the corner of chunk ([chunkX],
     * [chunkZ]), its first layer at [floor], turned to face east (x and z swapped, then mirrored
     * along x), of [blocks] in turn, with only the layers [keep] says.
     */
    private fun laid(
        resource: String, depth: Int, chunkX: Int, chunkZ: Int, floor: Int, blocks: List<Block>, keep: (Int) -> Boolean = { true },
    ): Map<Long, BlockState> {
        val out = HashMap<Long, BlockState>()
        for ((x, y, z) in stone(resource)) {
            if (!keep(y)) continue
            val wx = chunkX * 16 + (depth - 1 - z)
            val wz = chunkZ * 16 + x
            out[BlockPos.asLong(wx, floor + y, wz)] = blocks[Math.floorMod(wx + y + wz, blocks.size)].defaultBlockState()
        }
        return out
    }

    private fun seen(blocks: Map<Long, BlockState>): Box {
        val xs = blocks.keys.map(BlockPos::getX)
        val ys = blocks.keys.map(BlockPos::getY)
        val zs = blocks.keys.map(BlockPos::getZ)
        return Box(xs.min(), ys.min(), zs.min(), xs.max(), ys.max(), zs.max())
    }

    private val cobble = listOf(Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE)

    @Test
    fun aWholeJungleTempleIsFoundAtItsChunksCorner() {
        val blocks = laid("jungle_temple_layout.txt", 15, -159, -201, 60, cobble)
        assertEquals(Box(-2544, 60, -3216, -2530, 73, -3205), CodeLayout.JUNGLE_TEMPLE.fit(seen(blocks), World(blocks)))
    }

    @Test
    fun oneDugOutBelowAndHiddenByAntiXrayIsFoundFromWhatIsLeft() {
        // Its lower half gone, and what was underground shown as ores, as a server with anti-xray does.
        val blocks = HashMap(laid("jungle_temple_layout.txt", 15, 36, -397, 86, cobble) { y -> y >= 7 })
        for (x in 576..590) for (z in -6352..-6341) for (y in 80..89) blocks[BlockPos.asLong(x, y, z)] = Blocks.DIAMOND_ORE.defaultBlockState()
        val top = blocks.filterValues { it.block != Blocks.DIAMOND_ORE }
        assertEquals(Box(576, 86, -6352, 590, 99, -6341), CodeLayout.JUNGLE_TEMPLE.fit(seen(top), World(blocks)))
    }

    @Test
    fun aPlayersCobblestoneBuildIsNot() {
        // A solid cobblestone house the temple's size, at a chunk's corner.
        val blocks = HashMap<Long, BlockState>()
        for (x in 0..11) for (z in 0..14) for (y in 70..76) {
            if (x == 0 || x == 11 || z == 0 || z == 14 || y == 70 || y == 76) blocks[BlockPos.asLong(x, y, z)] = Blocks.COBBLESTONE.defaultBlockState()
        }
        assertNull(CodeLayout.JUNGLE_TEMPLE.fit(seen(blocks), World(blocks)))
    }

    @Test
    fun tooLittleOfOneIsNotEnough() {
        val blocks = laid("jungle_temple_layout.txt", 15, 0, 0, 64, cobble) { y -> y == 12 || y == 13 }
        assertNull(CodeLayout.JUNGLE_TEMPLE.fit(seen(blocks), World(blocks)))
    }

    @Test
    fun aDesertPyramidWithItsFloorDugUpIsFoundByItsShape() {
        // The floor (with its terracotta cross) dug out to get at the chests below.
        val sandstone = listOf(Blocks.SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.SANDSTONE_STAIRS)
        val blocks = laid("desert_temple_layout.txt", 21, -186, -141, 64, sandstone) { y -> y >= 1 }
        // Its box reaches 14 down to the hidden chamber, as one measured from the cross does.
        assertEquals(Box(-2976, 50, -2256, -2956, 78, -2236), CodeLayout.DESERT_TEMPLE.fit(seen(blocks), World(blocks)))
    }

    @Test
    fun aSandstoneHouseIsNoDesertPyramid() {
        val blocks = HashMap<Long, BlockState>()
        for (x in 0..20) for (z in 0..20) for (y in 64..70) {
            if (x == 0 || x == 20 || z == 0 || z == 20 || y == 70) blocks[BlockPos.asLong(x, y, z)] = Blocks.CUT_SANDSTONE.defaultBlockState()
        }
        assertNull(CodeLayout.DESERT_TEMPLE.fit(seen(blocks), World(blocks)))
    }

    @Test
    fun aLootedWitchHutIsFoundByItsShape() {
        // Its cauldron, crafting table and flower pot all taken: no furniture left to go by.
        val wood = listOf(Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS)
        val blocks = laid("witch_hut_layout.txt", 9, -220, -204, 63, wood)
        assertEquals(Box(-3520, 63, -3264, -3512, 69, -3258), CodeLayout.WITCH_HUT.fit(seen(blocks), World(blocks)))
    }

    @Test
    fun aSpruceShedIsNoWitchHut() {
        val blocks = HashMap<Long, BlockState>()
        for (x in 0..6) for (z in 0..8) for (y in 64..68) {
            if (x == 0 || x == 6 || z == 0 || z == 8 || y == 68) blocks[BlockPos.asLong(x, y, z)] = Blocks.SPRUCE_PLANKS.defaultBlockState()
        }
        assertNull(CodeLayout.WITCH_HUT.fit(seen(blocks), World(blocks)))
    }
}
