package com.skystormer.skysstructuremap

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
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

    /** Fortresses: the crossroads found so far. */
    var pieces: List<Piece> = emptyList()

    /** The saved structure this is, once discovered (or recognised as one discovered before). */
    var storedId: String? = null

    /** New blocks since [Tracker] last looked at it. */
    var changed = false

    /** Shipwrecks: new blocks since the last fit. */
    var fitDirty = false
    var lastFitTick = -1000L

    val count: Int get() = positions.size

    /** Adds a block; false when it was already known. */
    fun add(x: Int, y: Int, z: Int, block: Block?, keepBlock: Boolean = true): Boolean {
        val key = BlockPos.asLong(x, y, z)
        if (!positions.add(key)) return false
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

    /** Takes in everything [other] has seen. */
    fun absorb(other: Detection) {
        val iterator = other.positions.iterator()
        while (iterator.hasNext()) {
            val key = iterator.nextLong()
            add(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), other.blocks.get(key))
        }
        // Only shipwrecks keep each block; for the rest the counts are carried over as they are.
        if (other.blocks.isEmpty()) for ((block, n) in other.kinds) kinds.merge(block, n, Int::plus)
        if (storedId == null) storedId = other.storedId
    }

    /**
     * Whether the player is close enough to count as having found it: within
     * [Config.discoverDistance] blocks of its box sideways, at any height. At 0 they have to be
     * inside: inside the box for monuments and shipwrecks, whose boxes are exact; inside (or
     * standing on) one of the cells for the rest, whose real pieces include the air in their
     * corridors, where one big box around a fortress would take in all the lava between bridges.
     */
    fun touches(hitbox: net.minecraft.world.phys.AABB): Boolean {
        val box = box ?: return false
        val near = Config.discoverDistance
        if (near > 0) return box.horizontalDistance((hitbox.minX + hitbox.maxX) / 2, (hitbox.minZ + hitbox.maxZ) / 2) <= near
        if (!box.grow(8).touches(hitbox)) return false
        val reach = Specs.of(type).reach ?: return box.touches(hitbox)
        return cells.values.any { it.grow(reach.sideways, down = reach.down, up = reach.up).touches(hitbox) }
    }

    companion object {
        const val CELL = 8

        fun cellKey(x: Int, y: Int, z: Int): Long =
            BlockPos.asLong(Math.floorDiv(x, CELL), Math.floorDiv(y, CELL), Math.floorDiv(z, CELL))
    }
}
