package com.skystormer.skysstructuremap

/**
 * When a [Detection] has seen enough to be called a structure, and what its box is. Shipwrecks
 * are recognised by [ShipwreckFit] instead, which matches them against the game's own templates.
 */
object Recognise {

    /** Signature blocks needed before a cluster counts, so a few placed by a player do not. */
    const val MONUMENT_BLOCKS = 60
    const val FORTRESS_BLOCKS = 40

    /**
     * Nether ruined portals are built from the same polished blackstone bricks (the game swaps
     * them in for stone bricks), but even the giant ones are small; a bastion is far bigger.
     */
    const val BASTION_BLOCKS = 200
    const val BASTION_WIDTH = 20

    /** The box for [detection] if it is recognised now, else null. */
    fun box(detection: Detection): Box? = Specs.of(detection.type).recognise(detection)

    /**
     * An ocean monument is always the same building: 58 × 23 × 58 blocks from y 39 to 61, with
     * its west and north edges 29 blocks before the start of a chunk. So its box is known exactly
     * once only one chunk line fits what has been seen. Until then it is not recognised: guessing
     * from part of one picked the wrong chunk line in testing. By the time you are inside the box
     * the whole building has loaded, so this never holds up a discovery. Prismarine spread wider
     * than any monument (a player's build beside one) leaves just the blocks' own extent.
     */
    fun monumentBox(seen: Box): Box? {
        val xs = monumentEdges(seen.minX, seen.maxX)
        val zs = monumentEdges(seen.minZ, seen.maxZ)
        if (xs.isEmpty() || zs.isEmpty()) return seen
        if (xs.size > 1 || zs.size > 1) return null
        val minX = xs[0]
        val minZ = zs[0]
        return Box(minX, MONUMENT_MIN_Y, minZ, minX + MONUMENT_SIZE - 1, MONUMENT_MAX_Y, minZ + MONUMENT_SIZE - 1)
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

    const val MONUMENT_SIZE = 58
    const val MONUMENT_OFFSET = 29
    const val MONUMENT_MIN_Y = 39
    const val MONUMENT_MAX_Y = 61
}
