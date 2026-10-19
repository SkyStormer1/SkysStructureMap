package com.skystormer.skysstructuremap

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Blocks

/**
 * Whether a chest is one the game buried. It digs down from the sea floor in its chunk's column
 * (9, 9) to the first stone or sandstone, sets the chest on it, and fills every side that is air or
 * water with sandstone and the top with sand (the game's `BuriedTreasurePieces`). So a buried one
 * stands on one of those and is walled in; only above may be open, where it was dug down to.
 */
object BuriedTreasure {

    fun buried(box: Box, level: BlockSource): Boolean {
        val chest = BlockPos(box.minX, box.minY, box.minZ)
        if (!level.hasChunk(chest.x shr 4, chest.z shr 4)) return false
        if (level.getBlockState(chest.below()).block !in FLOOR) return false
        return Direction.Plane.HORIZONTAL.all { side ->
            val state = level.getBlockState(chest.relative(side))
            !state.isAir && state.fluidState.isEmpty
        }
    }

    /** What the game sets it on. */
    private val FLOOR = setOf(Blocks.SANDSTONE, Blocks.STONE, Blocks.ANDESITE, Blocks.GRANITE, Blocks.DIORITE)
}
