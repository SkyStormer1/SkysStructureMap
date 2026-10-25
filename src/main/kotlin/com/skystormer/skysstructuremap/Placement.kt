package com.skystormer.skysstructuremap

import net.minecraft.core.registries.Registries
import net.minecraft.data.registries.VanillaRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets
import net.minecraft.world.level.levelgen.structure.StructureSet
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement

/**
 * Which chunks the game lets a kind start in, by its placement rules alone. Most kinds are spread out
 * over squares of `spacing` chunks, one start in each, and only in the first `spacing −
 * separation` chunks of its square each way: villages (34, 8) never start in the last 8 of every
 * 34 chunks, two chunks in five. So a structure whose start chunk can be told from what was seen
 * ([Tracker]) is no structure when it starts where none can, and no second one starts in a square
 * that already has one ([StructureStore.startTaken]).
 *
 * The spacings are the game's own, read from its built-in structure sets. A server can change
 * them with a data pack; one that did would show real structures refused, in the log.
 */
object Placement {

    /** A kind's square size and how far from the square's far edges it keeps, in chunks. */
    class Spread(val set: ResourceKey<StructureSet>, val spacing: Int, val separation: Int)

    private val SETS = mapOf(
        StructureType.VILLAGE to BuiltinStructureSets.VILLAGES,
        StructureType.OUTPOST to BuiltinStructureSets.PILLAGER_OUTPOSTS,
        StructureType.SHIPWRECK to BuiltinStructureSets.SHIPWRECKS,
        StructureType.TRAIL_RUINS to BuiltinStructureSets.TRAIL_RUINS,
        StructureType.DESERT_TEMPLE to BuiltinStructureSets.DESERT_PYRAMIDS,
        StructureType.JUNGLE_TEMPLE to BuiltinStructureSets.JUNGLE_TEMPLES,
        StructureType.WITCH_HUT to BuiltinStructureSets.SWAMP_HUTS,
        StructureType.MONUMENT to BuiltinStructureSets.OCEAN_MONUMENTS,
        StructureType.ABANDONED_CAMP to BuiltinStructureSets.ABANDONED_CAMP,
        StructureType.IGLOO to BuiltinStructureSets.IGLOOS,
        // One set for both: a square holds a fortress or a bastion, not both.
        StructureType.FORTRESS to BuiltinStructureSets.NETHER_COMPLEXES,
        StructureType.BASTION to BuiltinStructureSets.NETHER_COMPLEXES,
    )

    private val spreads: Map<StructureType, Spread> by lazy {
        try {
            val sets = VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.STRUCTURE_SET)
            SETS.mapNotNull { (type, key) ->
                val placement = runCatching { sets.getOrThrow(key).value().placement() }.getOrNull() as? RandomSpreadStructurePlacement ?: return@mapNotNull null
                type to Spread(key, placement.spacing(), placement.separation())
            }.toMap().also { found ->
                Log.info("Start chunks checked for {}", found.entries.joinToString { "${it.key.id} (${it.value.spacing}/${it.value.separation})" })
            }
        } catch (e: Exception) {
            Log.error("Could not read the game's structure sets; start chunks are not checked", e)
            emptyMap()
        }
    }

    /** Whether [type] can start in chunk [chunkX], [chunkZ]; true for a kind not spread this way. */
    fun allows(type: StructureType, chunkX: Int, chunkZ: Int): Boolean {
        val spread = spreads[type] ?: return true
        val room = spread.spacing - spread.separation
        return Math.floorMod(chunkX, spread.spacing) < room && Math.floorMod(chunkZ, spread.spacing) < room
    }

    /** The square [type]'s start at chunk [chunkX], [chunkZ] is the one start of, with its set; null for a kind not spread this way. */
    fun square(type: StructureType, chunkX: Int, chunkZ: Int): Triple<ResourceKey<StructureSet>, Int, Int>? {
        val spread = spreads[type] ?: return null
        return Triple(spread.set, Math.floorDiv(chunkX, spread.spacing), Math.floorDiv(chunkZ, spread.spacing))
    }

    /**
     * For the kinds whose box is laid out from their start chunk: that chunk. A desert pyramid,
     * jungle temple and witch hut stand from its corner; an outpost's chunk corner is 31 in from
     * its box's west and north sides (its plate is laid from the corner, turned, and the box
     * reaches 16 past it), as for all 58 in the test worlds; a monument's box reaches 29 past its
     * chunk's corner. Null for a pyramid or temple whose box is not the size the game lays (one
     * saved from what was seen), and for other kinds.
     */
    fun startFromBox(type: StructureType, box: Box): Pair<Int, Int>? {
        fun at(x: Int, z: Int) = Math.floorDiv(x, 16) to Math.floorDiv(z, 16)
        return when (type) {
            StructureType.DESERT_TEMPLE -> if (box.sizeX == 21 && box.sizeZ == 21) at(box.minX, box.minZ) else null
            StructureType.JUNGLE_TEMPLE -> if (setOf(box.sizeX, box.sizeZ) == setOf(12, 15)) at(box.minX, box.minZ) else null
            StructureType.WITCH_HUT -> at(box.minX, box.minZ)
            StructureType.OUTPOST -> at(box.minX + 31, box.minZ + 31)
            StructureType.MONUMENT -> at(box.minX + 29, box.minZ + 29)
            StructureType.BURIED_TREASURE -> at(box.minX, box.minZ)
            else -> null
        }
    }
}
