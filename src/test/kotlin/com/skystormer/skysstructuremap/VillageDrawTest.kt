package com.skystormer.skysstructuremap

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * A village drawn from a house of it ([VillagePieces.draw]): its town centre found with its bell
 * taken, where the game lays one, from a chunk's corner and in the open.
 */
class VillageDrawTest {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setUp() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }

        private const val X = 1600
        private const val Y = 70
        private const val Z = -800
        private const val CENTRE = "plains/town_centers/plains_meeting_point_1"
        private const val HOUSE = "plains/houses/plains_small_house_4"
    }

    private class World(val blocks: Map<Long, BlockState>) : BlockSource {
        override fun hasChunk(chunkX: Int, chunkZ: Int) = true
        override fun getBlockState(pos: BlockPos): BlockState = blocks[pos.asLong()] ?: Blocks.AIR.defaultBlockState()
    }

    /** Design [name] of [fit]'s laid with its corner at [x], [y], [z], unturned, but for the blocks [leave] says to leave out. */
    private fun lay(
        out: MutableMap<Long, BlockState>, fit: TemplateFit, name: String, x: Int, y: Int, z: Int,
        leave: (BlockPos, String) -> Boolean = { _, _ -> false },
    ) {
        for ((pos, family) in fit.templates.single { it.name == name }.blocks) {
            if (leave(pos, family)) continue
            out[BlockPos.asLong(x + pos.x, y + pos.y, z + pos.z)] = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(family)).defaultBlockState()
        }
    }

    /** A plains house a little way from where the centre is laid, and the house as it fits from its door. */
    private fun withHouse(blocks: MutableMap<Long, BlockState>): TemplateFit.Match {
        lay(blocks, VillageHouseFit, HOUSE, X + 30, Y, Z + 20)
        val door = blocks.entries.first { (key, state) ->
            state.block == Blocks.OAK_DOOR && blocks[BlockPos.offset(key, net.minecraft.core.Direction.DOWN)]?.block != Blocks.OAK_DOOR
        }.key
        return checkNotNull(VillageHouseFit.fitAt(door, Blocks.OAK_DOOR, World(blocks))) { "the house does not fit from its door" }
    }

    @Test
    fun aCentreWithItsBellTakenIsFoundOnItsChunksCorner() {
        val blocks = HashMap<Long, BlockState>()
        lay(blocks, TownCentreFit, CENTRE, X, Y, Z) { _, family -> family == "bell" }
        val drawn = VillagePieces.draw(World(blocks), withHouse(blocks)) { null }
        val centre = checkNotNull(drawn.centre) { "no centre found" }
        assertEquals(CENTRE, centre.template.name)
        assertEquals(X to Z, BlockPos.getX(centre.origin) to BlockPos.getZ(centre.origin))
        assertEquals(2, drawn.pieces.size)
    }

    @Test
    fun noCentreIsLaidOffAChunksCorner() {
        val blocks = HashMap<Long, BlockState>()
        lay(blocks, TownCentreFit, CENTRE, X + 3, Y, Z + 5) { _, family -> family == "bell" }
        assertNull(VillagePieces.draw(World(blocks), withHouse(blocks)) { null }.centre)
    }

    @Test
    fun noCentreIsMostlyHiddenByAntiXray() {
        // Underground, where anti-xray shows ore for three blocks in four of it.
        val blocks = HashMap<Long, BlockState>()
        lay(blocks, TownCentreFit, CENTRE, X, Y, Z) { pos, _ -> (pos.x + pos.y + pos.z) % 4 != 0 }
        for ((pos, _) in TownCentreFit.templates.single { it.name == CENTRE }.blocks) {
            if ((pos.x + pos.y + pos.z) % 4 != 0) blocks[BlockPos.asLong(X + pos.x, Y + pos.y, Z + pos.z)] = Blocks.IRON_ORE.defaultBlockState()
        }
        assertNull(VillagePieces.draw(World(blocks), withHouse(blocks)) { null }.centre)
    }
}
