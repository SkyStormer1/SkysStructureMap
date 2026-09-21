package com.skystormer.skysstructuremap

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation

/**
 * Recognises shipwrecks by matching the wood seen in the sea against the game's own shipwreck
 * templates, which are inside the Minecraft jar. The game places them turned (never mirrored) and
 * without changing any block, so a real wreck matches one of them almost block for block, and a
 * match gives its exact box.
 *
 * Matching is by voting: every seen block, paired with every block of the same kind in a
 * template, says where that template would have to start; the true start gets a vote from nearly
 * every block. The few best-voted starts are then checked block by block against the world.
 */
object ShipwreckFit {

    class Template(val name: String, val sizeX: Int, val sizeY: Int, val sizeZ: Int, val blocks: List<Pair<BlockPos, Block>>) {
        val byBlock: Map<Block, List<BlockPos>> = blocks.groupBy({ it.second }, { it.first })
    }

    private val NAMES = listOf(
        "with_mast", "with_mast_degraded",
        "rightsideup_full", "rightsideup_full_degraded", "rightsideup_fronthalf", "rightsideup_fronthalf_degraded",
        "rightsideup_backhalf", "rightsideup_backhalf_degraded",
        "sideways_full", "sideways_full_degraded", "sideways_fronthalf", "sideways_fronthalf_degraded",
        "sideways_backhalf", "sideways_backhalf_degraded",
        "upsidedown_full", "upsidedown_full_degraded", "upsidedown_fronthalf", "upsidedown_fronthalf_degraded",
        "upsidedown_backhalf", "upsidedown_backhalf_degraded",
    )

    /** Blocks the game leaves out when placing a template, or that could be anywhere in the sea. */
    private val IGNORED = setOf(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR, Blocks.STRUCTURE_BLOCK, Blocks.STRUCTURE_VOID, Blocks.JIGSAW, Blocks.WATER)

    val templates: List<Template> by lazy { load() }

    /** Every kind of block that is in some shipwreck: what chunks are searched for. */
    val signatureBlocks: Set<Block> by lazy {
        templates.flatMapTo(HashSet()) { it.byBlock.keys }.also { blocks ->
            Log.info("Searching the sea for {} kinds of shipwreck block: {}", blocks.size,
                blocks.map { BuiltInRegistries.BLOCK.getKey(it).path }.sorted().joinToString(", "))
        }
    }

    /** At least this many wood blocks before trying, so a lone plank in the sea is not worth the work. */
    const val MIN_BLOCKS = 12

    /**
     * How much of a template must be there, of the blocks in loaded chunks. Real wrecks are
     * missing plenty (the first one tested had 405 of 598), so the placement also has to be one
     * nearly every block seen agrees on, which chance never manages.
     */
    private const val MIN_MATCH = 0.5
    private const val MIN_MATCHED_BLOCKS = 60
    private const val MIN_AGREEMENT = 0.8

    private const val SAMPLES = 24

    class Match(val template: Template, val box: Box, val matched: Int, val known: Int)

    /** Why the last [fit] came out as it did, for the log. */
    var lastReport = ""
        private set

