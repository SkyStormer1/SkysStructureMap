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
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Ocean ruins as the game leaves them ([OceanRuinFit]): laid from its own designs, with their stone
 * bricks mixed plain, cracked and mossy whichever design it is, and their sea-floor sand and gravel
 * gone to water, as ruins seen in testing are.
 */
class OceanRuinFitTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setUp() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        private const val X = 1000
        private const val Y = 40
        private const val Z = -2000

        private val BRICKS by lazy { listOf(Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.STONE_BRICKS) }
    }

    private class World(val blocks: Map<Long, BlockState>) : BlockSource {
        override fun hasChunk(chunkX: Int, chunkZ: Int) = true
        override fun getBlockState(pos: BlockPos): BlockState = blocks[pos.asLong()] ?: Blocks.WATER.defaultBlockState()
    }

    /** Design [name] at the corner, its stone bricks mixed, and its sand and gravel left out. */
    private fun ruin(name: String): Map<Long, BlockState> {
        val out = HashMap<Long, BlockState>()
        for ((pos, family) in OceanRuinFit.templates.single { it.name == name }.blocks) {
            if (family == "gravel" || family == "sand") continue
            val block = if (family == "stone_bricks") BRICKS[Math.floorMod(pos.x * 7 + pos.y * 3 + pos.z, BRICKS.size)]
            else BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(family))
            out[BlockPos.asLong(X + pos.x, Y + pos.y, Z + pos.z)] = block.defaultBlockState()
        }
        return out
    }

    private fun fit(blocks: Map<Long, BlockState>): TemplateFit.Match? {
        val d = Detection(1, StructureType.OCEAN_RUIN, "minecraft:overworld")
        val spec = Specs.of(StructureType.OCEAN_RUIN)
        for ((key, state) in blocks) if (spec.matches(state)) d.add(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), state.block)
        return OceanRuinFit.fit(d, World(blocks))
    }

    @Test
    fun aColdRuinOfMixedBricksWithItsGravelGoneIsFound() {
        val match = fit(ruin("cracked_6"))
        assertNotNull(match, OceanRuinFit.lastReport)
        assertEquals(Box(X, Y, Z, X + 5, Y + 6, Z + 6), match!!.box)
    }

    @Test
    fun aWarmRuinWithItsSandGoneIsFound() {
        assertNotNull(fit(ruin("warm_3")), OceanRuinFit.lastReport)
    }

    @Test
    fun theSameBricksInRockAreNot() {
        // A stronghold's corridor is the same mixed bricks, with rock round it, not the sea.
        val blocks = HashMap(ruin("cracked_6"))
        for (x in X - 2..X + 8) for (z in Z - 2..Z + 8) for (y in Y - 1..Y + 8) blocks.putIfAbsent(BlockPos.asLong(x, y, z), Blocks.DEEPSLATE.defaultBlockState())
        assertNull(fit(blocks), OceanRuinFit.lastReport)
    }

    @Test
    fun theSameBricksRoundCaveAirAreNot() {
        // Or round the air of the corridor itself: open, but not the sea.
        val blocks = HashMap(ruin("cracked_6"))
        for (x in X - 2..X + 8) for (z in Z - 2..Z + 8) for (y in Y - 1..Y + 8) blocks.putIfAbsent(BlockPos.asLong(x, y, z), Blocks.CAVE_AIR.defaultBlockState())
        assertNull(fit(blocks), OceanRuinFit.lastReport)
    }

    @Test
    fun aWallOfStoneBricksIsNot() {
        // A player's wall under the sea, the bricks a cold ruin is made of.
        val blocks = HashMap<Long, BlockState>()
        for (x in 0 until 9) for (y in 0 until 5) blocks[BlockPos.asLong(X + x, Y + y, Z)] = BRICKS[(x + y) % BRICKS.size].defaultBlockState()
        val match = fit(blocks)
        assertNull(match, "${match?.template?.name} ${match?.rotation} ${match?.matched}/${match?.known}: ${OceanRuinFit.lastReport}")
    }
}
