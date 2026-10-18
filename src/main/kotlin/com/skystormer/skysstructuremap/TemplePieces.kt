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

    /**
     * The four blocks beside the middle, which the game leaves sandstone: never terracotta. A
     * badlands layer is terracotta all through, so it holds the whole cross anywhere: one of SiteB's
     * pyramids was placed on the badlands 70 blocks from where it stands.
     */
    private val GAPS = listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)

    /**
     * The middle block of a desert pyramid's floor cross among [detection]'s blocks, or null. The
     * cross is laid around 10,0,10 of the pyramid's 21 × 21 footprint, so the middle is the
     * pyramid's middle too ([desertBox]).
     */
    fun desertCross(detection: Detection, level: BlockSource): BlockPos? {
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
                // The game lays a pyramid from its chunk's corner, so its cross is 10 in from one;
                // the lower one under it, 4 down and a few blocks over, is not.
                if (Math.floorMod(x - ox - DESERT_HALF, 16) != 0 || Math.floorMod(z - oz - DESERT_HALF, 16) != 0) continue
                if (found >= CROSS_NEEDED && GAPS.none { (dx, dz) -> isTerracotta(level, cursor.set(x - ox + dx, y, z - oz + dz)) }) {
                    return BlockPos(x - ox, y, z - oz)
                }
            }
        }
        return null
    }

    /**
     * A desert pyramid's whole box from the middle of its floor cross: 21 × 21, from the hidden
     * chamber 14 below the floor to the tops of its towers 14 above. What is seen of one is never
     * the whole of it: its outer stairs and walls are plain sandstone, which is not looked for, so
     * the box from what was seen came out 18 blocks wide on one side and up to three off; and
     * where a desert village or a player's sandstone build touched it, hundreds of blocks across.
     */
    fun desertBox(cross: BlockPos): Box =
        Box(cross.x - DESERT_HALF, cross.y - DESERT_DEPTH, cross.z - DESERT_HALF, cross.x + DESERT_HALF, cross.y + DESERT_DEPTH, cross.z + DESERT_HALF)

    private const val DESERT_HALF = 10
    private const val DESERT_DEPTH = 14

    /**
     * A jungle temple's footprint: the game builds it from the north-west corner of its chunk, 12
     * by 15 blocks either way round (`JungleTemplePiece`). Of the places it could be near what was
     * seen, the one holding most of [detection]'s blocks; its height is what was seen within it.
     * The box from what was seen alone took in a player's cobblestone beside one.
     */
    fun jungleBox(detection: Detection): Box? {
        val seen = detection.bounds ?: return null
        val chunkX = Math.floorDiv(seen.centreX, 16)
        val chunkZ = Math.floorDiv(seen.centreZ, 16)
        val places = (chunkX - 1..chunkX + 1).flatMap { cx ->
            (chunkZ - 1..chunkZ + 1).flatMap { cz ->
                listOf(JUNGLE_WIDTH to JUNGLE_DEPTH, JUNGLE_DEPTH to JUNGLE_WIDTH).map { (w, d) ->
                    Box(cx * 16, seen.minY, cz * 16, cx * 16 + w - 1, seen.maxY, cz * 16 + d - 1)
                }
            }
        }
        val best = places.maxByOrNull { detection.countIn(it) } ?: return null
        return detection.boundsIn(best)
    }

    private const val JUNGLE_WIDTH = 12
    private const val JUNGLE_DEPTH = 15

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
