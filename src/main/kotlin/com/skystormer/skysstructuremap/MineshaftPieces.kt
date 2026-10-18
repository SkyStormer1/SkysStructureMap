package com.skystormer.skysstructuremap

import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * A mineshaft's corridors are held up by supports the game lays the same way every time
 * (`MineshaftPieces.MineShaftCorridor.placeSupport`): two fence posts two blocks high, two blocks
 * apart across the corridor, with planks on top of each (and nearly always across the gap), and the
 * corridor open between them. Fences are what farms and houses are fenced with; a run of these
 * frames is not.
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

    /** A post standing at [x], [y], [z]: two fences on a block that is not one, under planks. */
    private fun post(level: BlockSource, cursor: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): Boolean =
        isFence(level, cursor.set(x, y, z)) && isFence(level, cursor.set(x, y + 1, z)) &&
            !isFence(level, cursor.set(x, y - 1, z)) && isPlanks(level, cursor.set(x, y + 2, z))

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
    fun startRoom(detection: Detection, level: BlockSource): Box? {
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
        for ((chunk, range) in heights) {
            val x = ChunkPos.getX(chunk) * 16 + 2
            val z = ChunkPos.getZ(chunk) * 16 + 2
            if (!level.hasChunk(x shr 4, z shr 4)) continue
            for (y in range[0] - ROOM_BELOW..range[1] + ROOM_ABOVE) {
                if (isRoom(level, cursor, x, y, z)) return Box(x, y, z, x + ROOM - 1, y + ROOM_HIGH, z + ROOM - 1)
            }
        }
        return null
    }

    /**
     * A start room's floor at [y], from [x], [z]: mostly solid, with air three blocks high over all
     * of its 7 x 7 and a wall standing just outside it on the west and north. Checked against every
     * start room in a test world: none missed, none found where there was none.
     */
    private fun isRoom(level: BlockSource, cursor: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): Boolean {
        if (isAir(level, cursor.set(x, y, z)) || !isAir(level, cursor.set(x, y + 1, z))) return false
        var floor = 0
        for (dx in 0 until ROOM) for (dz in 0 until ROOM) {
            if (!isAir(level, cursor.set(x + dx, y, z + dz))) floor++
            for (dy in 1..ROOM_HIGH) if (!isAir(level, cursor.set(x + dx, y + dy, z + dz))) return false
        }
        if (floor < ROOM_FLOOR) return false
        var wall = 0
        for (dy in 1..ROOM_HIGH) for (along in 0 until ROOM) {
            if (!isAir(level, cursor.set(x - 1, y + dy, z + along))) wall++
            if (!isAir(level, cursor.set(x + along, y + dy, z - 1))) wall++
        }
        return wall >= ROOM_HIGH * ROOM * 2 * ROOM_WALL
    }

    private const val ROOM = 7
    private const val ROOM_HIGH = 3
    private const val ROOM_FLOOR = 30
    private const val ROOM_WALL = 0.6
    private const val ROOM_BELOW = 10
    private const val ROOM_ABOVE = 2

    private fun isAir(level: BlockSource, pos: BlockPos): Boolean = level.getBlockState(pos).isAir

    private fun isFence(level: BlockSource, pos: BlockPos): Boolean =
        level.hasChunk(pos.x shr 4, pos.z shr 4) && level.getBlockState(pos).block in FENCES

    private fun isPlanks(level: BlockSource, pos: BlockPos): Boolean =
        level.hasChunk(pos.x shr 4, pos.z shr 4) && level.getBlockState(pos).block in PLANKS

    // What 26.2's `blocksMotion` said, which 26.3 removed: solid, but not a cobweb or a bamboo shoot.
    private fun blocks(level: BlockSource, pos: BlockPos): Boolean =
        !level.hasChunk(pos.x shr 4, pos.z shr 4) || level.getBlockState(pos).let {
            it.block != Blocks.COBWEB && it.block != Blocks.BAMBOO_SAPLING && it.isSolid
        }
}
