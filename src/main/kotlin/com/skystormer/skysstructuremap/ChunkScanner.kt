package com.skystormer.skysstructuremap

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk

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

    fun scan(level: ClientLevel, chunk: LevelChunk) {
        try {
            val dimension = level.dimension().identifier().toString()
            for (type in StructureType.entries) {
                if (type.dimension == dimension) scan(chunk, type, dimension)
            }
        } catch (e: Throwable) {
            if (!failed) {
                failed = true
                Log.error("Could not search chunk ${chunk.pos} for structures (logged once)", e)
            }
        }
    }

    private fun scan(chunk: LevelChunk, type: StructureType, dimension: String) {
        val spec = Specs.of(type)
        if (spec.blocks.isEmpty() && spec.tags.isEmpty()) return
        val test = { state: BlockState -> spec.matches(state) }
        val baseX = chunk.pos.minBlockX
        val baseZ = chunk.pos.minBlockZ
        val found = ArrayList<Found>()
        for (index in chunk.sections.indices) {
            val section = chunk.getSection(index)
            val baseY = chunk.getSectionYFromSectionIndex(index) shl 4
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
                        if (spec.biomeFiltered && !spec.biomeAllowed(biomeAt(chunk, x, worldY, z))) continue
                        found.add(Found(baseX + x, worldY, baseZ + z, state.block))
                    }
                }
            }
        }
        if (found.isNotEmpty()) Tracker.addBlocks(type, dimension, found)
    }

    private fun biomeAt(chunk: LevelChunk, x: Int, y: Int, z: Int): String =
        chunk.getNoiseBiome(x shr 2, y shr 2, z shr 2).unwrapKey().map { it.identifier().path }.orElse("")

    class Found(val x: Int, val y: Int, val z: Int, val block: Block)
}
