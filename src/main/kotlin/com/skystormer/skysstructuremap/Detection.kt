package com.skystormer.skysstructuremap

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block

/**
 * One structure being put together from the signature blocks seen so far, in this session only.
 * It becomes a saved [Structure] once it is recognised (enough of it has been seen to be sure what
 * it is and where its box is) and you come near it ([touches]).
 *
 * Positions are kept, not just counted, so a chunk that is sent again (you walk away and back)
 * does not count its blocks twice.
 */
class Detection(val id: Int, val type: StructureType, val dimension: String) {

    /** Every signature block seen, as `BlockPos.asLong`. */
    val positions = LongOpenHashSet()

    /**
     * The same blocks gathered into 8×8×8 cells, each the tight box around its blocks: a rough
     * outline of the structure's shape, which for fortresses and bastions is what "being inside"
     * is tested against. One big box around a fortress would take in all the lava between its
     * bridges.
     */
    val cells = Long2ObjectOpenHashMap<Box>()

    /** Around everything seen. */
    var bounds: Box? = null
        private set

    /** Shipwrecks only: the block at each position, for fitting against the templates. */
    val blocks = Long2ObjectOpenHashMap<Block>()

    /** How many of each kind of block, for the log. */
    val kinds = HashMap<Block, Int>()

    /** The structure's box, once recognised; null until then. */
    var box: Box? = null

    /** Shipwrecks only: which template matched. */
    var variant: String? = null

    /** Fortresses: the pieces found so far ([FortressPieces]). */
    var pieces: List<Piece> = emptyList()

    /**
     * Fortresses: one group for each fortress, when its pieces prove it holds more than one
     * ([FortressPieces.split]); empty when it is one. Each is recognised, discovered and saved on
     * its own, with the cells nearest its pieces.
     */
    var parts: List<Detection> = emptyList()

    /** The group a part is one fortress of. */
    var partOf: Detection? = null

    /**
     * Ancient cities: the box of its centre piece, once that has matched. A group whose box is
     * worked out from its centre takes in the whole city, so blocks beyond it are another city
     * ([Groups.splitOff]).
     */
    var centre: Box? = null

    /** Groups split from this one, or it from them: never joined again, and never saved as the same structure. */
    val apart: MutableSet<Detection> = HashSet()

    /** The saved structure this is, once discovered (or recognised as one discovered before). */
    var storedId: String? = null

    /** New blocks since [Tracker] last looked at it. */
    var changed = false

    /** Shipwrecks: new blocks since the last fit. */
    var fitDirty = false
    var lastFitTick = -1000L

    val count: Int get() = positions.size

