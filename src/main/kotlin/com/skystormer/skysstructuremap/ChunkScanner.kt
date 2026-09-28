package com.skystormer.skysstructuremap

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection

/**
 * Looks through each chunk the server sends for the blocks that give a structure away ([Specs]).
 * This is only reading what the client already has to draw the world; nothing is asked of the
 * server.
 *
 * Each 16×16×16 section first asks its palette whether it could hold any of the blocks at all,
 * which rules out almost every section at once, so a chunk costs next to nothing.
 */
object ChunkScanner {

    private var failed = false

    /**
     * Looks again at every chunk loaded around you, as if it had just arrived. Everything found so
     * far in this dimension is let go of first, so a structure whose blocks were there all along
     * gets another chance: after the mod has been updated, or when part of it loaded oddly.
     *
     * Answers how many chunks were looked at.
     */
    fun lookAgain(level: ClientLevel, around: net.minecraft.core.BlockPos, chunks: Int): Int {
        Tracker.clear()
        var looked = 0
        val middleX = around.x shr 4
        val middleZ = around.z shr 4
        for (x in middleX - chunks..middleX + chunks) {
            for (z in middleZ - chunks..middleZ + chunks) {
                val chunk = level.chunkSource.getChunk(x, z, false) ?: continue
                scan(level, chunk)
                looked++
            }
        }
        return looked
    }

    fun scan(level: ClientLevel, chunk: LevelChunk) {
        try {
            val dimension = level.dimension().identifier().toString()
            val minSectionY = chunk.getSectionYFromSectionIndex(0)
            for ((type, found) in find(chunk.sections, minSectionY, chunk.pos.minBlockX, chunk.pos.minBlockZ, dimension)) {
                Tracker.addBlocks(type, dimension, found)
            }
        } catch (e: Throwable) {
            if (!failed) {
                failed = true
                Log.error("Could not search chunk ${chunk.pos} for structures (logged once)", e)
            }
        }
    }

    /**
     * The signature blocks in one chunk of every kind that generates in [dimension], by kind (only
     * kinds with some). [sections] go up from [minSectionY]; a missing one is empty. The chunk is
     * the one starting at [baseX], [baseZ]. Used for the chunks the server sends and for those
     * Bobby saved ([BobbyScan]), from its own thread: nothing here is changed.
     */
    fun find(sections: Array<out LevelChunkSection?>, minSectionY: Int, baseX: Int, baseZ: Int, dimension: String): Map<StructureType, List<Found>> {
        val all = LinkedHashMap<StructureType, List<Found>>()
        for (type in StructureType.entries) {
            if (type.dimension != dimension) continue
            val found = find(sections, minSectionY, baseX, baseZ, type)
            if (found.isNotEmpty()) all[type] = found
        }
        return all
    }

    private fun find(sections: Array<out LevelChunkSection?>, minSectionY: Int, baseX: Int, baseZ: Int, type: StructureType): List<Found> {
        val spec = Specs.of(type)
        if (spec.blocks.isEmpty() && spec.tags.isEmpty()) return emptyList()
        val test = { state: BlockState -> spec.matches(state) }
        val found = ArrayList<Found>()
        for (index in sections.indices) {
            val section = sections[index] ?: continue
            val baseY = (minSectionY + index) shl 4
            if (baseY + 15 < spec.minY || baseY > spec.maxY) continue
            if (section.hasOnlyAir() || !section.maybeHas(test)) continue
            for (y in 0 until 16) {
                val worldY = baseY + y
                if (worldY < spec.minY || worldY > spec.maxY) continue
                for (z in 0 until 16) {
                    for (x in 0 until 16) {
                        val state = section.getBlockState(x, y, z)
                        if (!spec.matches(state)) continue
                        // Wood is everywhere on land, bricks under the sea, and so on: where a
                        // block is can matter as much as what it is.
                        val allowed = !spec.biomeFiltered || spec.biomeAllowed(biomeAt(section, x, y, z))
                        if (!allowed && !spec.biomeAsWhole) continue
                        found.add(Found(baseX + x, worldY, baseZ + z, state.block, allowed))
                    }
                }
            }
        }
        return found
    }

    /** The biome at a block of a section, as its id without `minecraft:`; biomes are kept per 4×4×4. */
    private fun biomeAt(section: LevelChunkSection, x: Int, y: Int, z: Int): String =
        section.getNoiseBiome(x shr 2, y shr 2, z shr 2).unwrapKey().map { it.identifier().path }.orElse("")

    /** A block found, and whether it stands in a biome its structure can be in. */
    class Found(val x: Int, val y: Int, val z: Int, val block: Block, val inBiome: Boolean = true)
}
