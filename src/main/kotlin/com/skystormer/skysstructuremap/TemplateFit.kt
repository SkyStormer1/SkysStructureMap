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
 * Recognises a structure by matching the blocks seen against the game's own designs for it (its
 * templates, inside the Minecraft jar): shipwrecks, and pillager outposts' watchtowers. The game
 * places them turned (never mirrored) and with few or no blocks changed, so a real one matches a
 * design almost block for block, which a player's build never does, and a match gives its exact box.
 *
 * Matching is by voting: every seen block, paired with every block of the same kind in a
 * template, says where that template would have to start; the true start gets a vote from nearly
 * every block. The few best-voted starts are then checked block by block against the world.
 */
open class TemplateFit(
    /** The folder under `data/minecraft/structure/` its designs are in. */
    private val folder: String,
    val names: List<String>,
    /**
     * Match shapes whatever the wood: shipwrecks are built in eight woods from one design, while a
     * watchtower is always the same woods and is matched block for block.
     */
    private val anyWood: Boolean,
    /**
     * How many of the blocks voting must agree on one placement: most for a lone structure like a
     * wreck; fewer for a village's town centre, whose streets run right up to it and vote too.
     * Every placement is then checked block for block just the same.
     */
    private val agreement: Double = MIN_AGREEMENT,
    /** How many of the blocks seen vote: more where most of them are not part of the design. */
    private val samples: Int = SAMPLES,
    /**
     * Blocks the game may swap in as it places a design, counted as the one they replace: trail
     * ruins turn some gravel to dirt, coarse dirt or suspicious gravel and some mud bricks to packed mud.
     */
    private val aliases: Map<String, String> = emptyMap(),
    /**
     * Kinds that could be there anyway (gravel and dirt in the ground a trail ruins is buried in):
     * the rest of a design must match on its own as well, so a spot in plain ground never passes.
     */
    private val loose: Set<String> = emptySet(),
    /** Enough agreeing votes whatever the share, for designs that are a small part of what is seen. */
    private val minVotes: Int = Int.MAX_VALUE,
) {

    /**
     * One shipwreck design. Its blocks are kept by [family] (stairs, planks, fence…) rather than by
     * wood: the game builds each design in eight woods (a template holds eight palettes), and
     * matching the shape alone finds every one of them.
     */
    class Template(val name: String, val sizeX: Int, val sizeY: Int, val sizeZ: Int, val blocks: List<Pair<BlockPos, String>>, val woods: Set<Block>) {
        val byFamily: Map<String, List<BlockPos>> = blocks.groupBy({ it.second }, { it.first })
    }

    private val families = HashMap<Block, String>()

    /** What a block is matched as: without its wood (`dark_oak_stairs` and `spruce_stairs` are both `stairs`) when [anyWood]. */
    fun family(block: Block): String = families.getOrPut(block) {
        var path = BuiltInRegistries.BLOCK.getKey(block).path
        if (anyWood) for (wood in WOODS) path = path.removePrefix(wood)
        aliases[path] ?: path
    }

    companion object {
        /** At least this many blocks before trying, so a lone plank in the sea is not worth the work. */
        const val MIN_BLOCKS = 12

        /**
         * How much of a design must be there, of the blocks in loaded chunks: real ones are often
         * broken or built over (the first wreck tested had 405 of 598), so half will do, as long as
         * the placement is one nearly every block seen agrees on, which chance never manages.
         * Blocks added to it do not count against it.
         */
        private const val MIN_MATCH = 0.5
        private const val MIN_MATCHED_BLOCKS = 60
        const val MIN_AGREEMENT = 0.8

        const val SAMPLES = 24

        /** How many of the best-voted starts for each design and turn are checked. */
        private const val TOP_STARTS = 3

        private val WOODS = listOf("stripped_", "dark_oak_", "pale_oak_", "oak_", "spruce_", "birch_", "jungle_", "acacia_", "mangrove_", "cherry_", "bamboo_")
    }

    /** Blocks the game leaves out when placing a template, or that could be anywhere in the sea. */
    private val IGNORED = setOf(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR, Blocks.STRUCTURE_BLOCK, Blocks.STRUCTURE_VOID, Blocks.JIGSAW, Blocks.WATER)

    val templates: List<Template> by lazy { load() }

    /** Every kind of block that is in some shipwreck: what chunks are searched for. */
    val signatureBlocks: Set<Block> by lazy {
        templates.flatMapTo(HashSet()) { it.woods }.also { blocks ->
            Log.info("Searching for {} kinds of {} block: {}", blocks.size, folder,
                blocks.map { BuiltInRegistries.BLOCK.getKey(it).path }.sorted().joinToString(", "))
        }
    }

    class Match(val template: Template, val box: Box, val matched: Int, val known: Int)

    /** Why the last [fit] came out as it did, for the log. */
    var lastReport = ""
        private set

    /**
     * The best-matching placement of one of the designs for [detection]'s blocks, or null for
     * none. Only blocks [votes] accepts help choose it; every block of the design is checked after.
     */
    fun fit(detection: Detection, level: Level, votes: (Long) -> Boolean = { true }): Match? {
        if (templates.isEmpty() || detection.blocks.size < MIN_BLOCKS) {
            lastReport = "${detection.blocks.size} blocks, too few"
            return null
        }
        // An even spread of the blocks seen, so one end of a wreck cannot outvote the rest.
        val keys = detection.blocks.keys.toLongArray().filter(votes).toLongArray().also { it.sort() }
        if (keys.size < MIN_BLOCKS) {
            lastReport = "${keys.size} blocks that may vote, too few"
            return null
        }
        val step = maxOf(1, keys.size / this.samples)
        val samples = (keys.indices step step).map { keys[it] }

        class Candidate(val template: Template, val rotation: Rotation, val origin: Long, val votes: Int)
        val candidates = ArrayList<Candidate>()
        for (template in templates) {
            for (rotation in Rotation.entries) {
                val votes = Long2IntOpenHashMap()
                for (key in samples) {
                    val block = detection.blocks.get(key) ?: continue
                    val local = template.byFamily[family(block)] ?: continue
                    val x = BlockPos.getX(key)
                    val y = BlockPos.getY(key)
                    val z = BlockPos.getZ(key)
                    for (pos in local) {
                        val turned = pos.rotate(rotation)
                        votes.addTo(BlockPos.asLong(x - turned.x, y - turned.y, z - turned.z), 1)
                    }
                }
                // The few best-voted starts, not just the best: other blocks nearby (a village's
                // streets) can put the true one second or third.
                val top = ArrayList<Pair<Long, Int>>(TOP_STARTS + 1)
                val iterator = votes.long2IntEntrySet().fastIterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.intValue < minOf(samples.size * agreement, minVotes.toDouble())) continue
                    if (top.size < TOP_STARTS || entry.intValue > top.last().second) {
                        top.add(entry.longKey to entry.intValue)
                        top.sortByDescending { it.second }
                        if (top.size > TOP_STARTS) top.removeAt(top.size - 1)
                    }
                }
                for ((origin, count) in top) candidates.add(Candidate(template, rotation, origin, count))
            }
        }
        val bestVotes = candidates.maxOfOrNull { it.votes } ?: 0
        lastReport = "${detection.blocks.size} blocks, ${samples.size} samples, ${candidates.size} placements most samples agree on (best $bestVotes)"
        if (candidates.isEmpty()) {
            // Which kinds voted, and whether any design has them at all: what to look at when nothing fits.
            val kinds = samples.mapNotNull { detection.blocks.get(it)?.let(::family) }.groupingBy { it }.eachCount()
            lastReport += "; voting kinds ${kinds.entries.joinToString { "${it.key}×${it.value}" + if (templates.none { t -> it.key in t.byFamily }) " (in no design)" else "" }}"
        }
        if (candidates.isEmpty()) return null

        var best: Match? = null
        // Every well-voted placement is checked: a design heavy with paths can outvote the right one.
        for (candidate in candidates.sortedByDescending { it.votes }) {
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
        var knownFirm = 0
        var matchedFirm = 0
        val cursor = BlockPos.MutableBlockPos()
        for ((pos, block) in template.blocks) {
            val turned = pos.rotate(rotation)
            cursor.set(ox + turned.x, oy + turned.y, oz + turned.z)
            if (!level.hasChunk(cursor.x shr 4, cursor.z shr 4)) continue
            known++
            val hit = family(level.getBlockState(cursor).block) == block
            if (hit) matched++
            if (block !in loose) {
                knownFirm++
                if (hit) matchedFirm++
            }
        }
        lastCheck = "$matched/$known of ${template.blocks.size} match" + if (loose.isNotEmpty()) " ($matchedFirm/$knownFirm without ${loose.joinToString()})" else ""
        if (known < template.blocks.size * 0.6 || matched < known * MIN_MATCH || matched < MIN_MATCHED_BLOCKS) return null
        if (loose.isNotEmpty() && matchedFirm < knownFirm * MIN_MATCH) return null
        val a = BlockPos.ZERO.rotate(rotation)
        val b = BlockPos(template.sizeX - 1, template.sizeY - 1, template.sizeZ - 1).rotate(rotation)
        val box = Box(
            ox + minOf(a.x, b.x), oy + minOf(a.y, b.y), oz + minOf(a.z, b.z),
            ox + maxOf(a.x, b.x), oy + maxOf(a.y, b.y), oz + maxOf(a.z, b.z),
        )
        return Match(template, box, matched, known)
    }

    private fun load(): List<Template> {
        val loaded = names.mapNotNull { name ->
            try {
                val stream = TemplateFit::class.java.getResourceAsStream("/data/minecraft/structure/$folder/$name.nbt")
                    ?: return@mapNotNull null.also { Log.warn("Template {}/{} is not in the game jar", folder, name) }
                val tag = stream.use { NbtIo.readCompressed(it, NbtAccounter.unlimitedHeap()) }
                parse(name, tag)
            } catch (e: Exception) {
                Log.error("Could not read template $folder/$name", e)
                null
            }
        }
        Log.info("Loaded {} of {} {} templates", loaded.size, names.size, folder)
        return loaded
    }

    private fun parse(name: String, tag: CompoundTag): Template {
        val size = tag.getListOrEmpty("size")
        // One palette, or several (the same design in different woods); the first gives the shape.
        val palettes = tag.getList("palette").map { listOf(it) }.orElseGet {
            val many = tag.getListOrEmpty("palettes")
            (0 until many.size).map { many.getListOrEmpty(it) }
        }
        val blockPalettes = palettes.map { paletteTag ->
            (0 until paletteTag.size).map { i ->
                BuiltInRegistries.BLOCK.getValue(Identifier.parse(paletteTag.getCompoundOrEmpty(i).getStringOr("Name", "minecraft:air")))
            }
        }
        val palette = blockPalettes.firstOrNull() ?: emptyList()
        val blocksTag = tag.getListOrEmpty("blocks")
        val blocks = ArrayList<Pair<BlockPos, String>>()
        for (i in 0 until blocksTag.size) {
            val entry = blocksTag.getCompoundOrEmpty(i)
            val block = palette.getOrNull(entry.getIntOr("state", -1)) ?: continue
            if (block in IGNORED) continue
            val pos = entry.getListOrEmpty("pos")
            blocks.add(BlockPos(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0)) to family(block))
        }
        val woods = blockPalettes.flatten().filterTo(HashSet()) { it !in IGNORED }
        return Template(name, size.getIntOr(0, 0), size.getIntOr(1, 0), size.getIntOr(2, 0), blocks, woods)
    }
}