    /** How many of its blocks are in [box]. */
    fun countIn(box: Box): Int {
        var n = 0
        val iterator = positions.iterator()
        while (iterator.hasNext()) {
            val key = iterator.nextLong()
            if (box.contains(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key))) n++
        }
        return n
    }

    /** How many of its blocks stand in a biome it can be in (see [Spec.biomeAsWhole]). */
    var inBiome = 0
    var biomeLogged = false

    /** Temples: the game's own layout has been found in it, so it stays recognised. */
    var proved = false

    /** Where the design that proved it stands, which the rest of its box is measured from. */
    var piece: Box? = null

    /** Adds a block; false when it was already known. */
    fun add(x: Int, y: Int, z: Int, block: Block?, keepBlock: Boolean = true, inBiome: Boolean = true): Boolean {
        val key = BlockPos.asLong(x, y, z)
        if (!positions.add(key)) return false
        if (inBiome) this.inBiome++
        if (block != null) {
            if (keepBlock) blocks.put(key, block)
            kinds.merge(block, 1, Int::plus)
        }
        val cell = cellKey(x, y, z)
        val old = cells.get(cell)
        cells.put(cell, old?.including(x, y, z) ?: Box.of(x, y, z))
        bounds = bounds?.including(x, y, z) ?: Box.of(x, y, z)
        fitDirty = true
        changed = true
        return true
    }

    /** A part's own share of its group's cells ([parts]): its outline and [bounds]. */
    fun takeCells(own: Collection<Box>) {
        cells.clear()
        for (box in own) cells.put(cellKey(box.minX, box.minY, box.minZ), box)
        bounds = own.reduceOrNull(Box::union)
    }

    /** How many of its blocks are outside [box]. */
    fun countOutside(box: Box): Int = count - countIn(box)

    /**
     * Moves every block outside [keep] into [into], and works out its own outline again from the
     * blocks left. How many of them stood in their biome is shared out in proportion.
     */
    fun moveOutside(keep: Box, into: Detection) {
        val out = LongArrayList()
        val iterator = positions.iterator()
        while (iterator.hasNext()) {
            val key = iterator.nextLong()
            if (!keep.contains(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key))) out.add(key)
        }
        if (out.isEmpty()) return
        val moved = (inBiome.toLong() * out.size / maxOf(1, count)).toInt()
        val keepsBlocks = !blocks.isEmpty()
        for (i in 0 until out.size) {
            val key = out.getLong(i)
            into.add(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), blocks.get(key), keepsBlocks, inBiome = false)
            positions.remove(key)
            blocks.remove(key)
        }
        into.inBiome += moved
        inBiome -= moved
        cells.clear()
        bounds = null
        if (keepsBlocks) kinds.clear()
        val left = positions.iterator()
        while (left.hasNext()) {
            val key = left.nextLong()
            val x = BlockPos.getX(key)
            val y = BlockPos.getY(key)
            val z = BlockPos.getZ(key)
            val cell = cellKey(x, y, z)
            cells.put(cell, cells.get(cell)?.including(x, y, z) ?: Box.of(x, y, z))
            bounds = bounds?.including(x, y, z) ?: Box.of(x, y, z)
            if (keepsBlocks) blocks.get(key)?.let { kinds.merge(it, 1, Int::plus) }
        }
        changed = true
        fitDirty = true
    }

    /** Takes in everything [other] has seen. */
    fun absorb(other: Detection) {
        val iterator = other.positions.iterator()
        while (iterator.hasNext()) {
            val key = iterator.nextLong()
            add(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), other.blocks.get(key), inBiome = false)
        }
        inBiome += other.inBiome
        // Only shipwrecks keep each block; for the rest the counts are carried over as they are.
        if (other.blocks.isEmpty()) for ((block, n) in other.kinds) kinds.merge(block, n, Int::plus)
        if (storedId == null) storedId = other.storedId
        if (centre == null) centre = other.centre
        for (far in other.apart) {
            far.apart.remove(other)
            if (far !== this) {
                far.apart.add(this)
                apart.add(far)
            }
        }
    }

    /**
     * Whether the player is close enough to count as having found it: within
     * [Config.discoverDistance] blocks of its box sideways, at any height; at [Config.DISCOVER_ALL],
     * anywhere in the square of chunks the server sends them. At 0 they have to be
     * inside: inside the box for monuments and shipwrecks, whose boxes are exact; inside (or
     * standing on) one of the cells for the rest, whose real pieces include the air in their
     * corridors, where one big box around a fortress would take in all the lava between bridges.
     */
    fun touches(hitbox: net.minecraft.world.phys.AABB): Boolean {
        val box = box ?: return false
        val near = Config.discoverDistance
        val x = (hitbox.minX + hitbox.maxX) / 2
        val z = (hitbox.minZ + hitbox.maxZ) / 2
        if (near == Config.DISCOVER_ALL) {
            val range = Config.discoverRange() / 16
            val chunkX = Math.floorDiv(Math.floor(x).toInt(), 16)
            val chunkZ = Math.floorDiv(Math.floor(z).toInt(), 16)
            fun gap(at: Int, min: Int, max: Int) = maxOf(0, Math.floorDiv(min, 16) - at, at - Math.floorDiv(max, 16))
            return maxOf(gap(chunkX, box.minX, box.maxX), gap(chunkZ, box.minZ, box.maxZ)) <= range
        }
        if (near > 0) return box.horizontalDistance(x, z) <= near
        if (!box.grow(8).touches(hitbox)) return false
        val reach = Specs.of(type).reach ?: return box.touches(hitbox)
        return cells.values.any { it.grow(reach.sideways, down = reach.down, up = reach.up).touches(hitbox) }
    }

    companion object {
        const val CELL = 8

        /** Ids for [parts], apart from the groups' own (which each [Groups] counts from 1). */
        private val nextPartId = java.util.concurrent.atomic.AtomicInteger(1_000_000)

        fun newPartId(): Int = nextPartId.getAndIncrement()

        fun cellKey(x: Int, y: Int, z: Int): Long =
            BlockPos.asLong(Math.floorDiv(x, CELL), Math.floorDiv(y, CELL), Math.floorDiv(z, CELL))
    }
}
