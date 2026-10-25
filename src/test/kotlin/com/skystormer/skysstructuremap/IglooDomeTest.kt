package com.skystormer.skysstructuremap

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Igloos told by their dome ([IglooFit.dome]): laid from the game's own design at its chunk's corner,
 * the way round the game lays it there, with what the scanner keeps of it as the blocks seen.
 */
class IglooDomeTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setUp() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        /** The corner of a chunk, where the game lays an igloo not turned. */
        private const val X = 160
        private const val Y = 96
        private const val Z = -320
    }

    private class World(val blocks: Map<Long, BlockState>) : BlockSource {
        override fun hasChunk(chunkX: Int, chunkZ: Int) = true
        override fun getBlockState(pos: BlockPos): BlockState = blocks[pos.asLong()] ?: Blocks.AIR.defaultBlockState()
    }

    /** The design's blocks at the corner, those [keep] says; its inside left as air unless [filled]. */
    private fun laid(filled: Boolean = false, keep: (String) -> Boolean): HashMap<Long, BlockState> {
        val template = IglooFit.templates.single()
        val out = HashMap<Long, BlockState>()
        for ((pos, name) in template.blocks) {
            if (!keep(name)) continue
            out[BlockPos.asLong(X + pos.x, Y + pos.y, Z + pos.z)] = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(name)).defaultBlockState()
        }
        if (filled) for (pos in template.air) out[BlockPos.asLong(X + pos.x, Y + pos.y, Z + pos.z)] = Blocks.SNOW_BLOCK.defaultBlockState()
        return out
    }

    /** What the scanner keeps ([Specs] for igloos): furnishings, and snow only with air under it. */
    private fun seen(blocks: Map<Long, BlockState>): Detection {
        val d = Detection(1, StructureType.IGLOO, "minecraft:overworld")
        val spec = Specs.of(StructureType.IGLOO)
        for ((key, state) in blocks) {
            if (!spec.matches(state)) continue
            if (state.block in spec.overAir && blocks[BlockPos.offset(key, net.minecraft.core.Direction.DOWN)]?.isAir == false) continue
            d.add(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), state.block)
        }
        return d
    }

    private fun dome(blocks: Map<Long, BlockState>): TemplateFit.Match? {
        val d = seen(blocks)
        return IglooFit.fit(d, World(blocks), accept = IglooFit::refusal) ?: IglooFit.dome.fit(d, World(blocks), accept = IglooFit::refusal)
    }

    @Test
    fun anEmptiedIglooIsFoundByItsDome() {
        // Its bed, furnace, crafting table, carpet, torch and trapdoor all taken.
        val blocks = laid { it == "snow_block" || it == "ice" }
        val match = dome(blocks)
        assertNotNull(match, IglooFit.dome.lastReport)
        assertEquals(Box(X, Y, Z, X + 6, Y + 4, Z + 7), match!!.box)
        assertEquals(10 to -20, IglooFit.startChunk(match))
    }

    @Test
    fun aWholeOneIsStillFoundByItsFurnishings() {
        val blocks = laid { true }
        assertNotNull(IglooFit.fit(seen(blocks), World(blocks), accept = IglooFit::refusal), IglooFit.lastReport)
    }

    @Test
    fun aDriftOfSnowItsShapeIsNot() {
        // Solid, but for the layer under its top, so its top is over air as an igloo's roof is.
        val blocks = laid(filled = true) { it == "snow_block" }
        for (x in X..X + 6) for (z in Z..Z + 7) blocks.remove(BlockPos.asLong(x, Y + 3, z))
        assertNull(dome(blocks))
        assertTrue("its open space is not" in IglooFit.dome.lastReport, IglooFit.dome.lastReport)
    }

    @Test
    fun oneOffItsSpotInTheChunkIsNot() {
        // The game lays one only from its chunk's corner; the same dome a block over is a player's.
        val blocks = HashMap<Long, BlockState>()
        for ((key, state) in laid { it == "snow_block" || it == "ice" }) blocks[BlockPos.offset(key, 1, 0, 0)] = state
        assertNull(dome(blocks))
    }
}
