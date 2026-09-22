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

    /**
     * A witch hut's box: as wide as its spruce walls and floor, from one block under the floor to
     * five above it (seven high), as the game builds it. The stilts below are not part of it.
     */
    fun witchHutBox(seen: Box): Box = Box(seen.minX, seen.minY - 1, seen.minZ, seen.maxX, seen.minY + 5, seen.maxZ)

    /**
     * A pillager outpost's box: always 48 × 48 blocks of platforms, the middle one lined up with
     * the chunk its watchtower stands in and one more on each side, and 30 blocks high from its
     * base. The tower is the only part that stands high, so its middle gives the chunk. Null
     * until the tower has been seen.
     */
    fun outpostBox(detection: Detection): Box? {
        val seen = detection.bounds ?: return null
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE
        val iterator = detection.positions.iterator()
        while (iterator.hasNext()) {
            val key = iterator.nextLong()
            if (net.minecraft.core.BlockPos.getY(key) < seen.minY + OUTPOST_TOWER_ABOVE) continue
            val x = net.minecraft.core.BlockPos.getX(key)
            val z = net.minecraft.core.BlockPos.getZ(key)
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
        }
        if (minX > maxX) return null
        val chunkX = Math.floorDiv((minX + maxX) / 2, 16) * 16
        val chunkZ = Math.floorDiv((minZ + maxZ) / 2, 16) * 16
        return Box(chunkX - 16, seen.minY, chunkZ - 16, chunkX + 31, seen.minY + OUTPOST_HEIGHT - 1, chunkZ + 31)
    }

    /** Blocks this far above an outpost's base can only be its tower. */
    private const val OUTPOST_TOWER_ABOVE = 8
    private const val OUTPOST_HEIGHT = 30

    const val MONUMENT_SIZE = 58
    const val MONUMENT_OFFSET = 29
    const val MONUMENT_MIN_Y = 39
    const val MONUMENT_MAX_Y = 61
}
