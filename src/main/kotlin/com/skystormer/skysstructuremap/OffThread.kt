package com.skystormer.skysstructuremap

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.PalettedContainer
import java.util.concurrent.Executors

/**
 * Work on the blocks round a group done on a thread of its own, so a large structure does not hold
 * up a frame: labelling a fortress's pieces ([FortressLabelling]), drawing a village ([VillageDrawing]).
 * The chunks' sections are copied on the main thread first (the world is only safe to read there,
 * and a copy is quick), and what was worked out is handed back to it. A group is worked on again
 * only once more of it has been seen, and at most every [everyMillis].
 */
open class OffThread<T : Any>(private val what: String, private val everyMillis: Long) {

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Sky's Structure Map $what").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    /** Groups being worked on now, by id. */
    private val busy = HashSet<Int>()

    /** How many blocks each group had when it was last worked on, and when that was. */
    private val doneAt = HashMap<Int, Int>()
    private val doneWhen = HashMap<Int, Long>()

    /**
     * Runs [work] on the blocks over [box], then [done] with what it worked out on the main thread,
     * unless already asked for what has been seen of [detection]. Main thread.
     */
    protected fun request(detection: Detection, box: Box, work: (BlockSource) -> T?, done: (T) -> Unit) {
        if (detection.id in busy || doneAt[detection.id] == detection.count) return
        val now = System.currentTimeMillis()
        if (now - (doneWhen[detection.id] ?: 0L) < everyMillis) {
            // Asked again once the wait is over, whether or not more arrives.
            detection.changed = true
            return
        }
        doneWhen[detection.id] = now
        val level = Minecraft.getInstance().level ?: return
        val snapshot = Snapshot.of(level, box)
        val count = detection.count
        busy.add(detection.id)
        worker.execute {
            val result = try {
                work(snapshot)
            } catch (e: Throwable) {
                Log.error("Could not work out ${detection.type.id} #${detection.id}'s $what", e)
                null
            }
            Minecraft.getInstance().execute {
                busy.remove(detection.id)
                doneAt[detection.id] = count
                if (result != null) {
                    done(result)
                    detection.changed = true
                }
            }
        }
    }

    fun clear() {
        doneAt.clear()
        doneWhen.clear()
    }

    /** Copies of the loaded chunk sections over a box, and their biomes, readable from any thread. */
    private class Snapshot(
        private val sections: Long2ObjectOpenHashMap<PalettedContainer<BlockState>>,
        private val biomes: Long2ObjectOpenHashMap<PalettedContainer<Holder<Biome>>>,
        private val chunks: Set<Long>,
    ) : BlockSource {

        override fun hasChunk(chunkX: Int, chunkZ: Int) = key(chunkX, 0, chunkZ) in chunks

        override fun getBlockState(pos: BlockPos): BlockState =
            sections.get(key(pos.x shr 4, pos.y shr 4, pos.z shr 4))?.get(pos.x and 15, pos.y and 15, pos.z and 15) ?: AIR

        override fun biome(pos: BlockPos): String? =
            biomes.get(key(pos.x shr 4, pos.y shr 4, pos.z shr 4))?.get((pos.x shr 2) and 3, (pos.y shr 2) and 3, (pos.z shr 2) and 3)
                ?.unwrapKey()?.map { it.identifier().path }?.orElse(null)

        companion object {
            private val AIR: BlockState = Blocks.AIR.defaultBlockState()

            private fun key(x: Int, y: Int, z: Int) = BlockPos.asLong(x, y, z)

            fun of(level: ClientLevel, box: Box): Snapshot {
                val sections = Long2ObjectOpenHashMap<PalettedContainer<BlockState>>()
                val biomes = Long2ObjectOpenHashMap<PalettedContainer<Holder<Biome>>>()
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
                            if (section.hasOnlyAir()) continue
                            sections.put(key(cx, sy, cz), section.states.copy())
                            biomes.put(key(cx, sy, cz), section.biomes.copy())
                        }
                    }
                }
                return Snapshot(sections, biomes, chunks)
            }
        }
    }
}
