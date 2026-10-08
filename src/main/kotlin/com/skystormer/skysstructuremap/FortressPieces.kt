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

    private const val CROSSROADS = "nebcr"

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

        /**
         * The lowest y above the piece's floor (a bridge's deck, a room's floor): the first run of
         * rows that are nearly all brick over the piece's footprint, and past it. 0 when there is none.
         */
        val wallsFrom: Int by lazy {
            var footprint = 0
            for (x in 0 until sx) for (z in 0 until sz) if ((0 until sy).any { at(x, it, z) != 0 }) footprint++
            fun solid(y: Int) = (0 until sx).sumOf { x -> (0 until sz).count { z -> at(x, y, z) in 1..3 } } >= 0.8 * footprint
            var y = 0
            while (y < sy && !solid(y)) y++
            while (y < sy && solid(y)) y++
            if (y < sy) y else 0
        }
    }

    /** A layout placed facing one way: its box's size in the world, and its cells as world offsets. */
    private class Placed(val layout: Layout, val facing: Int, val wx: Int, val wy: Int, val wz: Int, val offsets: IntArray, val categories: IntArray) {
        val total: Float = categories.fold(0f) { sum, c -> sum + weight(c) }
        /** The first nether brick cell, which every placement is tried from. */
        val anchor: Int = categories.indexOfFirst { it == 1 }

        /** Its walls and railings above the floor (cells, as indices), and the air they enclose ([worn]). */
        val walls: IntArray
        val walkway: IntArray

        init {
            val from = layout.wallsFrom
            walls = offsets.indices.filter { uy(offsets[it]) >= from && categories[it] in 1..3 }.toIntArray()
            walkway = offsets.indices.filter { uy(offsets[it]) >= from && categories[it] == AIR }.toIntArray()
        }

        /** A few of its wall cells spread along it, to try a worn placement from (any may be dug out). */
        val wallAnchors: IntArray = if (walls.isEmpty()) IntArray(0)
        else IntArray(minOf(WORN_ANCHORS, walls.size)) { walls[it * walls.size / minOf(WORN_ANCHORS, walls.size)] }
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
     * How pieces join, learned from the game's own fortresses (`fortress_joins.txt`): piece a, piece
     * b, and b's box corner less a's. Facings are left out: a crossroads looks the same every way.
     */
    private val joins: Set<String> by lazy {
        val stream = FortressPieces::class.java.getResourceAsStream("/assets/skysstructuremap/fortress_joins.txt")
            ?: return@lazy emptySet<String>().also { Log.warn("The fortress piece joins are missing from the jar") }
        stream.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toHashSet()
    }

    /** The two corridor turns are mirror images of each other: one in the joins. */
    private fun joinId(id: String) = if (id == "nescrt") "nesclt" else id

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
        val fit get() = 1 - misses / placed.total

        fun contains(o: Candidate) = o.x >= x && o.y >= y && o.z >= z &&
            o.x + o.placed.wx <= x + placed.wx && o.y + o.placed.wy <= y + placed.wy && o.z + o.placed.wz <= z + placed.wz
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
        val placed = ArrayList<Candidate>()
        // The highest taken cell of each column: bricks below one are its supports, not loose.
        val top = IntArray(area.sx * area.sz) { -1 }
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
            for (x in c.x until c.x + c.placed.wx) for (z in c.z until c.z + c.placed.wz) {
                val column = x * area.sz + z
                top[column] = maxOf(top[column], c.y + c.placed.wy - 1)
            }
            placed.add(c)
            found.add(Piece(c.placed.layout.id, Box(
                area.x0 + c.x, area.y0 + c.y, area.z0 + c.z,
                area.x0 + c.x + c.placed.wx - 1, area.y0 + c.y + c.placed.wy - 1, area.z0 + c.z + c.placed.wz - 1,
            )))
        }
        fun endFillers() {
            for (c in endFillers) {
                if (!free(c)) continue
                val (bx, by, bz) = behind(c)
                if (bx !in 0 until area.sx || bz !in 0 until area.sz || by + 2 >= area.sy) continue
                // A piece right behind its face, open into it: the passage over that deck or floor runs on.
                if (!taken.get(area.index(bx, by, bz)) || area[bx, by + 2, bz] != AIR) continue
                if (brickShare(area, c) > END_FILLER_BRICKS) continue
                take(c)
            }
        }
        val containers = candidates.filter { it.placed.layout.id in CONTAINERS.values && it.fit >= CONTAINER_FIT }
        for (c in candidates.sortedWith(compareBy<Candidate> { Math.round(it.misses) }.thenByDescending { it.evidence })) {
            if (!free(c)) continue
            // A smaller piece inside a bigger one that fits nearly as well is that bigger piece, worn.
            val big = CONTAINERS[c.placed.layout.id]
            val bigger = if (big == null) null
            else containers.filter { it.placed.layout.id == big && it.contains(c) && free(it) }.maxByOrNull { it.fit }
            take(bigger ?: c)
        }
        endFillers()
        if (placed.isNotEmpty()) worn(area, placed, taken, top, ::free, ::take, ::endFillers)
        return found
    }

    /**
     * Pieces players have dug into: a bridge whose supports or deck were mined away, a room with
     * holes in it. Once intact pieces show a fortress is there, a piece that fits less well is
     * taken where only a piece can explain what is left: most of its walls and railings above the
     * floor still stand ([WORN_WALLS]) around its walkway's air ([WORN_WALKWAY]), at least half of
     * those walls are bricks no piece found so far accounts for, and it joins a found piece just
     * as the game joins them ([joins]). Tried the most standing wall first, so a crossroads with a
     * mined arm is not taken for a bridge along its other arm; after each, end fillers are looked
     * for again. On a fortress with nothing dug out, it finds nothing more.
     */
    private fun worn(
        area: Area, placed: List<Candidate>, taken: BitSet, top: IntArray,
        free: (Candidate) -> Boolean, take: (Candidate) -> Unit, endFillers: () -> Unit,
    ) {
        fun loose(x: Int, y: Int, z: Int) = area[x, y, z] in 1..3 && y > top[x * area.sz + z]
        // The loose bricks, fences and stairs, by category: where a worn piece's walls can be.
        val looseAt = Array(4) { ArrayList<Int>() }
        for (x in 0 until area.sx) for (y in 0 until area.sy) for (z in 0 until area.sz) {
            if (loose(x, y, z)) looseAt[area[x, y, z]].add(pack(x, y, z))
        }
        val tried = HashSet<Long>()
        val worn = ArrayList<Pair<Candidate, Int>>()
        for ((index, p) in placements.withIndex()) {
            if (p.walls.isEmpty() || p.walkway.isEmpty() || p.layout.id == END_FILLER) continue
            val allowedWalls = ((1 - WORN_WALLS) * p.walls.size + 1e-9).toInt()
            val allowedAir = ((1 - WORN_WALKWAY) * p.walkway.size + 1e-9).toInt()
            for (a in p.wallAnchors) {
                val ax = ux(p.offsets[a]); val ay = uy(p.offsets[a]); val az = uz(p.offsets[a])
                for (b in looseAt[p.categories[a]]) {
                    val ox = ux(b) - ax; val oy = uy(b) - ay; val oz = uz(b) - az
                    if (ox < 0 || oy < 0 || oz < 0 || ox + p.wx > area.sx || oy + p.wy > area.sy || oz + p.wz > area.sz) continue
                    if (!tried.add((index.toLong() shl 48) or (ox.toLong() shl 32) or (oy.toLong() shl 16) or oz.toLong())) continue
                    var wallMisses = 0
                    for (i in p.walls) {
                        val o = p.offsets[i]
                        if (area[ox + ux(o), oy + uy(o), oz + uz(o)] != p.categories[i] && ++wallMisses > allowedWalls) break
                    }
                    if (wallMisses > allowedWalls) continue
                    var airMisses = 0
                    for (i in p.walkway) {
                        val o = p.offsets[i]
                        if (area[ox + ux(o), oy + uy(o), oz + uz(o)] != AIR && ++airMisses > allowedAir) break
                    }
                    if (airMisses > allowedAir) continue
                    val c = Candidate(p, ox, oy, oz, 0f)
                    if (free(c)) worn.add(c to p.walls.size - wallMisses)
                }
            }
        }
        worn.sortByDescending { it.second }
        do {
            var added = false
            for ((c, _) in worn) {
                if (!free(c)) continue
                val standing = c.placed.walls.count { i ->
                    val o = c.placed.offsets[i]
                    loose(c.x + ux(o), c.y + uy(o), c.z + uz(o))
                }
                if (standing < 0.5 * c.placed.walls.size) continue
                val id = joinId(c.placed.layout.id)
                if (placed.none { joinId(it.placed.layout.id) + " " + id + " " + (c.x - it.x) + " " + (c.y - it.y) + " " + (c.z - it.z) in joins }) continue
                take(c)
                endFillers()
                added = true
                break
            }
        } while (added)
    }

    private const val END_FILLER = "nebef"

    /**
     * The most of an end filler's box that can be brick. A real one is rubble, about a fifth to a
     * third brick (more only where two fortresses overlap). One "found" in the solid brick the
     * game fills down under rooms and corridors was 41% or more in nine of ten.
     */
    private const val END_FILLER_BRICKS = 0.36

    private fun brickShare(area: Area, c: Candidate): Double {
        var bricks = 0
        var known = 0
        for (x in c.x until c.x + c.placed.wx) for (y in c.y until c.y + c.placed.wy) for (z in c.z until c.z + c.placed.wz) {
            val v = area[x, y, z]
            if (v == UNKNOWN.toInt()) continue
            known++
            if (v in 1..3) bricks++
        }
        return if (known == 0) 1.0 else bricks.toDouble() / known
    }

    /**
     * Smaller pieces that also fit inside a bigger one: a T balcony's middle is a corridor
     * crossing, a crossroads' arm is a bridge. When the bigger piece is there but a little worn,
     * the smaller fits exactly and was taken first, losing the bigger. Real T balconies nearly all
     * fit 94% or more, real crossroads 88% or more, while either laid around a real corridor or
     * bridge fitted 86% at best. So the bigger one is taken instead only from [CONTAINER_FIT].
     */
    private val CONTAINERS = mapOf("nescsc" to "nectb", "nesc" to "nectb", "nebs" to CROSSROADS)
    private const val CONTAINER_FIT = 0.92

    /** How much of a worn piece's walls above its floor must stand, and of its walkway be open ([worn]). */
    private const val WORN_WALLS = 0.6
    private const val WORN_WALKWAY = 0.8

    /** How many of a piece's wall cells a worn placement is tried from. */
    private const val WORN_ANCHORS = 12

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

    /**
     * The most crossroads one fortress has: its start is one, and the game places at most four
     * more.
     */
    const val MOST_CROSSROADS = 5

    /** How many crossroads, under the name pieces were saved with before they were told apart too. */
    fun crossroads(pieces: Collection<Piece>): Int = pieces.count { it.kind == CROSSROADS || it.kind == "crossroads" }

    /** Whether [a]'s pieces and [b]'s together hold more crossroads than one fortress has; a piece in both counts once. */
    fun tooManyCrossroads(a: List<Piece>, b: List<Piece>): Boolean =
        crossroads(a) + crossroads(b.filterNot { p -> a.any { it.box.overlaps(p.box) } }) > MOST_CROSSROADS

    /**
     * The fortresses among one group's pieces. Fortresses close together or on top of each other
     * are seen as one group of bricks, and nothing in their bricks tells them apart; only more
     * crossroads than one fortress has ([MOST_CROSSROADS]) proves there are two or more, so
     * without that the pieces stay one fortress: wrongly joined is better than wrongly split.
     *
     * When proved: pieces that touch are joined first (a fortress's pieces join face to face, so
     * every one touches another of its own), then each part, smallest first, joins the nearest it
     * can, never making one with more crossroads than a fortress has. What is left apart holds a
     * crossroads each. Where two touch, a few pieces can end up on the wrong one.
     */
    fun split(pieces: List<Piece>): List<List<Piece>> {
        if (crossroads(pieces) <= MOST_CROSSROADS) return listOf(pieces)
        val parts = pieces.map { mutableListOf(it) }.toMutableList()
        fun fits(a: List<Piece>, b: List<Piece>) = crossroads(a) + crossroads(b) <= MOST_CROSSROADS
        fun join(a: MutableList<Piece>, b: MutableList<Piece>) {
            a.addAll(b)
            parts.remove(b)
        }
        for (i in pieces.indices) for (j in i + 1 until pieces.size) {
            if (pieces[i].box.gap(pieces[j].box) > 1) continue
            val a = parts.first { pieces[i] in it }
            val b = parts.first { pieces[j] in it }
            if (a !== b && fits(a, b)) join(a, b)
        }
        while (true) {
            val (small, near) = parts.sortedBy { it.size }.firstNotNullOfOrNull { part ->
                parts.filter { it !== part && fits(it, part) }
                    .minByOrNull { other -> part.minOf { p -> other.minOf { p.box.gap(it.box) } } }
                    ?.let { part to it }
            } ?: break
            join(near, small)
        }
        return parts
    }

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
    const val LOWEST = 48
    private const val ALWAYS_LOWEST_FROM = 70
}
