package com.skystormer.skysstructuremap

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks

/**
 * The boxes inside a nether fortress that mobs spawn in, worked out from its blocks.
 *
 * A fortress spawns its blazes, wither skeletons and the rest only inside its pieces' boxes, not
 * anywhere in the fortress's overall box. The piece that matters most for farms is the bridge
 * crossroads: a 19 × 10 × 19 box around a plus-shaped deck of nether bricks, five wide and two
 * thick, three blocks up from the bottom of the box. The plus is the same turned any way, so the
 * centre of the deck gives the box exactly (checked against the boxes the game saved for a real
 * fortress).
 *
 * The fortress's own box: sideways, exactly as far as its nether bricks go (the pillars under the
 * bridges stand inside it); upwards, the highest brick (a bridge's railing) plus the four blocks of
 * air above it that the bridge's box keeps; downwards, y 48 (see [LOWEST]), which nearly every fortress
 * starts at.
 */
object FortressPieces {

    const val CROSSROADS = "crossroads"

    /** How much of a crossroads' deck must still be there to count, so a few broken blocks do not lose it. */
    private const val MIN_DECK = 0.9

    /** How much of the corners around the plus may be nether bricks: a castle floor is all bricks there. */
    private const val MAX_CORNERS = 0.2

    /** Every crossroads among [detection]'s blocks, as its piece box. */
    fun crossroads(detection: Detection, level: Level): List<Box> {
        val found = ArrayList<Pair<Box, Double>>()
        val positions = detection.positions
        val iterator = positions.iterator()
        while (iterator.hasNext()) {
            val key = iterator.nextLong()
            val x = BlockPos.getX(key)
            val y = BlockPos.getY(key)
            val z = BlockPos.getZ(key)
            // A deck centre: brick below and above it in the deck, air over the deck, and the ends
            // of all four arms nine blocks away.
            if (!positions.contains(BlockPos.asLong(x, y + 1, z))) continue
            if (positions.contains(BlockPos.asLong(x, y + 2, z))) continue
            if (!positions.contains(BlockPos.asLong(x + 9, y, z)) || !positions.contains(BlockPos.asLong(x - 9, y, z))) continue
            if (!positions.contains(BlockPos.asLong(x, y, z + 9)) || !positions.contains(BlockPos.asLong(x, y, z - 9))) continue
            val box = Box(x - 9, y - 3, z - 9, x + 9, y + 6, z + 9)
            if (found.any { it.first == box }) continue
            val score = deckScore(level, x, y, z) ?: continue
            found.add(box to score)
        }
        // A spot a block or two off a real centre can pass too; of overlapping ones, the best is it.
        val kept = ArrayList<Box>()
        for ((box, _) in found.sortedByDescending { it.second }) {
            if (kept.none { it.overlaps(box) }) kept.add(box)
        }
        return kept
    }

    /**
     * How well a crossroads' deck fits at ([cx], [y], [cz]), or null for not at all: nearly all of
     * the plus must be nether bricks in both layers, and the four corners around it open (air or
     * lava). The corners are what tell a crossroads from a castle's solid floor.
     */
    private fun deckScore(level: Level, cx: Int, y: Int, cz: Int): Double? {
        val cursor = BlockPos.MutableBlockPos()
        var cells = 0
        var bricks = 0
        var corners = 0
        var cornerBricks = 0
        for (dx in -9..9) {
            for (dz in -9..9) {
                // The plus: within two blocks of either centre line. Everything else is a corner.
                val inPlus = Math.abs(dx) <= 2 || Math.abs(dz) <= 2
                for (dy in 0..1) {
                    cursor.set(cx + dx, y + dy, cz + dz)
                    if (!level.hasChunk(cursor.x shr 4, cursor.z shr 4)) continue
                    val brick = level.getBlockState(cursor).`is`(Blocks.NETHER_BRICKS)
                    if (inPlus) {
                        cells++
                        if (brick) bricks++
                    } else {
                        corners++
                        if (brick) cornerBricks++
                    }
                }
            }
        }
        if (cells == 0 || bricks < cells * MIN_DECK) return null
        if (corners > 0 && cornerBricks > corners * MAX_CORNERS) return null
        return bricks.toDouble() / cells - (if (corners > 0) cornerBricks.toDouble() / corners else 0.0)
    }

    /** The fortress's whole box from its seen blocks and crossroads (see the class notes). */
    fun outerBox(bricks: Box, crossroads: List<Box>): Box {
        val top = maxOf(bricks.maxY + 4, crossroads.maxOfOrNull { it.maxY } ?: Int.MIN_VALUE)
        val bottom = if (top >= ALWAYS_LOWEST_FROM) LOWEST
        else crossroads.minOfOrNull { it.minY } ?: bricks.minY
        return Box(bricks.minX, bottom, bricks.minZ, bricks.maxX, top, bricks.maxZ)
    }

    /**
     * The lowest a fortress's box can start (y 48), and the top from which it certainly starts
     * there: the game places a fortress anywhere with its box inside y 48..70 if it fits with room
     * to spare, and at 48 otherwise, so a top at 70 or above can only mean a bottom at 48.
     */
    private const val LOWEST = 48
    private const val ALWAYS_LOWEST_FROM = 70
}
