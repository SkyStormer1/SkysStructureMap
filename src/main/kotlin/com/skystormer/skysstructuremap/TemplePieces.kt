package com.skystormer.skysstructuremap

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks

/**
 * The two temples the game builds in code rather than from a saved design, recognised by the parts
 * of that code no one builds by accident: the desert pyramid's terracotta cross, and the jungle
 * temple's puzzle of levers and pistons. Sandstone in a desert and cobblestone in a jungle are what
 * players build with, so the blocks alone say nothing.
 */
object TemplePieces {

    /**
     * The orange and blue terracotta the game lays into a desert pyramid's floor: a diamond three
     * across, in the middle of the temple, over the hidden chamber. Thirteen blocks, of which ten
     * must still be there, so a looted temple with part of its floor dug out still counts.
     *
     * (`DesertPyramidPiece` in the game places them at 10,0,7 through 10,0,13 of its own 21 × 21
     * footprint; these are the same spots as offsets from the middle one.)
     */
    private val CROSS = listOf(
        0 to 0, 0 to -3, 0 to -2, -1 to -1, 1 to -1, -2 to 0, 2 to 0, -3 to 0, 3 to 0, -1 to 1, 1 to 1, 0 to 2, 0 to 3,
    )

    private const val CROSS_NEEDED = 10

    /** Whether a desert pyramid's floor cross is among [detection]'s blocks. */
    fun desertCross(detection: Detection, level: BlockSource): Boolean {
        val cursor = BlockPos.MutableBlockPos()
        val iterator = detection.positions.iterator()
        while (iterator.hasNext()) {
            val key = iterator.nextLong()
            val x = BlockPos.getX(key)
            val y = BlockPos.getY(key)
            val z = BlockPos.getZ(key)
            if (!isTerracotta(level, cursor.set(x, y, z))) continue
            // Every block of the cross is one of these, so any of them can be its middle: each is
            // tried as the middle in turn, and the rest of the diamond looked for around it.
            for ((ox, oz) in CROSS) {
                var found = 0
                for ((dx, dz) in CROSS) {
                    if (isTerracotta(level, cursor.set(x - ox + dx, y, z - oz + dz))) found++
                }
                if (found >= CROSS_NEEDED) return true
            }
        }
        return false
    }

    private fun isTerracotta(level: BlockSource, pos: BlockPos): Boolean {
        if (!level.hasChunk(pos.x shr 4, pos.z shr 4)) return false
        val block = level.getBlockState(pos).block
        return block == Blocks.DYED_TERRACOTTA.orange() || block == Blocks.DYED_TERRACOTTA.blue()
    }

    /**
     * The jungle temple's puzzle: three chiselled stone bricks in a row, which the game uses for
     * the lever wall, with one of its sticky pistons close by. Both belong to the trap it builds
     * under the temple (`JungleTemplePiece`), and a jungle's cobblestone ruins of a player's making
     * have neither.
     */
    fun jungleTrap(detection: Detection, level: BlockSource): Boolean {
        val cursor = BlockPos.MutableBlockPos()
        val iterator = detection.positions.iterator()
        while (iterator.hasNext()) {
            val key = iterator.nextLong()
            val x = BlockPos.getX(key)
            val y = BlockPos.getY(key)
            val z = BlockPos.getZ(key)
            if (!isBlock(level, cursor.set(x, y, z), Blocks.CHISELED_STONE_BRICKS)) continue
            val inARow = (isBlock(level, cursor.set(x + 1, y, z), Blocks.CHISELED_STONE_BRICKS) &&
                isBlock(level, cursor.set(x + 2, y, z), Blocks.CHISELED_STONE_BRICKS)) ||
                (isBlock(level, cursor.set(x, y, z + 1), Blocks.CHISELED_STONE_BRICKS) &&
                    isBlock(level, cursor.set(x, y, z + 2), Blocks.CHISELED_STONE_BRICKS))
            if (!inARow) continue
            if (pistonNear(level, cursor, x, y, z)) return true
        }
        return false
    }

    /** A sticky piston within [PISTON_REACH] of the lever wall, as the game's trap has. */
    private fun pistonNear(level: BlockSource, cursor: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): Boolean {
        for (dx in -PISTON_REACH..PISTON_REACH) {
            for (dy in -PISTON_REACH..PISTON_REACH) {
                for (dz in -PISTON_REACH..PISTON_REACH) {
                    if (isBlock(level, cursor.set(x + dx, y + dy, z + dz), Blocks.STICKY_PISTON)) return true
                }
            }
        }
        return false
    }

    private const val PISTON_REACH = 5

    private fun isBlock(level: BlockSource, pos: BlockPos, block: net.minecraft.world.level.block.Block): Boolean =
        level.hasChunk(pos.x shr 4, pos.z shr 4) && level.getBlockState(pos).block == block
}
