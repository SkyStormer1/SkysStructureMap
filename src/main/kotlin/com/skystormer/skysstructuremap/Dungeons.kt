package com.skystormer.skysstructuremap

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks

/**
 * A dungeon as the game digs it (`MonsterRoomFeature`): a room 2 or 3 blocks each way from its
 * spawner, walled with cobblestone, on a floor one block under the spawner that is three parts in
 * four mossy cobblestone. The floor and walls reach one block past the room, so 7 or 9 blocks across
 * each way, from the floor to the ceiling 4 blocks over the spawner.
 *
 * Only a zombie, skeleton or spider spawner is one's (the game's `MonsterRoomFeature` picks one of
 * those): a mineshaft's cave spider, a stronghold's silverfish, a bastion's magma cubes never.
 * Trial spawners are another block. Where the mob is not known, a spawner among cobwebs is a
 * mineshaft's: five beside mossy cobblestone were taken for dungeons on a server, and not one of
 * 400 real dungeons had a cobweb within 2 blocks of its spawner.
 */
object Dungeons {

    /** Mossy cobblestone needed in the floor round the spawner. */
    private const val MOSSY = 4

    /** The mobs a dungeon's spawner spawns. */
    private val MOBS = setOf("minecraft:zombie", "minecraft:skeleton", "minecraft:spider")

    /** The room around [detection]'s spawner, or null when it has no dungeon floor. */
    fun room(detection: Detection, level: BlockSource): Box? {
        detection.forEachPosition { x, y, z ->
            room(level, x, y, z)?.let { return it }
        }
        return null
    }

    private fun room(level: BlockSource, x: Int, y: Int, z: Int): Box? {
        val cursor = BlockPos.MutableBlockPos()
        val mob = level.spawnerMob(cursor.set(x, y, z))
        if (mob != null && mob !in MOBS) return null
        for (dx in -2..2) for (dy in -1..2) for (dz in -2..2) {
            if (block(level, cursor.set(x + dx, y + dy, z + dz)) == Blocks.COBWEB) return null
        }
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
