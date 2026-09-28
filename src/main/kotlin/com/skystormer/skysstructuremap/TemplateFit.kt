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
    /**
     * Only these kinds vote, when some of a structure's blocks are far too common to be worth
     * voting with: a bastion is thousands of polished blackstone bricks, and pairing each of them
     * with every brick in a design would be slow and say little, while its stairs, chiselled and
     * gilded blocks are few and in known places. Everything is still checked block for block.
     */
    private val votingKinds: Set<Block> = emptySet(),
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

        /** How many of the best-voted placements are checked block for block. */
        private const val MAX_CHECKS = 24

        /**
         * How much of a design must be there, of the blocks in loaded chunks: real ones are often
         * broken or built over (the first wreck tested had 405 of 598), so half will do, as long as
         * the placement is one nearly every block seen agrees on, which chance never manages.
         * Blocks added to it do not count against it.
         */
        private const val MIN_MATCH = 0.5
        private const val MIN_MATCHED_BLOCKS = 60

        /** How many seen blocks of a kind are tried as the anchor, spread through those seen. */
        private const val SEEDS = 6

        /** A kind in more spots than this of a design says too little about where it sits. */
        private const val MAX_SPOTS = 20

        /** How many placements one anchored search may check block for block. */
        private const val CHECK_BUDGET = 2000

        /** Of a design smaller than that, this share of it is enough. */
        private const val MIN_MATCHED_SHARE = 0.5
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
    /**
     * The design that fits with one of these blocks standing exactly where one was seen, or null.
     *
     * Where a structure has blocks that only sit in a few spots of a design, each of those says
     * where the design is by itself, with nothing to vote on: a village's town centre has one bell,
     * and a lantern, a trapdoor or a few blocks of packed ice when the bell has been taken. Every
     * design and turn is tried with such a block on a seen one and checked block for block. The
     * rarer a block is in a design, the fewer placements it means, so those are tried first and the
     * work is capped ([CHECK_BUDGET]).
     */
    fun fitAnchored(detection: Detection, level: Level, kinds: List<Block>): Match? {
        var budget = CHECK_BUDGET
        val report = ArrayList<Triple<String, Int, Int>>()
        for (kind in kinds) {
            val seen = detection.blocks.long2ObjectEntrySet().filter { it.value == kind }.map { it.longKey }
            if (seen.isEmpty()) continue
            val step = maxOf(1, seen.size / SEEDS)
            val anchors = seen.indices.step(step).map { seen[it] }
            val work = templates.mapNotNull { template ->
                val spots = template.byFamily[family(kind)].orEmpty()
                if (spots.isEmpty() || spots.size > MAX_SPOTS) null else template to spots
            }.sortedBy { it.second.size }
            var best: Match? = null
            for ((template, spots) in work) {
                for (anchor in anchors) {
                    val x = BlockPos.getX(anchor)
                    val y = BlockPos.getY(anchor)
                    val z = BlockPos.getZ(anchor)
                    for (spot in spots) {
                        for (rotation in Rotation.entries) {
                            if (budget-- <= 0) break
                            val turned = spot.rotate(rotation)
                            val match = check(template, rotation, BlockPos.asLong(x - turned.x, y - turned.y, z - turned.z), level)
                                ?: continue
                            report.add(Triple("${template.name} $rotation", match.matched, match.known))
                            if (best == null || match.matched > best.matched) best = match
                        }
                    }
                }
            }
            if (best != null) {
                lastReport = "anchored on ${BuiltInRegistries.BLOCK.getKey(kind).path}; " +
                    report.sortedByDescending { it.second }.take(3).joinToString("; ") { "${it.first}: ${it.second}/${it.third}" }
                return best
            }
        }
        lastReport = "nothing fits any of ${kinds.joinToString { BuiltInRegistries.BLOCK.getKey(it).path }} that were seen"
        return null
    }

    fun fit(detection: Detection, level: Level, votes: (Long) -> Boolean = { true }): Match? {
        if (templates.isEmpty() || detection.blocks.size < MIN_BLOCKS) {
            lastReport = "${detection.blocks.size} blocks, too few"
            return null
        }
        // An even spread of the blocks seen, so one end of a wreck cannot outvote the rest.
        val keys = detection.blocks.keys.toLongArray()
            .filter { votes(it) && (votingKinds.isEmpty() || detection.blocks.get(it) in votingKinds) }
            .toLongArray().also { it.sort() }
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
        // The best-voted placements are checked: a design heavy with paths can outvote the right
        // one, but checking hundreds of them block for block would hold up a frame.
        for (candidate in candidates.sortedByDescending { it.votes }.take(MAX_CHECKS)) {
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
        // Enough blocks to mean something, but never more than most of the design: a taiga village's
        // meeting point is only 72 blocks, and asking for 60 matched threw away a real one at 49.
        val enough = minOf(MIN_MATCHED_BLOCKS, (template.blocks.size * MIN_MATCHED_SHARE).toInt())
        if (known < template.blocks.size * 0.6 || matched < known * MIN_MATCH || matched < enough) return null
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

/**
 * A bastion remnant's ramparts, walls and bridge, for each of the four kinds the game builds
 * (units, hoglin stable, treasure room, bridge). Only their stairs, slabs, walls and chiselled
 * blocks vote: a bastion is thousands of polished blackstone bricks, which say little about where a
 * design sits and would be slow to pair up. Gilded blackstone never votes either, because the game
 * scatters that itself, one blackstone block in a hundred.
 *
 * What the game changes as it builds one is allowed for: it cracks three in ten of the polished
 * bricks, and turns half the gilded blackstone back into plain blackstone (and one blackstone in a
 * hundred into gilded), so those pairs count as the same block.
 */
object BastionFit : TemplateFit(
    "bastion",
    listOf(
        "units/ramparts/ramparts_0", "units/ramparts/ramparts_1", "units/walls/wall_base",
        "hoglin_stable/ramparts/ramparts_1", "hoglin_stable/ramparts/ramparts_2",
        "hoglin_stable/walls/side_wall_0", "hoglin_stable/walls/side_wall_1", "hoglin_stable/walls/wall_base",
        "bridge/starting_pieces/entrance", "bridge/ramparts/rampart_0", "bridge/ramparts/rampart_1",
        "bridge/bridge_pieces/bridge",
        "treasure/ramparts/mid_wall_main", "treasure/ramparts/mid_wall_side", "treasure/ramparts/top_wall",
        "treasure/walls/bottom/wall_1",
    ),
    anyWood = false,
    agreement = 0.2,
    samples = 300,
    aliases = mapOf(
        "cracked_polished_blackstone_bricks" to "polished_blackstone_bricks",
        "gilded_blackstone" to "blackstone",
    ),
    minVotes = 5,
    votingKinds = setOf(
        Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Blocks.POLISHED_BLACKSTONE_BRICK_SLAB,
        Blocks.POLISHED_BLACKSTONE_BRICK_WALL, Blocks.CHISELED_POLISHED_BLACKSTONE,
    ),
)

/**
 * An ancient city's middle, where its frame of reinforced deepslate stands, and the buildings and
 * paths around it. Only the uncommon blocks vote: a city is tens of thousands of deepslate bricks
 * and tiles, which say little about where a design sits.
 */
object AncientCityFit : TemplateFit(
    "ancient_city",
    listOf(
        "city_center/city_center_1", "city_center/city_center_2", "city_center/city_center_3",
        "city/entrance/entrance_path_1", "city/entrance/entrance_path_2", "city/entrance/entrance_path_3",
        "city/entrance/entrance_path_4", "city/entrance/entrance_path_5", "city/entrance/entrance_connector",
        "structures/sauna_1", "structures/barracks", "structures/large_ruin_1", "structures/medium_ruin_1",
        "structures/small_statue", "structures/tall_ruin_1", "structures/ice_box_1",
    ),
    anyWood = false,
    agreement = 0.2,
    samples = 300,
    aliases = mapOf(
        "cracked_deepslate_bricks" to "deepslate_bricks",
        "cracked_deepslate_tiles" to "deepslate_tiles",
    ),
    minVotes = 5,
    votingKinds = setOf(
        Blocks.CHISELED_DEEPSLATE, Blocks.REINFORCED_DEEPSLATE, Blocks.DEEPSLATE_TILE_WALL,
        Blocks.DEEPSLATE_BRICK_WALL, Blocks.POLISHED_DEEPSLATE_WALL, Blocks.DEEPSLATE_BRICK_SLAB,
    ),
)

/**
 * A woodland mansion's rooms: its entrance hall and the big two-by-two rooms it is built from. A
 * player's dark oak house has the same planks, but not a room laid out as the game lays it.
 */
object MansionFit : TemplateFit(
    "woodland_mansion",
    listOf(
        "entrance", "2x2_a1", "2x2_a2", "2x2_a3", "2x2_a4", "2x2_b1", "2x2_b2", "2x2_b3", "2x2_b4", "2x2_b5",
        "2x2_s1", "1x2_c_stairs", "1x2_d_stairs", "1x1_b1", "1x1_b2", "1x1_b3", "1x1_b4", "1x1_b5",
        "1x2_a1", "1x2_b1", "1x2_c1", "1x2_d1", "1x2_se1",
    ),
    anyWood = false,
    agreement = 0.2,
    samples = 400,
    minVotes = 15,
    votingKinds = setOf(
        Blocks.CARPET.red(), Blocks.POLISHED_ANDESITE, Blocks.WOOL.lightGray(), Blocks.WOOL.black(),
        Blocks.BOOKSHELF, Blocks.BIRCH_STAIRS, Blocks.DARK_OAK_STAIRS,
    ),
)

/**
 * A village's working houses: the library, the smithy, the farm and the rest, for every kind of
 * village. Each has its own job block in one or two spots, which says where the house sits, so a
 * village is still known by a house when its town centre has been pulled down.
 */
object VillageHouseFit : TemplateFit(
    "village",
    listOf(
        "desert/houses/desert_armorer_1",
        "desert/houses/desert_butcher_shop_1",
        "desert/houses/desert_cartographer_house_1",
        "desert/houses/desert_farm_1",
        "desert/houses/desert_farm_2",
        "desert/houses/desert_fisher_1",
        "desert/houses/desert_fletcher_house_1",
        "desert/houses/desert_large_farm_1",
        "desert/houses/desert_library_1",
        "desert/houses/desert_mason_1",
        "desert/houses/desert_shepherd_house_1",
        "desert/houses/desert_tannery_1",
        "desert/houses/desert_tool_smith_1",
        "desert/houses/desert_weaponsmith_1",
        "plains/houses/plains_armorer_house_1",
        "plains/houses/plains_butcher_shop_1",
        "plains/houses/plains_butcher_shop_2",
        "plains/houses/plains_cartographer_1",
        "plains/houses/plains_fisher_cottage_1",
        "plains/houses/plains_fletcher_house_1",
        "plains/houses/plains_large_farm_1",
        "plains/houses/plains_library_1",
        "plains/houses/plains_library_2",
        "plains/houses/plains_masons_house_1",
        "plains/houses/plains_shepherds_house_1",
        "plains/houses/plains_small_farm_1",
        "plains/houses/plains_tannery_1",
        "plains/houses/plains_tool_smith_1",
        "plains/houses/plains_weaponsmith_1",
        "savanna/houses/savanna_armorer_1",
        "savanna/houses/savanna_butchers_shop_1",
        "savanna/houses/savanna_butchers_shop_2",
        "savanna/houses/savanna_cartographer_1",
        "savanna/houses/savanna_fisher_cottage_1",
        "savanna/houses/savanna_fletcher_house_1",
        "savanna/houses/savanna_large_farm_1",
        "savanna/houses/savanna_large_farm_2",
        "savanna/houses/savanna_library_1",
        "savanna/houses/savanna_mason_1",
        "savanna/houses/savanna_shepherd_1",
        "savanna/houses/savanna_small_farm",
        "savanna/houses/savanna_tannery_1",
        "savanna/houses/savanna_tool_smith_1",
        "savanna/houses/savanna_weaponsmith_1",
        "savanna/houses/savanna_weaponsmith_2",
        "snowy/houses/snowy_armorer_house_1",
        "snowy/houses/snowy_armorer_house_2",
        "snowy/houses/snowy_butchers_shop_1",
        "snowy/houses/snowy_butchers_shop_2",
        "snowy/houses/snowy_cartographer_house_1",
        "snowy/houses/snowy_farm_1",
        "snowy/houses/snowy_farm_2",
        "snowy/houses/snowy_fisher_cottage",
        "snowy/houses/snowy_fletcher_house_1",
        "snowy/houses/snowy_library_1",
        "snowy/houses/snowy_masons_house_1",
        "snowy/houses/snowy_masons_house_2",
        "snowy/houses/snowy_shepherds_house_1",
        "snowy/houses/snowy_tannery_1",
        "snowy/houses/snowy_tool_smith_1",
        "taiga/houses/taiga_armorer_2",
        "taiga/houses/taiga_armorer_house_1",
        "taiga/houses/taiga_butcher_shop_1",
        "taiga/houses/taiga_cartographer_house_1",
        "taiga/houses/taiga_fisher_cottage_1",
        "taiga/houses/taiga_fletcher_house_1",
        "taiga/houses/taiga_large_farm_1",
        "taiga/houses/taiga_large_farm_2",
        "taiga/houses/taiga_library_1",
        "taiga/houses/taiga_masons_house_1",
        "taiga/houses/taiga_shepherds_house_1",
        "taiga/houses/taiga_small_farm_1",
        "taiga/houses/taiga_tannery_1",
        "taiga/houses/taiga_tool_smith_1",
        "taiga/houses/taiga_weaponsmith_1",
        "taiga/houses/taiga_weaponsmith_2",
    ),
    anyWood = false,
)
