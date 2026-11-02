package com.skystormer.skysstructuremap

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * The shape of a structure the game builds in code from its chunk's north-west corner, one way
 * round of four: where it puts its blocks and its air, learned from ones it built (a layout file in
 * `assets/skysstructuremap`). The game picks among a few blocks at random for each spot (cobblestone
 * or mossy cobblestone), so the blocks say little; where they stand says a lot. It holds when the
 * piece that proves one cannot be seen: hidden by a server's anti-xray, which shows ores in place
 * of what is underground, dug out, or looted.
 *
 * @param solid the blocks the layout's `s` cells may hold.
 * @param below how far the structure's box reaches under the layout's first layer.
 * @param minStone how many of the layout's `s` cells must hold one of [solid], at least.
 */
class CodeLayout(
    private val resource: String,
    private val width: Int,
    private val depth: Int,
    private val solid: Set<Block>,
    private val below: Int,
    private val minStone: Int,
) {
    /**
     * The layout's cells as offsets in the footprint (x, y, z packed by [pack]), whether each is
     * stone, and how many stone cells there are from each on, so a placement that can no longer
     * pass is left at once.
     */
    private class Layout(val cells: IntArray, val stone: BooleanArray, val height: Int) {
        val stoneFrom = IntArray(cells.size + 1).also { from ->
            for (i in cells.indices.reversed()) from[i] = from[i + 1] + if (stone[i]) 1 else 0
        }
    }

    private val layout: Layout by lazy {
        val stream = CodeLayout::class.java.getResourceAsStream("/assets/skysstructuremap/$resource")
            ?: return@lazy Layout(IntArray(0), BooleanArray(0), 0).also { Log.warn("{} is missing from the jar", resource) }
        val cells = ArrayList<Int>()
        val stone = ArrayList<Boolean>()
        var y = -1
        var z = 0
        var top = 0
        for (line in stream.bufferedReader().readLines()) {
            if (line.startsWith("#")) continue
            if (line.isEmpty()) { y++; z = 0; continue }
            for ((x, c) in line.withIndex()) {
                if (c != 's' && c != '.') continue
                cells.add(pack(x, y, z))
                stone.add(c == 's')
                top = maxOf(top, y)
            }
            z++
        }
        Layout(cells.toIntArray(), stone.toBooleanArray(), top + 1)
    }

    private fun pack(x: Int, y: Int, z: Int) = (x shl 16) or (y shl 8) or z

    /**
     * The whole structure, if one stands where [seen] (what was seen of it) is: of the [solid]
     * blocks found where the layout has stone or air, at least [MIN_FIT] where it has stone, and at
     * least [minStone] such blocks, from whichever chunk corner it could start at, whichever way it
     * faces and at whichever height what was seen sits in it.
     */
    fun fit(seen: Box, level: BlockSource): Box? {
        val layout = layout
        if (layout.cells.isEmpty()) return null
        val cursor = BlockPos.MutableBlockPos()
        // What was seen sits somewhere in the layout's height: from its top at the layout's top, to its floor at the floor.
        val lowest = minOf(seen.maxY - layout.height + 1, seen.minY)
        val highest = maxOf(seen.maxY - layout.height + 1, seen.minY)
        for (way in 0 until 8) {
            // The eight ways a footprint can be turned or mirrored: swap x and z or not, then flip either.
            val swapped = way and 4 != 0
            val sizeX = if (swapped) depth else width
            val sizeZ = if (swapped) width else depth
            for (cornerX in corners(seen.minX, seen.maxX, sizeX)) {
                for (cornerZ in corners(seen.minZ, seen.maxZ, sizeZ)) {
                    for (baseY in lowest..highest) {
                        var fits = 0
                        var misplaced = 0
                        for (i in layout.cells.indices) {
                            // Too few stone cells left to reach [minStone], or so many misplaced that even every one left fitting would not make [MIN_FIT].
                            val most = fits + layout.stoneFrom[i]
                            if (most < minStone || most < MIN_FIT * (most + misplaced)) break
                            val cell = layout.cells[i]
                            var dx = cell shr 16
                            val dy = (cell shr 8) and 255
                            var dz = cell and 255
                            if (swapped) { val t = dx; dx = dz; dz = t }
                            if (way and 1 != 0) dx = sizeX - 1 - dx
                            if (way and 2 != 0) dz = sizeZ - 1 - dz
                            val x = cornerX + dx
                            val z = cornerZ + dz
                            if (!level.hasChunk(x shr 4, z shr 4)) continue
                            if (level.getBlockState(cursor.set(x, baseY + dy, z)).block !in solid) continue
                            if (layout.stone[i]) fits++ else misplaced++
                        }
                        if (fits >= minStone && fits >= MIN_FIT * (fits + misplaced)) {
                            return Box(cornerX, baseY - below, cornerZ, cornerX + sizeX - 1, baseY + layout.height - 1, cornerZ + sizeZ - 1)
                        }
                    }
                }
            }
        }
        return null
    }

    /** Chunk corners a footprint [size] long could start at and still hold [min]..[max]. */
    private fun corners(min: Int, max: Int, size: Int): List<Int> =
        (-Math.floorDiv(-(max - size + 1), 16)..Math.floorDiv(min, 16)).map { it * 16 }

    companion object {
        private const val MIN_FIT = 0.9

        /**
         * The jungle temple (`JungleTemplePiece`), learned from one untouched. It fits all of its
         * 1,153; one with its lower half dug out and the rest hidden by anti-xray, 436 of 449; a
         * player's cobblestone ruin in the same jungle, 37 of 108.
         */
        val JUNGLE_TEMPLE = CodeLayout(
            "jungle_temple_layout.txt", 12, 15,
            setOf(Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE_STAIRS), below = 0, minStone = 200,
        )

        /**
         * The desert pyramid (`DesertPyramidPiece`) from its floor up, learned from 13. Its box
         * reaches down to the hidden chamber, as one measured from the floor's cross does
         * ([TemplePieces.desertBox]).
         */
        val DESERT_TEMPLE = CodeLayout(
            "desert_temple_layout.txt", 21, 21,
            setOf(
                Blocks.SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.SANDSTONE_STAIRS,
                Blocks.SANDSTONE_SLAB, Blocks.SANDSTONE_WALL, Blocks.SMOOTH_SANDSTONE_STAIRS, Blocks.SMOOTH_SANDSTONE_SLAB,
                Blocks.CUT_SANDSTONE_SLAB, Blocks.DYED_TERRACOTTA.orange(), Blocks.DYED_TERRACOTTA.blue(),
            ),
            below = 14, minStone = 300,
        )

        /** The witch hut (`SwampHutPiece`) from the top of its stilts up, learned from 14; its box is [Recognise.witchHutBox]'s. */
        val WITCH_HUT = CodeLayout(
            "witch_hut_layout.txt", 7, 9,
            setOf(Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB, Blocks.OAK_FENCE, Blocks.OAK_LOG), below = 0, minStone = 60,
        )
    }
}
