package com.skystormer.skysstructuremap

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.PalettedContainer
import java.util.concurrent.Executors

/**
 * Labels the fortresses around you ([FortressPieces.label]) on a thread of its own, so a large one
 * does not hold up a frame. The chunks' sections are copied on the main thread first (the world is
 * only safe to read there, and a copy is quick), and the pieces found are handed back to it.
 * A fortress is labelled again only once more of it has been seen, and at most every few seconds.
 */
object FortressLabelling {

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Sky's Structure Map fortress pieces").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    /** Groups being labelled now, by id. */
    private val busy = HashSet<Int>()

    /** How many blocks each group had when it was last labelled, and when that was. */
    private val labelledAt = HashMap<Int, Int>()
    private val labelledWhen = HashMap<Int, Long>()

    /** The least time between two labellings of one fortress: as you fly, chunks keep arriving. */
    private const val EVERY_MILLIS = 5000L

    /** Asks for [detection]'s pieces, unless already asked for what has been seen of it. Main thread. */
    fun request(detection: Detection, bricks: Box) {
        if (detection.id in busy || labelledAt[detection.id] == detection.count) return
        val now = System.currentTimeMillis()
        if (now - (labelledWhen[detection.id] ?: 0L) < EVERY_MILLIS) {
            // Asked again once the wait is over, whether or not more arrives.
            detection.changed = true
            return
        }
        labelledWhen[detection.id] = now
        val level = Minecraft.getInstance().level ?: return
        val snapshot = Snapshot.of(level, bricks)
        val count = detection.count
        busy.add(detection.id)
        worker.execute {
            val pieces = try {
                FortressPieces.label(snapshot, bricks)
            } catch (e: Throwable) {
                Log.error("Could not work out fortress #${detection.id}'s pieces", e)
                null
            }
            Minecraft.getInstance().execute {
                busy.remove(detection.id)
                labelledAt[detection.id] = count
                if (pieces != null) {
                    if (pieces.size != detection.pieces.size) Log.info("Fortress #{}: {} pieces", detection.id, pieces.size)
                    detection.pieces = pieces
                    detection.changed = true
                }
            }
        }
    }

    fun clear() {
        labelledAt.clear()
        labelledWhen.clear()
    }

    /** Copies of the loaded chunk sections over a box, readable from any thread. */
    private class Snapshot(private val sections: Long2ObjectOpenHashMap<PalettedContainer<BlockState>>, private val chunks: Set<Long>) : BlockSource {

        override fun hasChunk(chunkX: Int, chunkZ: Int) = key(chunkX, 0, chunkZ) in chunks

        override fun getBlockState(pos: BlockPos): BlockState =
            sections.get(key(pos.x shr 4, pos.y shr 4, pos.z shr 4))?.get(pos.x and 15, pos.y and 15, pos.z and 15) ?: AIR

        companion object {
            private val AIR: BlockState = Blocks.AIR.defaultBlockState()

            private fun key(x: Int, y: Int, z: Int) = BlockPos.asLong(x, y, z)

            fun of(level: ClientLevel, box: Box): Snapshot {
                val sections = Long2ObjectOpenHashMap<PalettedContainer<BlockState>>()
                val chunks = HashSet<Long>()
                for (cx in ((box.minX - 2) shr 4)..((box.maxX + 2) shr 4)) {
                    for (cz in ((box.minZ - 2) shr 4)..((box.maxZ + 2) shr 4)) {
                        if (!level.hasChunk(cx, cz)) continue
                        chunks.add(key(cx, 0, cz))
                        val chunk = level.getChunk(cx, cz)
                        for (sy in ((box.minY - 2) shr 4)..((box.maxY + 2) shr 4)) {
                            val index = chunk.getSectionIndexFromSectionY(sy)
                            if (index < 0 || index >= chunk.sections.size) continue
                            val section = chunk.getSection(index)
                            if (!section.hasOnlyAir()) sections.put(key(cx, sy, cz), section.states.copy())
                        }
                    }
                }
                return Snapshot(sections, chunks)
            }
        }
    }
}
