package com.skystormer.skysstructuremap

/**
 * When a [Detection] has seen enough to be called a structure, and what its box is. Shipwrecks and
 * pillager outposts are recognised by [TemplateFit] instead, which matches them against the game's designs.
 */
object Recognise {

    /**
     * Signature blocks needed before a cluster counts, so a few placed by a player do not. A
     * monument is built of well over 5000 prismarine blocks; a player's build near the sea has a few dozen.
     */
    const val MONUMENT_BLOCKS = 500
    const val FORTRESS_BLOCKS = 40

    /** The box for [detection] if it is recognised now, else null. */
    fun box(detection: Detection): Box? = Specs.of(detection.type).recognise(detection)

    /**
     * An ocean monument is always the same building: 58 × 23 × 58 blocks from y 39 to 61, with
     * its west and north edges 29 blocks before the start of a chunk. So its box is known exactly
     * once only one chunk line fits what has been seen. Where more than one does (one half
     * dismantled, 27 blocks of it left across in testing) and where prismarine spreads
     * wider than any monument (a player's build beside one), it is given the place that holds the
     * most of what was seen ([blocksIn] counts the blocks seen in a box). Whether a monument really
     * stands there, or a chunk over, is then [MonumentLayout]'s to say, and it waits for half of it
     * to have loaded, so a guess from the first chunks of one is never taken.
     */
    fun monumentBox(seen: Box, blocksIn: (Box) -> Int): Box? {
        var xs = monumentEdges(seen.minX, seen.maxX)
        var zs = monumentEdges(seen.minZ, seen.maxZ)
        if (xs.isEmpty()) xs = edgesAcross(seen.minX, seen.maxX)
        if (zs.isEmpty()) zs = edgesAcross(seen.minZ, seen.maxZ)
        val places = xs.flatMap { x -> zs.map { z -> monumentAt(x, z) } }
        return places.singleOrNull() ?: places.maxByOrNull(blocksIn)
    }

    private fun monumentAt(minX: Int, minZ: Int) =
        Box(minX, MONUMENT_MIN_Y, minZ, minX + MONUMENT_SIZE - 1, MONUMENT_MAX_Y, minZ + MONUMENT_SIZE - 1)

    /** Every west (or north) edge a monument can have that takes in some of [min] to [max]. */
    private fun edgesAcross(min: Int, max: Int): List<Int> {
        val lowest = Math.floorDiv(min - 28 + 15, 16) * 16
        val highest = Math.floorDiv(max + 29, 16) * 16
        return (lowest..highest step 16).map { it - MONUMENT_OFFSET }
    }

    /**
     * Every west (or north) edge a monument holding blocks from [min] to [max] along one axis
     * could have: one once enough is seen, several before, none when no monument could hold them.
     */
    fun monumentEdges(min: Int, max: Int): List<Int> {
        // The chunk start c puts the box at c - 29 .. c + 28, which must hold min..max.
        val lowest = Math.floorDiv(max - 28 + 15, 16) * 16
        val highest = Math.floorDiv(min + 29, 16) * 16
        return (lowest..highest step 16).map { it - MONUMENT_OFFSET }
    }

    /**
     * A witch hut's box: as wide as its spruce walls and floor, from one block under the floor to
     * five above it (seven high), as the game builds it. The stilts below are not part of it.
     */
    fun witchHutBox(seen: Box): Box = Box(seen.minX, seen.minY - 1, seen.minZ, seen.maxX, seen.minY + 5, seen.maxZ)

    /**
     * A pillager outpost's box: always 48 × 48 blocks, its 16 × 16 base plate under the watchtower
     * and 16 more on each side, and 30 blocks high from the tower's base. [tower] is the
     * watchtower's box, as matched against the game's design, turned by [rotation].
     *
     * The plate is laid from a chunk's corner, turned the same way as the tower, so it starts on a
     * chunk line on a side the turn faces away from (x: unturned or a quarter anticlockwise; z:
     * unturned or a quarter clockwise) and a block past one on the others. The tower stands on it
     * either flush with that edge or a block in: of the two places, only one lines up. Taking the
     * plate as the tower's chunk (as before) put the box a block off in three outposts of four.
     */
    fun outpostBox(tower: Box, rotation: net.minecraft.world.level.block.Rotation): Box {
        val alignX = if (rotation == net.minecraft.world.level.block.Rotation.NONE || rotation == net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90) 0 else 1
        val alignZ = if (rotation == net.minecraft.world.level.block.Rotation.NONE || rotation == net.minecraft.world.level.block.Rotation.CLOCKWISE_90) 0 else 1
        fun plateStart(towerMin: Int, align: Int) = if (Math.floorMod(towerMin - align, 16) == 0) towerMin else towerMin - 1
        val x = plateStart(tower.minX, alignX)
        val z = plateStart(tower.minZ, alignZ)
        return Box(x - 16, tower.minY, z - 16, x + 31, tower.minY + OUTPOST_HEIGHT - 1, z + 31)
    }

    private const val OUTPOST_HEIGHT = 30

    const val MONUMENT_SIZE = 58
    const val MONUMENT_OFFSET = 29
    const val MONUMENT_MIN_Y = 39
    const val MONUMENT_MAX_Y = 61
}
