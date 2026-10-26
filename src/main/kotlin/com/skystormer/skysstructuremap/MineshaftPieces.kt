package com.skystormer.skysstructuremap

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FallingBlock

/**
 * A mineshaft's corridors are held up by supports the game lays the same way every time
 * (`MineshaftPieces.MineShaftCorridor.placeSupport`): two fence posts two blocks high, two blocks
 * apart across the corridor, with planks on top of each (and nearly always across the gap), and the
 * corridor open between them. Where a corridor crosses a cave with no ground near under it, the game
 * hangs it from the roof instead (`fillPillarDownOrChainUp`): a fence on the corridor's plank floor
 * with a chain up from it, two blocks apart across the corridor the same way. Fences are what farms
 * and houses are fenced with; a run of these frames is not.
 */
object MineshaftPieces {

    /** Supports needed before a group of fences is a mineshaft. */
    const val SUPPORTS = 3

    /**
     * How far from its start room the game lays a mineshaft's pieces: a piece starts at most 80
     * blocks from it (`MineshaftPieces.generateAndAddPiece`), and a corridor runs up to 20 on.
     */
    const val REACH = 104

    /**
     * How far down and up from its start room (the 8 x 8 found of it, four high) it reaches: stairs
     * lead down and up from corridors, its pieces from 31 under the room's floor to 26 over it in
     * testing. No further, so one laid above another is not taken for it.
     */
    private const val REACH_DOWN = 32
    private const val REACH_UP = 24

    private val FENCES: Set<Block> = setOf(Blocks.OAK_FENCE, Blocks.DARK_OAK_FENCE)
    private val PLANKS: Set<Block> = setOf(Blocks.OAK_PLANKS, Blocks.DARK_OAK_PLANKS)

    /**
     * Whether [detection]'s fences hold up at least [SUPPORTS] corridor supports, one with a rail
     * or cobweb in its corridor: every one of 300 mineshafts sampled on a server had one, and a
     * player's dark oak fences under planks, 82 of them, none. Those supports are kept in
     * [Detection.furnished], for the part of a long chain saved as one mineshaft to need one too.
     */
    fun proved(detection: Detection, level: BlockSource): Boolean =
        supports(detection, level, SUPPORTS) >= SUPPORTS && furnish(detection, level)

    /** Finds the supports with a rail or cobweb in their corridor, within [ALONG] of them; whether there are any. */
    private fun furnish(detection: Detection, level: BlockSource): Boolean {
        if (detection.furnishChecked) return !detection.furnished.isEmpty
        val cursor = BlockPos.MutableBlockPos()
        detection.furnished.clear()
        val iterator = detection.positions.iterator()
        while (iterator.hasNext()) {
            val key = iterator.nextLong()
            val x = BlockPos.getX(key)
            val y = BlockPos.getY(key)
            val z = BlockPos.getZ(key)
            if (!post(level, cursor, x, y, z)) continue
            // Posts 2 east: the corridor runs north and south between them; 2 south, east and west.
            supports@ for ((dx, dz) in listOf(1 to 0, 0 to 1)) {
                if (!post(level, cursor, x + 2 * dx, y, z + 2 * dz)) continue
                for (along in -ALONG..ALONG) for (across in 0..2) for (dy in 0..2) {
                    val block = level.getBlockState(cursor.set(x + dx * across + dz * along, y + dy, z + dz * across + dx * along)).block
                    if (block in FURNISHING) {
                        detection.furnished.add(key)
                        break@supports
                    }
                }
            }
        }
        detection.furnishChecked = true
        return !detection.furnished.isEmpty
    }

