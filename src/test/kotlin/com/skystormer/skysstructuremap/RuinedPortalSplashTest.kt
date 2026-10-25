package com.skystormer.skysstructuremap

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Ruined portals with their obsidian and gold taken, as players leave them ([RuinedPortalFit]): told
 * by the netherrack the game splashes round one, which a player's netherrack floor is not.
 */
class RuinedPortalSplashTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setUp() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        private const val X = 3000
        private const val Y = 70
        private const val Z = 3000
    }

    private class World(val blocks: Map<Long, BlockState>) : BlockSource {
        override fun hasChunk(chunkX: Int, chunkZ: Int) = true
        override fun getBlockState(pos: BlockPos): BlockState =
            blocks[pos.asLong()] ?: (if (pos.y < Y) Blocks.STONE else Blocks.AIR).defaultBlockState()
    }

    /** Design portal_1 on stone ground with its obsidian and gold taken, and netherrack on the ground out to [splash] from its middle. */
    private fun looted(splash: Int, square: Boolean = false): Map<Long, BlockState> {
        val template = RuinedPortalFit.templates.single { it.name == "portal_1" }
        val out = HashMap<Long, BlockState>()
        val cx = X + template.sizeX / 2
        val cz = Z + template.sizeZ / 2
        for (x in cx - 30..cx + 30) for (z in cz - 30..cz + 30) {
            val d = if (square) maxOf(Math.abs(x - cx), Math.abs(z - cz)) else Math.abs(x - cx) + Math.abs(z - cz)
            if (d <= splash) out[BlockPos.asLong(x, Y - 1, z)] = Blocks.NETHERRACK.defaultBlockState()
        }
        for ((pos, family) in template.blocks) {
            if (family == "obsidian" || family == "gold_block") continue
            out[BlockPos.asLong(X + pos.x, Y + pos.y, Z + pos.z)] = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(family)).defaultBlockState()
        }
        return out
    }

    private fun fit(blocks: Map<Long, BlockState>): TemplateFit.Match? {
        val d = Detection(1, StructureType.RUINED_PORTAL, "minecraft:overworld")
        val spec = Specs.of(StructureType.RUINED_PORTAL)
        for ((key, state) in blocks) if (spec.matches(state)) d.add(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), state.block)
        val world = World(blocks)
        return RuinedPortalFit.fit(d, world, accept = RuinedPortalFit.refusal(world))
    }

    @Test
    fun oneWithItsObsidianTakenIsFoundByItsSplash() {
        assertNotNull(fit(looted(splash = 10)), RuinedPortalFit.lastReport)
    }

    @Test
    fun oneWithNoSplashLeftIsNot() {
        assertNull(fit(looted(splash = -1)))
    }

    @Test
    fun aNetherrackFloorRoundItIsNoSplash() {
        // A player's floor goes on square past where the game ever splashes.
        assertNull(fit(looted(splash = 24, square = true)))
    }
}
