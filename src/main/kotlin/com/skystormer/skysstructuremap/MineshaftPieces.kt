package com.skystormer.skysstructuremap

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

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

    /** How far up and down from its start room it reaches: stairs lead down and up from corridors. */
    private const val REACH_HEIGHT = 64

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
        if (isStretch(centre)) centre else centre.grow(REACH, down = REACH_HEIGHT, up = REACH_HEIGHT)

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
            val iterator = detection.positions.iterator()
            while (iterator.hasNext()) {
                val key = iterator.nextLong()
                val dx = (BlockPos.getX(key) - seen.minX).toLong()
                val dz = (BlockPos.getZ(key) - seen.minZ).toLong()
                if (dx * dx + dz * dz < nearest) {
                    nearest = dx * dx + dz * dz
                    x = Math.floorDiv(BlockPos.getX(key), TILE) * TILE
                    z = Math.floorDiv(BlockPos.getZ(key), TILE) * TILE
                }
            }
        }
        return Box(x, seen.minY, z, x + TILE - 1, seen.maxY, z + TILE - 1)
    }

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
        val iterator = detection.positions.iterator()
        while (iterator.hasNext()) {
            val key = iterator.nextLong()
            val y = BlockPos.getY(key)
            val chunkX = BlockPos.getX(key) shr 4
            val chunkZ = BlockPos.getZ(key) shr 4
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
                if (!isRoom(level, cursor, x, y, z)) continue
                rooms.add(Box(x, y, z, x + ROOM - 1, y + ROOM_HIGH, z + ROOM - 1))
                if (first) return rooms
                // One room to a chunk: the game starts one mineshaft in it at most.
                break
            }
        }
        return rooms
    }

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
        val iterator = detection.positions.iterator()
        while (iterator.hasNext()) {
            val key = iterator.nextLong()
            val x = BlockPos.getX(key)
            val y = BlockPos.getY(key)
            val z = BlockPos.getZ(key)
            if (!post(level, cursor, x, y, z)) continue
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
        return if (rooms.size >= 2) JoinedPieces.byStarts(pieces, rooms.map(::listOf), ::joined) else listOf(pieces)
    }

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
     * A start room's floor at [y], from [x], [z]: mostly solid, open three blocks high over all of
     * its 7 x 7 and a wall standing just outside it on the west and north. Checked against every
     * start room in a test world: none missed, none found where there was none. Open is air or the
     * water an aquifer floods one with, and a few other blocks may stand in it ([ROOM_CLUTTER]): the
     * dripstone the game grows in caves after laying mineshafts, a player's torch. In Bobby's cache
     * of SiteB a torch or a few dripstones lost rooms, and corridors leaving by two doorways in one
     * wall left 24 of its 42 blocks standing. Its floor is the ground it was cut into, never one
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
        return wall >= ROOM_HIGH * ROOM * 2 * ROOM_WALL
    }

    /** Planks, logs, wood and carpet: what a floor is laid of, not ground. */
    private fun isWood(level: BlockSource, pos: BlockPos): Boolean =
        BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).path.let { p -> WOOD_ENDS.any { p.endsWith(it) } }

    private val WOOD_ENDS = listOf("_planks", "_log", "_wood", "_carpet")

    private fun isOpen(level: BlockSource, pos: BlockPos): Boolean = level.getBlockState(pos).let { it.isAir || it.block == Blocks.WATER }

    private const val ROOM = 7
    private const val ROOM_HIGH = 3
    private const val ROOM_FLOOR = 30
    private const val ROOM_WALL = 0.55
    private const val ROOM_CLUTTER = 15

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

    // What 26.2's `blocksMotion` said, which 26.3 removed: solid, but not a cobweb or a bamboo shoot.
    private fun blocks(level: BlockSource, pos: BlockPos): Boolean =
        !level.hasChunk(pos.x shr 4, pos.z shr 4) || level.getBlockState(pos).let {
            it.block != Blocks.COBWEB && it.block != Blocks.BAMBOO_SAPLING && it.isSolid
        }
}
