package com.skystormer.skysstructuremap

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import java.util.BitSet
import java.util.IdentityHashMap

/**
 * Every piece of a nether fortress, each with its own box, worked out from its blocks: the boxes
 * the game spawns its blazes, wither skeletons and the rest in (only inside a piece, not anywhere
 * in the fortress's overall box), as MiniHUD shows them.
 *
 * The game builds fortress pieces in code, not from designs, so each piece's layout was learned
 * from every fully built example of it in three worlds (`assets/skysstructuremap/fortress_pieces.txt`):
 * the block most examples agree on, cell by cell, in the piece's own frame. A piece is found by laying
 * its layout, turned each of the four ways the game places pieces (mirrored as well as turned: the
 * `StructurePiece` frames), over the fortress's blocks. Placements are then taken fewest blocks out
 * of place first (a real piece nearly always fits exactly, while a big piece laid over a castle's
 * floor fits most of the way), then most blocks, never two overlapping, as the game never overlaps
 * pieces. Checked against the pieces the game saved: every piece of three fortresses (453) found
 * with its type and exact box, learning from two worlds and labelling the third.
 *
 * Two pieces need more. A left and a right corridor turn are the same shape mirrored, so which the
 * game called it cannot be told (the box is the same). The bridge end filler is random rubble
 * after its first row, a face of brick against what it ends; it is only taken where that face
 * fits exactly, a found piece stands right behind it, and that piece is open into it.
 *
 * The fortress's own box: sideways, exactly as far as its nether bricks go (the pillars under the
 * bridges stand inside it); upwards, the highest brick (a bridge's railing) plus the four blocks of
 * air above it that the bridge's box keeps; downwards, y 48 (see [LOWEST]), which nearly every fortress
 * starts at.
 */
object FortressPieces {

    const val CROSSROADS = "nebcr"

    /** How much of a piece's layout must match, weighted (air counts half: it is everywhere in the nether). */
    private const val MIN_FIT = 0.85

    /** The most blocks a fortress is labelled over at once (400 × 128 × 400). */
    private const val MAX_CELLS = 400L * 128 * 400

    private const val UNKNOWN: Byte = -1
    private const val AIR = 4

    private val CATEGORY = IdentityHashMap<Block, Int>().apply {
        put(Blocks.NETHER_BRICKS, 1); put(Blocks.NETHER_BRICK_FENCE, 2); put(Blocks.NETHER_BRICK_STAIRS, 3)
        put(Blocks.AIR, 4); put(Blocks.CAVE_AIR, 4); put(Blocks.LAVA, 5); put(Blocks.SOUL_SAND, 6)
        put(Blocks.NETHER_WART, 7); put(Blocks.CHEST, 8); put(Blocks.SPAWNER, 9)
    }

    private fun weight(category: Int): Float = if (category == AIR || category == 5) 0.5f else 1f

    /** A piece's layout in its own frame: size, and a category per cell (0 = no opinion). */
    private class Layout(val id: String, val sx: Int, val sy: Int, val sz: Int, val cells: ByteArray) {
        fun at(x: Int, y: Int, z: Int): Int = cells[(x * sy + y) * sz + z].toInt()
    }

    /** A layout placed facing one way: its box's size in the world, and its cells as world offsets. */
    private class Placed(val layout: Layout, val facing: Int, val wx: Int, val wy: Int, val wz: Int, val offsets: IntArray, val categories: IntArray) {
        val total: Float = categories.fold(0f) { sum, c -> sum + weight(c) }
        /** The first nether brick cell, which every placement is tried from. */
        val anchor: Int = categories.indexOfFirst { it == 1 }
    }