/** The game's 20 shipwreck designs, each in eight woods. */
object ShipwreckFit : TemplateFit(
    "shipwreck",
    listOf(
        "with_mast", "with_mast_degraded",
        "rightsideup_full", "rightsideup_full_degraded", "rightsideup_fronthalf", "rightsideup_fronthalf_degraded",
        "rightsideup_backhalf", "rightsideup_backhalf_degraded",
        "sideways_full", "sideways_full_degraded", "sideways_fronthalf", "sideways_fronthalf_degraded",
        "sideways_backhalf", "sideways_backhalf_degraded",
        "upsidedown_full", "upsidedown_full_degraded", "upsidedown_fronthalf", "upsidedown_fronthalf_degraded",
        "upsidedown_backhalf", "upsidedown_backhalf_degraded",
    ),
    anyWood = true,
)

/**
 * A pillager outpost's watchtower, plain or overgrown. Its birch and dark oak planks, fences and
 * white banners are what a player might build with too (a house was taken for an outpost), but not
 * the tower itself.
 */
object WatchtowerFit : TemplateFit(
    "pillager_outpost", listOf("watchtower", "watchtower_overgrown"), anyWood = false,
    // Many voters, few needing to agree: a group can take in a player's build or a griefed tower's
    // leftovers beside it, and 25 voters spread over all that never agreed on the tower in testing.
    agreement = 0.2, samples = 400, minVotes = 25,
)