    /** The best-matching template placement for [detection]'s blocks, or null for none. */
    fun fit(detection: Detection, level: Level): Match? {
        if (templates.isEmpty() || detection.blocks.size < MIN_BLOCKS) {
            lastReport = "${detection.blocks.size} blocks, too few"
            return null
        }
        // An even spread of the blocks seen, so one end of a wreck cannot outvote the rest.
        val keys = detection.blocks.keys.toLongArray().also { it.sort() }
        val step = maxOf(1, keys.size / SAMPLES)
        val samples = (keys.indices step step).map { keys[it] }

        class Candidate(val template: Template, val rotation: Rotation, val origin: Long, val votes: Int)
        val candidates = ArrayList<Candidate>()
        for (template in templates) {
            for (rotation in Rotation.entries) {
                val votes = Long2IntOpenHashMap()
                for (key in samples) {
                    val block = detection.blocks.get(key) ?: continue
                    val local = template.byBlock[block] ?: continue
                    val x = BlockPos.getX(key)
                    val y = BlockPos.getY(key)
                    val z = BlockPos.getZ(key)
                    for (pos in local) {
                        val turned = pos.rotate(rotation)
                        votes.addTo(BlockPos.asLong(x - turned.x, y - turned.y, z - turned.z), 1)
                    }
                }
                var bestOrigin = 0L
                var bestVotes = 0
                val iterator = votes.long2IntEntrySet().fastIterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.intValue > bestVotes) {
                        bestVotes = entry.intValue
                        bestOrigin = entry.longKey
                    }
                }
                if (bestVotes >= samples.size * MIN_AGREEMENT) candidates.add(Candidate(template, rotation, bestOrigin, bestVotes))
            }
        }
        val bestVotes = candidates.maxOfOrNull { it.votes } ?: 0
        lastReport = "${detection.blocks.size} blocks, ${samples.size} samples, ${candidates.size} placements most samples agree on (best $bestVotes)"
        if (candidates.isEmpty()) return null

        var best: Match? = null
        for (candidate in candidates.sortedByDescending { it.votes }.take(6)) {
            val match = check(candidate.template, candidate.rotation, candidate.origin, level)
            lastReport += "; ${candidate.template.name} ${candidate.rotation}: " + (match?.let { "${it.matched}/${it.known}" } ?: lastCheck)
            if (match == null) continue
            // A degraded wreck is a full one with blocks missing, so on an intact wreck both fit;
            // the one explaining more blocks is the right one.
            if (best == null || match.matched > best.matched) best = match
        }
        return best
    }

    private var lastCheck = ""

    private fun check(template: Template, rotation: Rotation, origin: Long, level: Level): Match? {
        val ox = BlockPos.getX(origin)
        val oy = BlockPos.getY(origin)
        val oz = BlockPos.getZ(origin)
        var known = 0
        var matched = 0
        val cursor = BlockPos.MutableBlockPos()
        for ((pos, block) in template.blocks) {
            val turned = pos.rotate(rotation)
            cursor.set(ox + turned.x, oy + turned.y, oz + turned.z)
            if (!level.hasChunk(cursor.x shr 4, cursor.z shr 4)) continue
            known++
            if (level.getBlockState(cursor).block == block) matched++
        }
        lastCheck = "$matched/$known of ${template.blocks.size} match"
        if (known < template.blocks.size * 0.6 || matched < known * MIN_MATCH || matched < MIN_MATCHED_BLOCKS) return null
        val a = BlockPos.ZERO.rotate(rotation)
        val b = BlockPos(template.sizeX - 1, template.sizeY - 1, template.sizeZ - 1).rotate(rotation)
        val box = Box(
            ox + minOf(a.x, b.x), oy + minOf(a.y, b.y), oz + minOf(a.z, b.z),
            ox + maxOf(a.x, b.x), oy + maxOf(a.y, b.y), oz + maxOf(a.z, b.z),
        )
        return Match(template, box, matched, known)
    }

    private fun load(): List<Template> {
        val loaded = NAMES.mapNotNull { name ->
            try {
                val stream = ShipwreckFit::class.java.getResourceAsStream("/data/minecraft/structure/shipwreck/$name.nbt")
                    ?: return@mapNotNull null.also { Log.warn("Shipwreck template {} is not in the game jar", name) }
                val tag = stream.use { NbtIo.readCompressed(it, NbtAccounter.unlimitedHeap()) }
                parse(name, tag)
            } catch (e: Exception) {
                Log.error("Could not read shipwreck template $name", e)
                null
            }
        }
        Log.info("Loaded {} of {} shipwreck templates", loaded.size, NAMES.size)
        return loaded
    }

    private fun parse(name: String, tag: CompoundTag): Template {
        val size = tag.getListOrEmpty("size")
        val paletteTag = tag.getList("palette").orElseGet { tag.getListOrEmpty("palettes").getListOrEmpty(0) }
        val palette = (0 until paletteTag.size).map { i ->
            val id = paletteTag.getCompoundOrEmpty(i).getStringOr("Name", "minecraft:air")
            BuiltInRegistries.BLOCK.getValue(Identifier.parse(id))
        }
        val blocksTag = tag.getListOrEmpty("blocks")
        val blocks = ArrayList<Pair<BlockPos, Block>>()
        for (i in 0 until blocksTag.size) {
            val entry = blocksTag.getCompoundOrEmpty(i)
            val block = palette.getOrNull(entry.getIntOr("state", -1)) ?: continue
            if (block in IGNORED) continue
            val pos = entry.getListOrEmpty("pos")
            blocks.add(BlockPos(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0)) to block)
        }
        return Template(name, size.getIntOr(0, 0), size.getIntOr(1, 0), size.getIntOr(2, 0), blocks)
    }
}
