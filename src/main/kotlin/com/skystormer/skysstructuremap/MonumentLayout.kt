package com.skystormer.skysstructuremap

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * An ocean monument's own layout: the prismarine every monument has in the same place (its floor,
 * walls, roofs, pillars and core), learned from monuments the game built (`monument_layout.txt`).
 * The game builds a monument in code, facing any of four ways, with some rooms that differ; those
 * cells are left out. Prismarine and sea lanterns are what players build by the sea with, but only
 * a monument lays thousands of them out like this.
 */
object MonumentLayout {

    /** The layout's cells as offsets in the box (x, y, z packed by [pack]), and the block each must be. */
    private class Layout(val cells: IntArray, val blocks: Array<Block>)

    private val layout: Layout by lazy {
        val stream = MonumentLayout::class.java.getResourceAsStream("/assets/skysstructuremap/monument_layout.txt")
            ?: return@lazy Layout(IntArray(0), emptyArray()).also { Log.warn("The ocean monument layout is missing from the jar") }
        val cells = ArrayList<Int>()
        val blocks = ArrayList<Block>()
        var y = -1
        var z = 0
        for (line in stream.bufferedReader().readLines()) {
            if (line.startsWith("#")) continue
            if (line.isEmpty()) { y++; z = 0; continue }
            for ((x, c) in line.withIndex()) {
                val block = when (c) {
                    'p' -> Blocks.PRISMARINE
                    'b' -> Blocks.PRISMARINE_BRICKS
                    'd' -> Blocks.DARK_PRISMARINE
                    'l' -> Blocks.SEA_LANTERN
                    else -> null
                } ?: continue
                cells.add(pack(x, y, z))
                blocks.add(block)
            }
            z++
        }
        Layout(cells.toIntArray(), blocks.toTypedArray())
    }

    private fun pack(x: Int, y: Int, z: Int) = (x shl 16) or (y shl 8) or z

    /**
     * Whether a monument stands in [box] (a monument's exact box): at least [MIN_MATCH] of the
     * layout's cells in chunks that are there hold its block, whichever way it faces. Monuments
     * the game built match 95% to all of it, and one half dismantled for a guardian farm 41%;
     * players' builds of prismarine and sea lanterns by the sea, 1% at most. False too while under
     * half of the layout's chunks are there to look at: it is asked again as more arrive.
     */
    fun matches(box: Box, level: BlockSource): Boolean {
        val layout = layout
        if (layout.cells.isEmpty()) return true
        val last = Recognise.MONUMENT_SIZE - 1
        val cursor = BlockPos.MutableBlockPos()
        // The eight ways a square can be turned or mirrored: swap x and z or not, then flip either.
        for (way in 0 until 8) {
            var known = 0
            var matched = 0
            for (i in layout.cells.indices) {
                val cell = layout.cells[i]
                val lx = cell shr 16
                val ly = (cell shr 8) and 255
                val lz = cell and 255
                var dx = if (way and 1 != 0) lz else lx
                var dz = if (way and 1 != 0) lx else lz
                if (way and 2 != 0) dx = last - dx
                if (way and 4 != 0) dz = last - dz
                val x = box.minX + dx
                val z = box.minZ + dz
                if (!level.hasChunk(x shr 4, z shr 4)) continue
                known++
                if (level.getBlockState(cursor.set(x, box.minY + ly, z)).block == layout.blocks[i]) matched++
            }
            if (known * 2 >= layout.cells.size && matched >= MIN_MATCH * known) return true
        }
        return false
    }

    /** How much of the layout a monument must still have. */
    private const val MIN_MATCH = 0.3
}