/**
 * A village's town centre, the meeting point or fountain with its bell that every village grows
 * from, for each kind of village. A player's base can have paths, beds, a bell and job blocks, but
 * not one of these laid out as the game lays them.
 */
object TownCentreFit : TemplateFit(
    "village",
    listOf(
        "plains/town_centers/plains_fountain_01", "plains/town_centers/plains_meeting_point_1",
        "plains/town_centers/plains_meeting_point_2", "plains/town_centers/plains_meeting_point_3",
        "desert/town_centers/desert_meeting_point_1", "desert/town_centers/desert_meeting_point_2",
        "desert/town_centers/desert_meeting_point_3",
        "savanna/town_centers/savanna_meeting_point_1", "savanna/town_centers/savanna_meeting_point_2",
        "savanna/town_centers/savanna_meeting_point_3", "savanna/town_centers/savanna_meeting_point_4",
        "snowy/town_centers/snowy_meeting_point_1", "snowy/town_centers/snowy_meeting_point_2",
        "snowy/town_centers/snowy_meeting_point_3",
        "taiga/town_centers/taiga_meeting_point_1", "taiga/town_centers/taiga_meeting_point_2",
    ),
    anyWood = false,
    agreement = 0.2,
    samples = 400,
)

/**
 * The same town centres, for a village whose bell is gone. Only paths are left to vote with, and a
 * path votes for every path in a design, so fewer need to agree; what matches is checked as closely.
 */