    private val layouts: List<Layout> by lazy {
        val stream = FortressPieces::class.java.getResourceAsStream("/assets/skysstructuremap/fortress_pieces.txt")
            ?: return@lazy emptyList<Layout>().also { Log.warn("The fortress piece layouts are missing from the jar") }
        stream.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val p = line.split(' ')
            Layout(p[0], p[1].toInt(), p[2].toInt(), p[3].toInt(), ByteArray(p[4].length) { (p[4][it] - '0').toByte() })
        }
    }

    /**
     * Each layout the four ways the game faces a piece: south as it is, north mirrored along z,
     * west and east turned (x and z swapped), west also mirrored (`StructurePiece.getWorldX/Z`).
     */
    private val placements: List<Placed> by lazy {
        layouts.flatMap { l ->
            (0..3).map { facing ->
                val turned = facing == WEST || facing == EAST
                val wx = if (turned) l.sz else l.sx
                val wz = if (turned) l.sx else l.sz
                val offsets = ArrayList<Int>()
                val categories = ArrayList<Int>()
                for (x in 0 until l.sx) for (y in 0 until l.sy) for (z in 0 until l.sz) {
                    val c = l.at(x, y, z)
                    if (c == 0) continue
                    val (ox, oz) = when (facing) {
                        SOUTH -> x to z
                        NORTH -> x to l.sz - 1 - z
                        WEST -> l.sz - 1 - z to x
                        else -> z to x
                    }
                    offsets.add(pack(ox, y, oz))
                    categories.add(c)
                }
                Placed(l, facing, wx, l.sy, wz, offsets.toIntArray(), categories.toIntArray())
            }
        }
    }

    private const val SOUTH = 0
    private const val WEST = 1
    private const val NORTH = 2
    private const val EAST = 3

    private fun pack(x: Int, y: Int, z: Int) = (x shl 20) or (y shl 10) or z
    private fun ux(p: Int) = p ushr 20
    private fun uy(p: Int) = (p ushr 10) and 1023
    private fun uz(p: Int) = p and 1023

    /** The blocks of a fortress's area, as categories: read once, then looked at many times. */
    private class Area(val x0: Int, val y0: Int, val z0: Int, val sx: Int, val sy: Int, val sz: Int) {
        val cells = ByteArray(sx * sy * sz)
        fun index(x: Int, y: Int, z: Int) = (x * sy + y) * sz + z
        operator fun get(x: Int, y: Int, z: Int): Int = cells[index(x, y, z)].toInt()
    }

    private fun read(level: BlockSource, around: Box): Area? {
        val sx = around.maxX - around.minX + 5
        val sy = around.maxY - around.minY + 5
        val sz = around.maxZ - around.minZ + 5
        if (sx.toLong() * sy * sz > MAX_CELLS) {
            Log.warn("Fortress area {} is too big to label at once", around)
            return null
        }
        val area = Area(around.minX - 2, around.minY - 2, around.minZ - 2, sx, sy, sz)
        val cursor = BlockPos.MutableBlockPos()
        for (x in 0 until area.sx) {
            for (z in 0 until area.sz) {
                val wx = area.x0 + x
                val wz = area.z0 + z
                val known = level.hasChunk(wx shr 4, wz shr 4)
                for (y in 0 until area.sy) {
                    area.cells[area.index(x, y, z)] = if (!known) UNKNOWN
                    else (CATEGORY[level.getBlockState(cursor.set(wx, area.y0 + y, wz)).block] ?: 0).toByte()
                }
            }
        }
        return area
    }

    private class Candidate(val placed: Placed, val x: Int, val y: Int, val z: Int, val misses: Float) {
        val evidence get() = placed.total - misses
    }

    /**
     * Every piece of the fortress around [bricks] that can be told from what [level] holds, as its
     * box, labelled with the game's piece id. Slow (a large fortress takes a second or so): off the
     * main thread, or on a scan's own.
     */
    fun label(level: BlockSource, bricks: Box): List<Piece> {
        val area = read(level, bricks) ?: return emptyList()
        val candidates = ArrayList<Candidate>()
        val endFillers = ArrayList<Candidate>()
        val bricksAt = ArrayList<Int>()
        for (x in 0 until area.sx) for (y in 0 until area.sy) for (z in 0 until area.sz) {
            if (area[x, y, z] == 1) bricksAt.add(pack(x, y, z))
        }
        for (placed in placements) {
            if (placed.anchor < 0) continue
            val endFiller = placed.layout.id == END_FILLER
            val allowed = if (endFiller) 0f else placed.total * (1 - MIN_FIT).toFloat()
            val a = placed.offsets[placed.anchor]
            for (b in bricksAt) {
                val ox = ux(b) - ux(a)
                val oy = uy(b) - uy(a)
                val oz = uz(b) - uz(a)
                if (ox < 0 || oy < 0 || oz < 0 || ox + placed.wx > area.sx || oy + placed.wy > area.sy || oz + placed.wz > area.sz) continue
                var misses = 0f
                var i = 0
                while (i < placed.offsets.size) {
                    val p = placed.offsets[i]
                    val c = placed.categories[i]
                    if (area[ox + ux(p), oy + uy(p), oz + uz(p)] != c) {
                        misses += weight(c)
                        if (misses > allowed) break
                    }
                    i++
                }
                if (misses > allowed) continue
                (if (endFiller) endFillers else candidates).add(Candidate(placed, ox, oy, oz, misses))
            }
        }
        val taken = BitSet(area.cells.size)
        val found = ArrayList<Piece>()
        fun free(c: Candidate): Boolean {
            for (x in c.x until c.x + c.placed.wx) for (y in c.y until c.y + c.placed.wy) {
                val from = area.index(x, y, c.z)
                val next = taken.nextSetBit(from)
                if (next in from until from + c.placed.wz) return false
            }
            return true
        }
        fun take(c: Candidate) {
            for (x in c.x until c.x + c.placed.wx) for (y in c.y until c.y + c.placed.wy) {
                val from = area.index(x, y, c.z)
                taken.set(from, from + c.placed.wz)
            }
            found.add(Piece(c.placed.layout.id, Box(
                area.x0 + c.x, area.y0 + c.y, area.z0 + c.z,
                area.x0 + c.x + c.placed.wx - 1, area.y0 + c.y + c.placed.wy - 1, area.z0 + c.z + c.placed.wz - 1,
            )))
        }
        for (c in candidates.sortedWith(compareBy<Candidate> { Math.round(it.misses) }.thenByDescending { it.evidence })) {
            if (free(c)) take(c)
        }
        for (c in endFillers) {
            if (!free(c)) continue
            val (bx, by, bz) = behind(c)
            if (bx !in 0 until area.sx || bz !in 0 until area.sz || by + 2 >= area.sy) continue
            // A piece right behind its face, open into it: the passage over that deck or floor runs on.
            if (!taken.get(area.index(bx, by, bz)) || area[bx, by + 2, bz] != AIR) continue
            take(c)
        }
        return found
    }

    private const val END_FILLER = "nebef"

    /** The block just behind the middle of an end filler's first row (its local 2, 3, -1). */
    private fun behind(c: Candidate): Triple<Int, Int, Int> {
        val x1 = c.x + c.placed.wx - 1
        val z1 = c.z + c.placed.wz - 1
        return when (c.placed.facing) {
            SOUTH -> Triple(c.x + 2, c.y + 3, c.z - 1)
            NORTH -> Triple(c.x + 2, c.y + 3, z1 + 1)
            WEST -> Triple(x1 + 1, c.y + 3, c.z + 2)
            else -> Triple(c.x - 1, c.y + 3, c.z + 2)
        }
    }

    /**
     * Whether pieces found are enough to call nether bricks a fortress: a crossroads (every fortress
     * starts from one), or several pieces (a castle seen without its bridges). A player's nether
     * brick build does not lay out fortress pieces.
     */
    fun isFortress(pieces: List<Piece>): Boolean = pieces.any { it.kind == CROSSROADS } || pieces.size >= 4

    /** The fortress's whole box from its seen blocks and pieces (see the class notes). */
    fun outerBox(bricks: Box, pieces: List<Piece>): Box {
        val top = maxOf(bricks.maxY + 4, pieces.maxOfOrNull { it.box.maxY } ?: Int.MIN_VALUE)
        val bottom = if (top >= ALWAYS_LOWEST_FROM) LOWEST
        else pieces.minOfOrNull { it.box.minY } ?: bricks.minY
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