    /** Whether one of [detection]'s furnished supports stands in [box]. */
    fun furnishedIn(detection: Detection, box: Box): Boolean = (0 until detection.furnished.size).any { i ->
        val key = detection.furnished.getLong(i)
        box.contains(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key))
    }

    private val FURNISHING: Set<Block> = setOf(Blocks.RAIL, Blocks.COBWEB)
    private const val ALONG = 8

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

    /**
     * A post at [x], [y], [z]: two fences on a block that is not one, under planks; or, hung from
     * the roof over a cave, a fence on planks with a chain on it.
     */
    private fun post(level: BlockSource, cursor: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): Boolean {
        if (!isFence(level, cursor.set(x, y, z)) || isFence(level, cursor.set(x, y - 1, z))) return false
        if (isFence(level, cursor.set(x, y + 1, z))) return isPlanks(level, cursor.set(x, y + 2, z))
        return isChain(level, cursor.set(x, y + 1, z)) && isPlanks(level, cursor.set(x, y - 1, z))
    }

    /** The other post 2 along ([dx], [dz]), and the corridor open between the two. */
    private fun support(level: BlockSource, cursor: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int, dx: Int, dz: Int): Boolean {
        if (!post(level, cursor, x + 2 * dx, y, z + 2 * dz)) return false
        // Rails, cobwebs and torches stand in a corridor; nothing that fills it does.
        return !blocks(level, cursor.set(x + dx, y, z + dz)) && !blocks(level, cursor.set(x + dx, y + 1, z + dz))
    }

    /**
     * Everywhere a mineshaft reckoned from [centre] can reach: as far as the game lays pieces from
     * its start room, or, for one whose room was never seen, the stretch it was cut to ([stretch]).
     */
    fun reachOf(centre: Box): Box =
        if (isStretch(centre)) centre else centre.grow(REACH, down = REACH_DOWN, up = REACH_UP)

    /**
     * Where a mineshaft whose start room is not in sight is reckoned to be, from the fences [seen]:
     * a stretch as wide as one can be, from their north-west corner, or, given [detection], the
     * tile of a fixed grid of such stretches holding its fence nearest that corner. Fences running
     * in an L leave the corner itself empty, and a stretch holding none of them would split off
     * every one, again and again; a stretch from the nearest fence would leave a few behind, the
     * next one a few blocks over, the same mineshaft twice. Not every start room can be found (one
     * opens into a cave with no walls; at another the game's own record had no room at all), and
     * fences chained across many mineshafts would otherwise make one huge one.
     */
    fun stretch(seen: Box, detection: Detection? = null): Box {
        var x = seen.minX
        var z = seen.minZ
        if (detection != null) {
            var nearest = Long.MAX_VALUE
            detection.forEachPosition { fx, _, fz ->
                val dx = (fx - seen.minX).toLong()
                val dz = (fz - seen.minZ).toLong()
                if (dx * dx + dz * dz < nearest) {
                    nearest = dx * dx + dz * dz
                    x = Math.floorDiv(fx, TILE) * TILE
                    z = Math.floorDiv(fz, TILE) * TILE
                }
            }
        }
        return Box(x, seen.minY, z, x + TILE - 1, seen.maxY, z + TILE - 1)
    }

    /**
     * [box] no higher than a mineshaft's box can be ([MOST_HIGH], as high as its room's reach; the
     * highest in testing was 45): the rows of it that hold the most of [detection]'s
     * fences. Without its room in sight, fences of mineshafts above and below chain into one group,
     * and one was saved 105 high.
     */
    fun noHigherThanOne(detection: Detection, box: Box): Box {
        if (box.sizeY <= MOST_HIGH) return box
        val rows = IntArray(box.sizeY)
        detection.forEachPosition { x, y, z ->
            if (box.contains(x, y, z)) rows[y - box.minY]++
        }
        var inWindow = (0 until MOST_HIGH).sumOf { rows[it] }
        var most = inWindow
        var from = 0
        for (start in 1..rows.size - MOST_HIGH) {
            inWindow += rows[start + MOST_HIGH - 1] - rows[start - 1]
            if (inWindow > most) {
                most = inWindow
                from = start
            }
        }
        return box.copy(minY = box.minY + from, maxY = box.minY + from + MOST_HIGH - 1)
    }

    /** [REACH_DOWN], the room's 5 and [REACH_UP]. */
    const val MOST_HIGH = 61

    /** The widest a mineshaft with no start room in sight is taken to be: the widest in testing was 198. */
    const val MOST_WIDE = 200

    /** Whether [box] is no bigger than one mineshaft with no start room in sight can be ([MOST_WIDE], [MOST_HIGH]). */
    fun fitsOne(box: Box): Boolean = maxOf(box.sizeX, box.sizeZ) <= MOST_WIDE && box.sizeY <= MOST_HIGH

    /** Whether [centre] is a stretch ([stretch]), not a start room. */
    fun isStretch(centre: Box): Boolean = centre.sizeX > REACH

    private const val TILE = 2 * REACH + 1

    /**
     * The start room of one of [detection]'s mineshafts, or null when none is in sight. Every
     * mineshaft has one, and only one (`MineshaftPieces.MineShaftRoom`): it starts 2 blocks into
     * its chunk on x and z and is at least 7 across, an open room with a wall just outside it, its
     * floor whatever ground was there. Its corridors leave from its sides, so it is looked for in
     * the chunks the fences are in and those beside them, at the fences' heights.
     */
    fun startRoom(detection: Detection, level: BlockSource): Box? = startRooms(detection, level, first = true).firstOrNull()

    /** Every start room among [detection]'s fences ([startRoom]), or only the first found when [first]. */
    fun startRooms(detection: Detection, level: BlockSource, first: Boolean = false): List<Box> {
        // Each chunk to try, with the lowest and highest fence near it.
        val heights = HashMap<Long, IntArray>()
        detection.forEachPosition { x, y, z ->
            val chunkX = x shr 4
            val chunkZ = z shr 4
            for (dx in -1..1) for (dz in -1..1) {
                val range = heights.getOrPut(ChunkPos.pack(chunkX + dx, chunkZ + dz)) { intArrayOf(y, y) }
                range[0] = minOf(range[0], y)
                range[1] = maxOf(range[1], y)
            }
        }
        val cursor = BlockPos.MutableBlockPos()
        val rooms = ArrayList<Box>()
        for ((chunk, range) in heights) {
            val x = ChunkPos.getX(chunk) * 16 + 2
            val z = ChunkPos.getZ(chunk) * 16 + 2
            if (!level.hasChunk(x shr 4, z shr 4)) continue
            for (y in range[0] - ROOM_BELOW..range[1] + ROOM_ABOVE) {
                if (!roomAt(level, cursor, x, y, z)) continue
                rooms.add(Box(x, y, z, x + ROOM - 1, y + ROOM_HIGH, z + ROOM - 1))
                if (first) return rooms
                // One room to a chunk: the game starts one mineshaft in it at most.
                break
            }
        }
        return rooms
    }

    /**
     * Whether a start room's floor is at [x], [y], [z]: cut as one ([isRoom], or its dome standing
     * where a cave took its floor or a wall's foot, [isDomed]), with a corridor leaving it
     * ([corridorLeaves]). Not private, for testing.
     */
    internal fun roomAt(level: BlockSource, cursor: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): Boolean =
        (isRoom(level, cursor, x, y, z) || isDomed(level, cursor, x, y, z)) && corridorLeaves(level, cursor, x, y, z)

    /**
     * Whether a corridor leaves the room at [x], [y], [z]: a support (two posts, [support]) with its
     * feet 1 to [LEAVE_HIGH] over the floor, no further than [LEAVE_PAST] past the walls of the
     * biggest room it could be. The game leaves a start room by corridors from its walls; of the
     * rooms found in testing, all but 15 in 2,202 had one so, and a flat-floored cave the room test
     * passed, a mineshaft's corridors crossing ten over its floor, had none.
     */
    private fun corridorLeaves(level: BlockSource, cursor: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): Boolean {
        for (a in x - LEAVE_PAST..x + MOST_ACROSS + LEAVE_PAST) for (c in z - LEAVE_PAST..z + MOST_ACROSS + LEAVE_PAST) {
            if (a in x until x + ROOM && c in z until z + ROOM) continue
            for (k in y + 1..y + LEAVE_HIGH) {
                if (!post(level, cursor, a, k, c)) continue
                if (support(level, cursor, a, k, c, 1, 0) || support(level, cursor, a, k, c, 0, 1)) return true
                if (post(level, cursor, a - 2, k, c) && support(level, cursor, a - 2, k, c, 1, 0)) return true
                if (post(level, cursor, a, k, c - 2) && support(level, cursor, a, k, c - 2, 0, 1)) return true
            }
        }
        return false
    }

    private const val LEAVE_HIGH = 5
    private const val LEAVE_PAST = 7

    /**
     * Start rooms worked out from [corridors] where none was seen: a cave opened over it, a player
     * dug it out, or it is filled. Each corridor of the game's leaves a room's wall or another
     * piece's; the corner 2 into a chunk (where every start room starts) that the most corridors no
     * room in [rooms] reaches ([joined]) end at, 1 or 2 short of its walls, with their feet 1 to 10
     * over its floor, is taken for one when [GUESS_LEAST] or more do, it is mostly open inside
     * ([GUESS_OPEN]), and no other room, of [rooms], [taken] (those saved near) or those guessed,
     * stands within [GUESS_APART] chunks of it: few real ones do, and a room guessed beside one would
     * split its mineshaft. Then it claims the corridors joined to it, and the next is looked for.
     * In testing, of 451 mineshafts whose room could not be seen and was not hidden by anti-xray,
     * about a third had theirs found so, most exactly, the rest a chunk or two off.
     */
    fun guessRooms(corridors: List<Piece>, rooms: List<Box>, taken: () -> List<Box>, level: BlockSource): List<Box> {
        if (corridors.size < GUESS_LEAST) return emptyList()
        val saved by lazy(taken)
        val claimed = BooleanArray(corridors.size)
        // A room claims what joins it, but no further than the game lays its pieces: joins run on
        // through crossings into the next mineshaft, and would claim the corridors of one whose
        // room is not in sight, leaving none to work its room out from.
        fun claim(room: Box) {
            val reach = reachOf(room)
            var frontier = listOf(Piece(ROOM_KIND, room))
            while (frontier.isNotEmpty()) {
                val next = ArrayList<Piece>()
                for (piece in frontier) for (i in corridors.indices) {
                    if (!claimed[i] && reach.overlaps(corridors[i].box) && joined(piece, corridors[i])) {
                        claimed[i] = true
                        next.add(corridors[i])
                    }
                }
                frontier = next
            }
        }
        rooms.forEach(::claim)
        val guessed = ArrayList<Box>()
        val cursor = BlockPos.MutableBlockPos()
        while (true) {
            val votes = HashMap<Long, MutableList<Int>>()
            for (i in corridors.indices) {
                if (claimed[i]) continue
                val box = corridors[i].box
                for (corner in cornersFor(box)) for (floor in box.minY - GUESS_BELOW until box.minY) {
                    votes.getOrPut(BlockPos.asLong(BlockPos.getX(corner), floor, BlockPos.getZ(corner))) { ArrayList() }.add(i)
                }
            }
            var best: Long? = null
            var most = 0
            var off = Int.MAX_VALUE
            for ((key, who) in votes) {
                if (who.size < GUESS_LEAST || who.size < most) continue
                val x = BlockPos.getX(key)
                val y = BlockPos.getY(key)
                val z = BlockPos.getZ(key)
                // The floor the game most often leaves its corridors from: 1 under their feet.
                val by = who.sumOf { Math.abs(corridors[it].box.minY - y - 1) }
                if (who.size == most && by >= off) continue
                if (!level.hasChunk(x shr 4, z shr 4)) continue
                if ((rooms.asSequence() + saved + guessed).any { Math.abs((it.minX shr 4) - (x shr 4)) <= GUESS_APART && Math.abs((it.minZ shr 4) - (z shr 4)) <= GUESS_APART }) continue
                if (!openInside(level, cursor, x, y, z)) continue
                best = key
                most = who.size
                off = by
            }
            val key = best ?: break
            val x = BlockPos.getX(key)
            val y = BlockPos.getY(key)
            val z = BlockPos.getZ(key)
            val room = Box(x, y, z, x + ROOM - 1, y + ROOM_HIGH, z + ROOM - 1)
            guessed.add(room)
            for (i in votes.getValue(key)) claimed[i] = true
            claim(room)
        }
        return guessed
    }

    /**
     * The corners (as x and z of a [BlockPos] key), 2 into a chunk, of every room 8 to 13 across
     * that [corridor] could leave: its end 1 to [GUESS_GAP] past a wall, the room as wide across it.
     */
    private fun cornersFor(corridor: Box): List<Long> {
        val out = ArrayList<Long>()
        val alongX = corridor.sizeX > corridor.sizeZ
        val low = if (alongX) corridor.minZ else corridor.minX
        val high = if (alongX) corridor.maxZ else corridor.maxX
        val across = (Math.floorDiv(low - MOST_ACROSS, 16) * 16 + 2..low step 16).filter { it + MOST_ACROSS - 1 >= high }
        val start = if (alongX) corridor.minX else corridor.minZ
        val end = if (alongX) corridor.maxX else corridor.maxZ
        val along = LinkedHashSet<Int>()
        for (k in 1..GUESS_GAP) {
            if (Math.floorMod(end + k, 16) == 2) along.add(end + k)
            for (wide in ROOM..MOST_ACROSS) if (Math.floorMod(start - k - wide + 1, 16) == 2) along.add(start - k - wide + 1)
        }
        for (a in along) for (c in across) out.add(if (alongX) BlockPos.asLong(a, 0, c) else BlockPos.asLong(c, 0, a))
        return out
    }

    /** Whether the 8 x 8 every start room is, 1 to 3 over the floor at [y], is [GUESS_OPEN] open. */
    private fun openInside(level: BlockSource, cursor: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): Boolean {
        var open = 0
        for (dx in 0 until ROOM) for (dz in 0 until ROOM) for (dy in 1..CUT_HIGH) if (isOpen(level, cursor.set(x + dx, y + dy, z + dz))) open++
        return open >= GUESS_OPEN * ROOM * ROOM * CUT_HIGH
    }

    private const val GUESS_LEAST = 4
    private const val GUESS_GAP = 2
    private const val GUESS_BELOW = 10
    private const val GUESS_OPEN = 0.8
    private const val GUESS_APART = 3

    /**
     * The corridors among [detection]'s fences, as the game lays them (`MineShaftCorridor`): a
     * support in each 5 blocks of one, 2 into it, so a run of supports 5 apart along one line is one
     * corridor, from 2 before its first support to 2 past its last, 3 across between its posts and
     * 3 high from their feet. Where a support was dug out or caved in, the run is two corridors,
     * which [joined] joins again across the gap.
     */
    fun corridors(detection: Detection, level: BlockSource): List<Piece> {
        val cursor = BlockPos.MutableBlockPos()
        // Supports by the line they stand on (which way it runs, and where across and up), each at its place along it.
        val lines = HashMap<Triple<Boolean, Int, Int>, MutableList<Int>>()
        detection.forEachPosition { x, y, z ->
            if (!post(level, cursor, x, y, z)) return@forEachPosition
            // A partner 2 east: the corridor runs north and south, along z; 2 south, along x.
            if (support(level, cursor, x, y, z, 1, 0)) lines.getOrPut(Triple(true, x, y)) { ArrayList() }.add(z)
            if (support(level, cursor, x, y, z, 0, 1)) lines.getOrPut(Triple(false, z, y)) { ArrayList() }.add(x)
        }
        val corridors = ArrayList<Piece>()
        for ((line, at) in lines) {
            val (alongZ, across, y) = line
            at.sort()
            var from = 0
            for (i in at.indices) {
                if (i + 1 < at.size && at[i + 1] - at[i] == SECTION) continue
                val start = at[from] - SUPPORT_IN
                val end = at[i] + SUPPORT_IN
                corridors.add(Piece(CORRIDOR_KIND, if (alongZ) Box(across, y, start, across + 2, y + 2, end) else Box(start, y, across, end, y + 2, across + 2)))
                from = i + 1
            }
        }
        return corridors
    }

    /**
     * Whether the game joins [a] and [b], each a corridor ([corridors]) or a start room: touching
     * face to face (its pieces never overlap, where another mineshaft's corridor crossing through
     * one does), or across the gap a crossing or stairs fills, lined up so one could; a corridor
     * leaves a start room from its walls, which stand up to [ROOM_EXTRA] past the part of it
     * [startRoom] finds. On the game's own mineshafts, with only their rooms and corridors to go by,
     * 97.4% of corridors went to their own room so (91.5% by the nearest room).
     */
    fun joined(a: Piece, b: Piece): Boolean {
        val gap = a.box.gap(b.box)
        if (a.kind == ROOM_KIND || b.kind == ROOM_KIND) return gap in 1..ROOM_EXTRA + 1
        if (gap == 1) return faceToFace(a.box, b.box)
        return gap in 2..CONNECTOR && Math.abs(a.box.minY - b.box.minY) <= CONNECTOR_RISE &&
            (minOf(a.box.maxX, b.box.maxX) - maxOf(a.box.minX, b.box.minX) >= -2 || minOf(a.box.maxZ, b.box.maxZ) - maxOf(a.box.minZ, b.box.minZ) >= -2)
    }

    /** Whether two boxes that touch do so across a face, not only at an edge or corner. */
    private fun faceToFace(a: Box, b: Box): Boolean =
        listOf(minOf(a.maxX, b.maxX) >= maxOf(a.minX, b.minX), minOf(a.maxY, b.maxY) >= maxOf(a.minY, b.minY), minOf(a.maxZ, b.maxZ) >= maxOf(a.minZ, b.minZ)).count { it } == 2

    /**
     * The mineshafts among one group's corridors and start rooms: with two or more rooms, what the
     * game joined to each ([JoinedPieces], [joined]); else one.
     */
    fun split(pieces: List<Piece>): List<List<Piece>> {
        val rooms = pieces.filter { it.kind == ROOM_KIND }
        if (rooms.size < 2) return listOf(pieces)
        val parts = JoinedPieces.byStarts(pieces, rooms.map(::listOf), ::joined).map { it.toMutableList() }
        // A piece joined on beyond the reach of the room it went to ([reachOf]) is no piece of that
        // room's mineshaft, and would be cut off its box and lost: it goes to the nearest room that
        // does reach it. One no room reaches is split off the group to be a mineshaft of its own.
        val reaches = parts.map { part -> reachOf(part.first { it.kind == ROOM_KIND }.box) }
        for (i in parts.indices) {
            val away = parts[i].filter { it.kind != ROOM_KIND && !reaches[i].overlaps(it.box) }
            for (piece in away) {
                val to = parts.indices.filter { reaches[it].overlaps(piece.box) }
                    .minByOrNull { j -> parts[j].first { it.kind == ROOM_KIND }.box.let { Math.abs(it.centreX - piece.box.centreX) + Math.abs(it.centreZ - piece.box.centreZ) } }
                    ?: continue
                parts[i].remove(piece)
                parts[to].add(piece)
            }
        }
        return parts
    }

    /**
     * The box of a mineshaft built of [pieces] (its start room and the corridors read off its
     * supports, [corridors]), as far as [within] (the reach of its room, or the stretch it was cut to):
     * what the game built of it that is seen, not every fence near it, which takes in the corridors of
     * the mineshafts above, below and beside it where their fences chain into one group. Null with none.
     */
    fun boxOf(pieces: List<Piece>, within: Box?): Box? =
        pieces.filter { within == null || within.overlaps(it.box) }.map { if (within == null) it.box else it.box.clampedTo(within) }.reduceOrNull(Box::union)

    /** What corridors and start rooms are called among pieces. */
    const val CORRIDOR_KIND = "mscorridor"
    const val ROOM_KIND = "msroom"

    private const val SECTION = 5
    private const val SUPPORT_IN = 2

    /** How much bigger a start room can be than the 7 x 7 found of it: the game makes one 7 to 12 across. */
    private const val ROOM_EXTRA = 6

    /** The widest gap a crossing or stairs fills, and how far stairs climb. */
    private const val CONNECTOR = 6
    private const val CONNECTOR_RISE = 8

    /**
     * A start room's floor at [y], from [x], [z]: mostly solid, open four blocks high over the 8 x 8
     * every one is at least (the game cuts it 8 to 13 across and 5 to 10 high) and a wall standing
     * just outside it on the west and north. Looked for over 7 x 7 and three high, caves passed for
     * rooms, and fewer real ones were found. Open is as [isOpen]
     * says, and a few other blocks may stand in it ([ROOM_CLUTTER]): a player's pillar, stone fallen
     * from the roof. In testing a torch or a few dripstones lost rooms, and
     * corridors leaving by two doorways in one wall left 24 of its 42 blocks standing. Its floor is the ground it was cut into, never one
     * laid of wood ([ROOM_WOOD]), as a player's room with a plank floor beside a mineshaft was.
     */
    private fun isRoom(level: BlockSource, cursor: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): Boolean {
        if (isOpen(level, cursor.set(x, y, z)) || !isOpen(level, cursor.set(x, y + 1, z))) return false
        var floor = 0
        var wood = 0
        var clutter = 0
        for (dx in 0 until ROOM) for (dz in 0 until ROOM) {
            if (!isOpen(level, cursor.set(x + dx, y, z + dz))) floor++
            if (isWood(level, cursor)) wood++
            for (dy in 1..ROOM_HIGH) if (!isOpen(level, cursor.set(x + dx, y + dy, z + dz)) && ++clutter > ROOM_CLUTTER) return false
        }
        if (floor < ROOM_FLOOR || wood > ROOM * ROOM * ROOM_WOOD) return false
        var wall = 0
        for (dy in 1..ROOM_HIGH) for (along in 0 until ROOM) {
            if (!isOpen(level, cursor.set(x - 1, y + dy, z + along))) wall++
            if (!isOpen(level, cursor.set(x + along, y + dy, z - 1))) wall++
        }
        if (wall < ROOM_HIGH * ROOM * 2 * ROOM_WALL) return false
        return isCut(level, cursor, x, y, z)
    }

    /**
     * Whether the room at [x], [y], [z] is cut as the game cuts one: a rectangle 8 to 13 across, open
     * three high right into its corners ([CUT_OPEN], [CORNERS_OPEN]), its floor the ground it was cut
     * into, not a structure's ([BUILT], as a trial chamber's room or a dungeon is), and closed off
     * just past its far sides but where a corridor leaves ([FAR_OPEN]). Caves are rounded and run on,
     * and passed for rooms where only the 8 x 8 and its near walls were asked: none of these did,
     * while all but 4 of the rooms found before still were (in testing).
     */
    private fun isCut(level: BlockSource, cursor: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): Boolean {
        fun open(dx: Int, dy: Int, dz: Int) = isOpen(level, cursor.set(x + dx, y + dy, z + dz))
        fun share(cells: Int, open: Int) = if (cells == 0) 0.0 else open.toDouble() / cells
        var open = 0
        var built = 0
        for (dx in 0 until ROOM) for (dz in 0 until ROOM) {
            if (level.getBlockState(cursor.set(x + dx, y, z + dz)).block.let(::isBuilt)) built++
            for (dy in 1..CUT_HIGH) if (open(dx, dy, dz)) open++
        }
        if (share(ROOM * ROOM * CUT_HIGH, open) < CUT_OPEN || built * BUILT_SHARE > ROOM * ROOM) return false
        var corners = 0
        for ((cx, cz) in listOf(0 to 0, ROOM - 2 to 0, 0 to ROOM - 2, ROOM - 2 to ROOM - 2)) {
            for (i in 0..1) for (j in 0..1) for (dy in 1..CUT_HIGH) if (open(cx + i, dy, cz + j)) corners++
        }
        if (share(4 * 4 * CUT_HIGH, corners) < CORNERS_OPEN) return false
        // Some rectangle it could be, closed off just past its far sides.
        for (w in ROOM..MOST_ACROSS) for (d in ROOM..MOST_ACROSS) {
            var inside = 0
            for (a in 0 until w) for (c in 0 until d) for (dy in 1..CUT_HIGH) if (open(a, dy, c)) inside++
            if (share(w * d * CUT_HIGH, inside) < RECTANGLE_OPEN) continue
            var far = 0
            for (k in 0 until FAR) for (dy in 1..CUT_HIGH) {
                for (c in 0 until d) if (open(w + k, dy, c)) far++
                for (a in 0 until w) if (open(a, dy, d + k)) far++
            }
            if (share(FAR * CUT_HIGH * (w + d), far) <= FAR_OPEN) return true
        }
        return false
    }

    /**
     * Whether a start room's whole shape stands at [x], [y], [z], where a cave took its floor or a
     * wall's foot and [isRoom] cannot say: a rectangle 8 to 13 across cut open three high ([DOMED_CUT]),
     * its four walls standing round it but where corridors leave ([DOMED_WALLS], each at least
     * [DOMED_SIDE]), and above it, up to 9 over its floor, the dome the game cuts over every one
     * (`StructurePiece.generateUpperHalfSphere`), open ([DOMED_OPEN]) and closed off just past its
     * sides and top ([DOMED_SHUT]). Caves run on and halls have flat roofs; in testing 240 more real
     * rooms were found so and none of 52,626 other open spots at a start room's place in its chunk.
     */
    private fun isDomed(level: BlockSource, cursor: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): Boolean {
        fun open(dx: Int, dy: Int, dz: Int) = isOpen(level, cursor.set(x + dx, y + dy, z + dz))
        // Open across the middle of the smallest room it could be, or it is no room at all.
        for (dx in intArrayOf(0, 3, ROOM - 1)) for (dz in intArrayOf(0, 3, ROOM - 1)) if (!open(dx, 2, dz)) return false
        for (w in ROOM..MOST_ACROSS) for (d in ROOM..MOST_ACROSS) {
            var cut = 0
            for (a in 0 until w) for (c in 0 until d) for (dy in 1..CUT_HIGH) if (open(a, dy, c)) cut++
            if (cut < DOMED_CUT * w * d * CUT_HIGH) continue
            val sides = intArrayOf(0, 0, 0, 0)
            for (dy in 1..CUT_HIGH) {
                for (a in 0 until w) {
                    if (!open(a, dy, -1)) sides[0]++
                    if (!open(a, dy, d)) sides[1]++
                }
                for (c in 0 until d) {
                    if (!open(-1, dy, c)) sides[2]++
                    if (!open(w, dy, c)) sides[3]++
                }
            }
            if (sides.sum() < DOMED_WALLS * 2 * (w + d) * CUT_HIGH) continue
            if (sides[0] < DOMED_SIDE * w * CUT_HIGH || sides[1] < DOMED_SIDE * w * CUT_HIGH ||
                sides[2] < DOMED_SIDE * d * CUT_HIGH || sides[3] < DOMED_SIDE * d * CUT_HIGH) continue
            for (high in DOME_LOWEST..DOME_HIGHEST) {
                val dome = dome(w, d, high)
                var opened = 0
                for (i in 0 until dome.cells.size / 3) if (open(dome.cells[3 * i], dome.cells[3 * i + 1], dome.cells[3 * i + 2])) opened++
                if (opened < DOMED_OPEN * (dome.cells.size / 3)) continue
                var shut = 0
                for (i in 0 until dome.beyond.size / 3) if (!open(dome.beyond[3 * i], dome.beyond[3 * i + 1], dome.beyond[3 * i + 2])) shut++
                if (shut >= DOMED_SHUT * (dome.beyond.size / 3)) return true
            }
        }
        return false
    }

    /** A start room's dome, from its floor's corner: the blocks the game opens, and those just past its sides and top, each as x, y, z. */
    private class Dome(val cells: IntArray, val beyond: IntArray)

    private val domes = java.util.concurrent.ConcurrentHashMap<Int, Dome>()

    /**
     * The dome over a room [w] by [d] whose box reaches [high] over its floor: from 4 over it to the
     * box's top, the half ellipsoid `generateUpperHalfSphere` opens, every block whose distance from
     * the middle, against the room's half widths and the dome's height, squared, is at most 1.05.
     */
    private fun dome(w: Int, d: Int, high: Int): Dome = domes.getOrPut((w * 16 + d) * 16 + high) {
        val layers = high - CUT_HIGH
        fun inside(a: Int, k: Int, c: Int): Boolean {
            if (a !in 0 until w || c !in 0 until d || k !in 0 until layers) return false
            val n = (a - w / 2f) / (w * 0.5f)
            val l = k.toFloat() / layers
            val p = (c - d / 2f) / (d * 0.5f)
            return n * n + l * l + p * p <= 1.05f
        }
        val cells = ArrayList<Int>()
        val beyond = LinkedHashSet<Triple<Int, Int, Int>>()
        for (k in 0 until layers) for (a in 0 until w) for (c in 0 until d) {
            if (!inside(a, k, c)) continue
            cells += listOf(a, CUT_HIGH + 1 + k, c)
            for ((da, dk, dc) in listOf(Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 0, 1), Triple(0, 0, -1), Triple(0, 1, 0))) {
                if (!inside(a + da, k + dk, c + dc)) beyond += Triple(a + da, CUT_HIGH + 1 + k + dk, c + dc)
            }
        }
        Dome(cells.toIntArray(), beyond.flatMap { listOf(it.first, it.second, it.third) }.toIntArray())
    }

    /** How open a domed room's cut and dome must be, how much of its walls must stand (and of each), and how much just past its dome. */
    private const val DOMED_CUT = 0.9
    private const val DOMED_WALLS = 0.5
    private const val DOMED_SIDE = 0.25
    private const val DOMED_OPEN = 0.9
    private const val DOMED_SHUT = 0.6

    /** How high over its floor a start room's box reaches: the game makes it 4 to 9. */
    private const val DOME_LOWEST = 4
    private const val DOME_HIGHEST = 9

    /** What a structure is built of, and a start room's floor never is: a trial chamber's, a dungeon's, a stronghold's or an ancient city's. */
    private val BUILT = listOf("tuff_brick", "polished_tuff", "chiseled_tuff", "copper", "cobblestone", "stone_bricks", "deepslate_brick", "deepslate_tile", "blackstone_brick")

    /** Whether [block] is one a structure is built of ([BUILT]): not the raw copper and ores anti-xray shows in buried ground. */
    private fun isBuilt(block: Block): Boolean = BuiltInRegistries.BLOCK.getKey(block).path.let { p ->
        BUILT.any { it in p } && !p.startsWith("raw_") && !p.endsWith("_ore")
    }

    /** At most one in this many of a start room's floor may be built. */
    private const val BUILT_SHARE = 4

    /** How high the game cuts a start room before its dome, and the most across it cuts one. */
    private const val CUT_HIGH = 3
    private const val MOST_ACROSS = 13

    /** How open its 8 x 8, its corners and a rectangle it could be must be, and how open the band of [FAR] past its far sides may be. */
    private const val CUT_OPEN = 0.92
    private const val CORNERS_OPEN = 0.8
    private const val RECTANGLE_OPEN = 0.8
    private const val FAR = 3
    private const val FAR_OPEN = 0.9

    /** Planks, logs, wood and carpet: what a floor is laid of, not ground. */
    private fun isWood(level: BlockSource, pos: BlockPos): Boolean =
        BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).path.let { p -> WOOD_ENDS.any { p.endsWith(it) } }

    private val WOOD_ENDS = listOf("_planks", "_log", "_wood", "_carpet")

    /**
     * Open, as a start room is cut: air; water or lava, which an aquifer floods one with and which
     * flows in; and what came after the game cut it and is no full block you cannot see through
     * (`canOcclude`): plants and dripstone grown in caves, a player's torches, rails, slabs and
     * glass; and gravel or sand, which falls into it. In testing these lost rooms, and counting them
     * open found 91 more and let no cave through.
     */
    private fun isOpen(level: BlockSource, pos: BlockPos): Boolean = level.getBlockState(pos).let {
        it.isAir || !it.fluidState.isEmpty || !it.canOcclude() || it.block is FallingBlock
    }

    private const val ROOM = 8
    private const val ROOM_HIGH = 4
    private const val ROOM_FLOOR = 39
    private const val ROOM_WALL = 0.55
    private const val ROOM_CLUTTER = 40

    /** The most of a start room's floor that may be wood. */
    private const val ROOM_WOOD = 0.25
    private const val ROOM_BELOW = 10
    private const val ROOM_ABOVE = 2

    private fun isFence(level: BlockSource, pos: BlockPos): Boolean =
        level.hasChunk(pos.x shr 4, pos.z shr 4) && level.getBlockState(pos).block in FENCES

    private fun isPlanks(level: BlockSource, pos: BlockPos): Boolean =
        level.hasChunk(pos.x shr 4, pos.z shr 4) && level.getBlockState(pos).block in PLANKS

    private fun isChain(level: BlockSource, pos: BlockPos): Boolean =
        level.hasChunk(pos.x shr 4, pos.z shr 4) && level.getBlockState(pos).block == Blocks.IRON_CHAIN

    /**
     * Whether what is at [pos] fills a corridor: anything not open as a start room is ([isOpen]), a
     * full block you cannot see through as stone and planks are; not a rail, cobweb, torch or bamboo
     * shoot, nor water, nor gravel fallen in. Unseen counts as filled.
     */
    private fun blocks(level: BlockSource, pos: BlockPos): Boolean =
        !level.hasChunk(pos.x shr 4, pos.z shr 4) || !isOpen(level, pos)
}