object BelllessTownCentreFit : TemplateFit(
    "village", TownCentreFit.names, anyWood = false, agreement = 0.2, samples = 400, minVotes = 15,
)

/**
 * The tower every trail ruins grows from, in its five designs. They are mostly gravel, coloured and
 * glazed terracotta and bricks, and buried; the houses and roads around them vote too, hence the
 * low agreement, with every placement checked block for block.
 */
object TrailRuinsFit : TemplateFit(
    "trail_ruins",
    (1..5).map { "tower/tower_$it" },
    anyWood = false,
    agreement = 0.2,
    samples = 400,
    aliases = mapOf("dirt" to "gravel", "coarse_dirt" to "gravel", "suspicious_gravel" to "gravel", "packed_mud" to "mud_bricks"),
    loose = setOf("gravel"),
    minVotes = 25,
)

/**
 * An end city's pieces: every city starts from its base floor, and the game places each piece as
 * designed. Only the rooms, towers and ship: roofs and bridges are plain purpur a player could lay.
 */
object EndCityFit : TemplateFit(
    "end_city",
    listOf(
        "base_floor", "second_floor_1", "second_floor_2", "third_floor_1", "third_floor_2", "tower_base", "tower_top",
        "fat_tower_base", "fat_tower_middle", "fat_tower_top", "ship",
    ),
    anyWood = false,
    agreement = 0.2,
    samples = 400,
    minVotes = 25,
)
