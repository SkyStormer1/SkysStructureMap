package com.skystormer.skysstructuremap

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks

/**
 * A dungeon as the game digs it (`MonsterRoomFeature`): a room 2 or 3 blocks each way from its
 * spawner, walled with cobblestone, on a floor one block under the spawner that is three parts in
 * four mossy cobblestone. The floor and walls reach one block past the room, so 7 or 9 blocks across
 * each way, from the floor to the ceiling 4 blocks over the spawner.
 *
 * A mineshaft's spawner stands among cobwebs and a stronghold's on stone bricks: neither has the
 * mossy floor.
 */
object Dungeons {

    /** Mossy cobblestone needed in the floor round the spawner. */
    private const val MOSSY = 4

    /** The room around [detection]'s spawner, or null when it has no dungeon floor. */
    fun room(detection: Detection, level: BlockSource): Box? {
        val iterator = detection.positions.iterator()
        while (iterator.hasNext()) {
            val key = iterator.nextLong()
            room(level, BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key))?.let { return it }
        }
        return null
    }

    private fun room(level: BlockSource, x: Int, y: Int, z: Int): Box? {
        val cursor = BlockPos.MutableBlockPos()
        var mossy = 0
        for (dx in -4..4) for (dz in -4..4) {
            if (block(level, cursor.set(x + dx, y - 1, z + dz)) == Blocks.MOSSY_COBBLESTONE) mossy++
        }
        if (mossy < MOSSY) return null
        // The wider rooms have floor out at 4; the floor is only laid where the ground was solid, so two will do.
        fun reach(alongX: Boolean): Int {
            var edge = 0
            for (side in intArrayOf(-4, 4)) for (across in -3..3) {
                val at = if (alongX) cursor.set(x + side, y - 1, z + across) else cursor.set(x + across, y - 1, z + side)
                val floor = block(level, at)
                if (floor == Blocks.MOSSY_COBBLESTONE || floor == Blocks.COBBLESTONE) edge++
            }
            return if (edge >= 2) 4 else 3
        }
        val rx = reach(true)
        val rz = reach(false)
        return Box(x - rx, y - 1, z - rz, x + rx, y + 4, z + rz)
    }

    private fun block(level: BlockSource, pos: BlockPos) =
        if (level.hasChunk(pos.x shr 4, pos.z shr 4)) level.getBlockState(pos).block else null
}
