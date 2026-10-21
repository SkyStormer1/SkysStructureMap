package com.skystormer.skysstructuremap

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection
import java.util.IdentityHashMap

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
            val dimension = dimensionId(level)
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
    /**
     * Every section is read once for all the kinds: a block is looked up in what each state
     * stands for ([kindsOf]), rather than the section read again for each of some 30 kinds, which
     * read sections of planks or bricks block by block several times over.
     */
    fun find(sections: Array<out LevelChunkSection?>, minSectionY: Int, baseX: Int, baseZ: Int, dimension: String): Map<StructureType, List<SignatureBlock>> {
        val types = StructureType.entries.filter { type ->
            type.dimension == dimension && Specs.of(type).let { it.blocks.isNotEmpty() || it.tags.isNotEmpty() }
        }
        if (types.isEmpty()) return emptyMap()
        val specs = types.map(Specs::of)
        val found = Array(types.size) { ArrayList<SignatureBlock>() }
        // Which kinds each block state is a signature of, by their place in [types]; a chunk holds
        // a few dozen states, each worked out once.
        val kinds = IdentityHashMap<BlockState, IntArray>()
        fun kindsOf(state: BlockState): IntArray = kinds.getOrPut(state) {
            specs.indices.filter { specs[it].matches(state) }.toIntArray()
        }
        // Per kind, per section, whether it holds one of the kind's biomes; worked out only when asked.
        val nearby = arrayOfNulls<Array<Boolean?>>(types.size)
        for (index in sections.indices) {
            val section = sections[index] ?: continue
            val baseY = (minSectionY + index) shl 4
            if (section.hasOnlyAir() || !section.maybeHas { state -> kindsOf(state).any { baseY + 15 >= specs[it].minY && baseY <= specs[it].maxY } }) continue
            // The biome of each 4×4×4 cell, asked only of cells holding a block that needs it.
            val biomes = arrayOfNulls<String>(64)
            for (y in 0 until 16) {
                val worldY = baseY + y
                for (z in 0 until 16) {
                    for (x in 0 until 16) {
                        val state = section.getBlockState(x, y, z)
                        val of = kindsOf(state)
                        if (of.isEmpty()) continue
                        for (k in of) {
                            val spec = specs[k]
                            if (worldY < spec.minY || worldY > spec.maxY) continue
                            spec.highest[state.block]?.let { if (worldY > it) continue }
                            // Wood is everywhere on land, bricks under the sea, and so on: where a
                            // block is can matter as much as what it is.
                            val allowed = !spec.biomeFiltered ||
                                spec.biomeAllowed(biomes.cell(x, y, z) { biomeAt(section, x, y, z) }) ||
                                (spec.biomeNearby && holdsBiome(sections, index, spec, nearby[k] ?: arrayOfNulls<Boolean>(sections.size).also { nearby[k] = it }))
                            if (!allowed && !spec.biomeAsWhole) continue
                            found[k].add(SignatureBlock(baseX + x, worldY, baseZ + z, state.block, allowed))
                        }
                    }
                }
            }
        }
        val all = LinkedHashMap<StructureType, List<SignatureBlock>>()
        for (k in types.indices) if (found[k].isNotEmpty()) all[types[k]] = found[k]
        return all
    }

    /** The biome cached for the 4×4×4 cell holding [x], [y], [z], or [biome] the first time. */
    private inline fun Array<String?>.cell(x: Int, y: Int, z: Int, biome: () -> String): String {
        val i = (x shr 2) or ((z shr 2) shl 2) or ((y shr 2) shl 4)
        return this[i] ?: biome().also { this[i] = it }
    }

    /** Whether section [index], or the one above or below it, holds a biome [spec] allows ([Spec.biomeNearby]). */
    private fun holdsBiome(sections: Array<out LevelChunkSection?>, index: Int, spec: Spec, known: Array<Boolean?>): Boolean =
        (index - 1..index + 1).any { i ->
            if (i !in sections.indices) return@any false
            known[i] ?: run {
                val section = sections[i]
                val holds = section != null && (0 until 4).any { bx -> (0 until 4).any { by -> (0 until 4).any { bz ->
                    spec.biomeAllowed(section.getNoiseBiome(bx, by, bz).unwrapKey().map { it.identifier().path }.orElse(""))
                } } }
                known[i] = holds
                holds
            }
        }

    /** The biome at a block of a section, as its id without `minecraft:`; biomes are kept per 4×4×4. */
    private fun biomeAt(section: LevelChunkSection, x: Int, y: Int, z: Int): String =
        section.getNoiseBiome(x shr 2, y shr 2, z shr 2).unwrapKey().map { it.identifier().path }.orElse("")

    /** A block found, and whether it stands in a biome its structure can be in. */
    class SignatureBlock(val x: Int, val y: Int, val z: Int, val block: Block, val inBiome: Boolean = true)
}
