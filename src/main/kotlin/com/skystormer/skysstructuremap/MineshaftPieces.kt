package com.skystormer.skysstructuremap

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * A mineshaft's corridors are held up by supports the game lays the same way every time
 * (`MineshaftPieces.MineShaftCorridor.placeSupport`): two fence posts two blocks high, two blocks
 * apart across the corridor, with planks on top of each (and nearly always across the gap), and the
 * corridor open between them. Fences are what farms and houses are fenced with; a run of these
 * frames is not.
 */
object MineshaftPieces {

    /** Supports needed before a group of fences is a mineshaft. */
    const val SUPPORTS = 3

    private val FENCES: Set<Block> = setOf(Blocks.OAK_FENCE, Blocks.DARK_OAK_FENCE)
    private val PLANKS: Set<Block> = setOf(Blocks.OAK_PLANKS, Blocks.DARK_OAK_PLANKS)

    /** Whether [detection]'s fences hold up at least [SUPPORTS] corridor supports. */
    fun proved(detection: Detection, level: BlockSource): Boolean = supports(detection, level, SUPPORTS) >= SUPPORTS

    /** How many supports [detection]'s fences make, counting no further than [enough]. */
    fun supports(detection: Detection, level: BlockSource, enough: Int = Int.MAX_VALUE): Int {
        val cursor = BlockPos.MutableBlockPos()
        var found = 0
        val iterator = detection.positions.iterator()
        while (iterator.hasNext() && found < enough) {
            val key = iterator.nextLong()
            val x = BlockPos.getX(key)
            val y = BlockPos.getY(key)
            val z = BlockPos.getZ(key)
            // Each support is counted from its west or north post, whose partner is 2 east or south.
            if (!post(level, cursor, x, y, z)) continue
            if (support(level, cursor, x, y, z, 1, 0) || support(level, cursor, x, y, z, 0, 1)) found++
        }
        return found
    }

    /** A post standing at [x], [y], [z]: two fences on a block that is not one, under planks. */
    private fun post(level: BlockSource, cursor: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): Boolean =
        isFence(level, cursor.set(x, y, z)) && isFence(level, cursor.set(x, y + 1, z)) &&
            !isFence(level, cursor.set(x, y - 1, z)) && isPlanks(level, cursor.set(x, y + 2, z))

    /** The other post 2 along ([dx], [dz]), and the corridor open between the two. */
    private fun support(level: BlockSource, cursor: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int, dx: Int, dz: Int): Boolean {
        if (!post(level, cursor, x + 2 * dx, y, z + 2 * dz)) return false
        // Rails, cobwebs and torches stand in a corridor; nothing that fills it does.
        return !blocks(level, cursor.set(x + dx, y, z + dz)) && !blocks(level, cursor.set(x + dx, y + 1, z + dz))
    }

    private fun isFence(level: BlockSource, pos: BlockPos): Boolean =
        level.hasChunk(pos.x shr 4, pos.z shr 4) && level.getBlockState(pos).block in FENCES

    private fun isPlanks(level: BlockSource, pos: BlockPos): Boolean =
        level.hasChunk(pos.x shr 4, pos.z shr 4) && level.getBlockState(pos).block in PLANKS

    private fun blocks(level: BlockSource, pos: BlockPos): Boolean =
        !level.hasChunk(pos.x shr 4, pos.z shr 4) || level.getBlockState(pos).blocksMotion()
}
