package com.skystormer.skysstructuremap

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState

/**
 * Recognises a structure by matching the blocks seen against the game's own designs for it (its
 * templates, inside the Minecraft jar): shipwrecks, outposts' watchtowers, villages, trail ruins,
 * end cities, bastions, ancient cities and mansions (the designs are in `Fits`). The game
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
    val votingKinds: Set<Block> = emptySet(),
    /**
     * The game may also place it mirrored, front to back, as it does ruined portals: each design is
     * tried that way too, as a design of its own.
     */
    private val mirrored: Boolean = false,
    /**
     * Checks the best-voted placements of every design and turn, and keeps the closest fit, rather
     * than the most-voted placements and the fit with the most blocks: where one design is far
     * bigger than the rest, as a giant ruined portal is, it outvotes and outmatches the right small one.
     */
    private val closest: Boolean = false,
    /** How many of its blocks must be seen before it is worth trying ([MIN_BLOCKS] unless a design is that small). */
    val minBlocks: Int = MIN_BLOCKS,
    /**
     * What most of a design's open space (the air it holds) must be in the world, at a height, for
     * designs whose open space says what they are: an igloo's inside is air, and an ocean ruin
     * stands in the sea, with the sky over it where it reaches the surface. Otherwise only its
     * blocks are checked, and a solid drift of snow matched an igloo's, and stretches of a
     * stronghold's corridors deep under the sea floor a small ruin's.
     */
    private val open: ((BlockState, Int) -> Boolean)? = null,
    /**
     * Only the top of each column of its open space need be [open], or the first block over it
     * that is: an ocean ruin's open space is the sea down to the floor and the ground the game left
     * below that, and ones buried in the floor in testing had sand or gravel over
     * them, or the ores anti-xray shows for what is buried, and the sea a few blocks up; or else
     * some of the column below it, where the design stands under a ledge.
     */
    private val openTopOnly: Boolean = false,
    /**
     * What a design stands in, when it is always laid there: for an ocean ruin, the sea. A quarter of
     * its open space at least must be that, at the block or over a column's top ([openTopOnly]), not
     * only [open]: a small warm ruin is a dozen blocks of cut sandstone and granite over sand, which
     * counts as the floor and so either way, and laid on dry land above the sea, where air is open
     * too, two passed on 18 of their 71 blocks, one in a desert village.
     */
    private val sea: ((BlockState) -> Boolean)? = null,
    /** Where a design laid with its corner at the given spot may stand out of [sea] all the same. */
    private val outOfSea: ((Long, BlockSource) -> Boolean)? = null,
) {

    /**
     * One shipwreck design. Its blocks are kept by [family] (stairs, planks, fence…) rather than by
     * wood: the game builds each design in eight woods (a template holds eight palettes), and
     * matching the shape alone finds every one of them.
     */
    class Template(
        val name: String, val sizeX: Int, val sizeY: Int, val sizeZ: Int, val blocks: List<Pair<BlockPos, String>>, val woods: Set<Block>,
        /**
         * Where a village house joins its street: the jigsaw named `building_entrance`, and the way
         * it faces, towards the street. Empty for designs that are not village houses.
         */
        val entrances: List<Pair<BlockPos, Direction>> = emptyList(),
        /** Where the design holds air: its open space, for the designs that say what that must be ([open]). */
        val air: List<BlockPos> = emptyList(),
    ) {
        val byFamily: Map<String, List<BlockPos>> = blocks.groupBy({ it.second }, { it.first })
    }

    /** Read from the scan of Bobby's cache as well as on the main thread, so safe for both. */
    private val families = java.util.concurrent.ConcurrentHashMap<Block, String>()

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
        const val MIN_MATCH = 0.5
        private const val MIN_MATCHED_BLOCKS = 60

        /** How many seen blocks of a kind are tried as the anchor, spread through those seen. */
        private const val ANCHORS_TRIED = 6

        /** A kind in more spots than this of a design says too little about where it sits. */
        private const val MAX_SPOTS = 20

        /** How many placements one anchored search may check block for block. */
        private const val CHECK_BUDGET = 2000

        /** At least this much of a design must be seen as it is, whatever anti-xray hides. */
        private const val MIN_SEEN_SHARE = 0.25

        /**
         * What Paper's anti-xray shows in place of blocks nobody has uncovered (its default hidden
         * blocks, which it also scatters over buried stone): a template spot showing one of these, and
         * not the block the design has there, says nothing either way. In testing the
         * buried half of every ocean ruin is copper, iron, gold, lapis, redstone and diamond ore.
         */
        private val DISGUISES = setOf(
            Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.COPPER_ORE,
            Blocks.DEEPSLATE_COPPER_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.DIAMOND_ORE,
            Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.RAW_IRON_BLOCK, Blocks.RAW_COPPER_BLOCK, Blocks.RAW_GOLD_BLOCK,
            Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS, Blocks.CHEST, Blocks.ENDER_CHEST,
        )

        /** How far over the top of an open column, through what settled or anti-xray put over it, it may be open instead ([openTopOnly]). */
        private const val OPEN_ABOVE = 8

        /**
         * Its own blocks in its open space may be no more than one for this many that matched: small
         * ruins in a cluster in testing had up to one for every two from the ruin
         * beside them, and a player's wall twice as many as matched.
         */
        private const val OWN_IN_OPEN = 2

        /** At least one in this many of a design's open space must be [sea], for designs laid in it. */
        private const val IN_SEA = 4

        /** Of a design smaller than that, this share of it is enough. */
        private const val MIN_MATCHED_SHARE = 0.5
        const val MIN_AGREEMENT = 0.8

        const val SAMPLES = 24

        /** Fits this much apart in share are as close as each other (see [closest]). */
        private const val CLOSE = 0.05

        /** How many of the best-voted starts for each design and turn are checked, and the most when more tie with the last. */
        private const val TOP_STARTS = 3
        private const val MAX_TIED = 8

        private const val BUILDING_ENTRANCE = "minecraft:building_entrance"

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

    class Match(val template: Template, val box: Box, val matched: Int, val known: Int, val rotation: Rotation, val origin: Long) {

        /** How much of what could be checked matched. */
        val share: Double get() = if (known == 0) 0.0 else matched.toDouble() / known

        /** Where the design's own corner (its block 0, 0, 0) lies in its chunk, x to z. */
        val inChunk: Pair<Int, Int> get() = Math.floorMod(BlockPos.getX(origin), 16) to Math.floorMod(BlockPos.getZ(origin), 16)

        /** The design's [Template.entrances] where it was placed: each in the world, and the way it faces. */
        fun entrances(): List<Pair<BlockPos, Direction>> = template.entrances.map { (pos, facing) ->
            val turned = pos.rotate(rotation)
            BlockPos(BlockPos.getX(origin) + turned.x, BlockPos.getY(origin) + turned.y, BlockPos.getZ(origin) + turned.z) to rotation.rotate(facing)
        }
    }

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
    fun fitAnchored(
        detection: Detection, level: BlockSource, kinds: List<Block>,
        minMatch: Double = MIN_MATCH, accept: (Match) -> String? = { null },
    ): Match? {
        var budget = CHECK_BUDGET
        val report = ArrayList<Triple<String, Int, Int>>()
        for (kind in kinds) {
            // A door is two blocks, one on the other: only its bottom half is tried, in the world
            // and in the designs, or every placement would be tried twice over from each side.
            val tall = kind is DoorBlock
            val seen = detection.blocks.long2ObjectEntrySet()
                .filter { it.value == kind && !(tall && detection.blocks.get(BlockPos.offset(it.longKey, Direction.DOWN)) == kind) }
                .map { it.longKey }
            if (seen.isEmpty()) continue
            val step = maxOf(1, seen.size / ANCHORS_TRIED)
            val anchors = seen.indices.step(step).map { seen[it] }
            var best: Match? = null
            // Each anchor through every design before the next, so a real one is tried in full
            // even when the budget runs out.
            for (anchor in anchors) {
                if (budget <= 0) break
                val left = intArrayOf(budget)
                fitAt(anchor, kind, level, minMatch, accept, left, report)?.let { if (best == null || it.share > best.share) best = it }
                budget = left[0]
            }
            if (best != null) {
                lastReport = "anchored on ${BuiltInRegistries.BLOCK.getKey(kind).path}; " +
                    report.sortedByDescending { it.second }.take(3).joinToString("; ") { "${it.first}: ${it.second}/${it.third}" }
                return best
            }
        }
        lastReport = if (report.isNotEmpty()) {
            "fits, but none accepted: " + report.sortedByDescending { it.second }.take(3).joinToString("; ") { "${it.first}: ${it.second}/${it.third}" }
        } else {
            "nothing fits any of ${kinds.joinToString { BuiltInRegistries.BLOCK.getKey(it).path }} that were seen"
        }
        return null
    }

    /**
     * The design that fits closest with a block of [kind] standing at [anchor], every design and turn
     * that has one in few enough places ([maxSpots]) tried ([fitAnchored]), or null; only placements
     * whose corner [origins] allows, [budget] of them at most, what is left put back.
     */
    fun fitAt(
        anchor: Long, kind: Block, level: BlockSource, minMatch: Double = MIN_MATCH, accept: (Match) -> String? = { null },
        budget: IntArray = intArrayOf(Int.MAX_VALUE), report: MutableList<Triple<String, Int, Int>>? = null,
        origins: (Template, Rotation, Long) -> Boolean = { _, _, _ -> true }, maxSpots: Int = MAX_SPOTS,
    ): Match? {
        val x = BlockPos.getX(anchor)
        val y = BlockPos.getY(anchor)
        val z = BlockPos.getZ(anchor)
        var best: Match? = null
        for ((template, spots) in anchorSpots(kind)) {
            if (spots.size > maxSpots) continue
            for (spot in spots) {
                for (rotation in Rotation.entries) {
                    val turned = spot.rotate(rotation)
                    val origin = BlockPos.asLong(x - turned.x, y - turned.y, z - turned.z)
                    if (!origins(template, rotation, origin)) continue
                    if (budget[0]-- <= 0) return best
                    val match = check(template, rotation, origin, level, minMatch) ?: continue
                    val refused = accept(match)
                    report?.add(Triple("${template.name} $rotation" + (refused?.let { " ($it)" } ?: ""), match.matched, match.known))
                    // The closest fit, not the most blocks: a big design half fitting over
                    // a small house beside it is not the house.
                    if (refused == null && (best == null || match.share > best.share)) best = match
                }
            }
        }
        return best
    }

    /** Where each design has [kind], the designs with fewest such spots first. A door's bottom half only. */
    private fun anchorSpots(kind: Block): List<Pair<Template, List<BlockPos>>> = anchorSpots.getOrPut(kind) {
        val tall = kind is DoorBlock
        templates.mapNotNull { template ->
            val all = template.byFamily[family(kind)].orEmpty()
            val spots = if (tall) all.filter { it.below() !in all } else all
            if (spots.isEmpty()) null else template to spots
        }.sortedBy { it.second.size }
    }

    private val anchorSpots = java.util.concurrent.ConcurrentHashMap<Block, List<Pair<Template, List<BlockPos>>>>()

    fun fit(detection: Detection, level: BlockSource, votes: (Long) -> Boolean = { true }, accept: (Match) -> String? = { null }): Match? {
        if (templates.isEmpty() || detection.blocks.size < minBlocks) {
            lastReport = "${detection.blocks.size} blocks, too few"
            return null
        }
        // An even spread of the blocks seen, so one end of a wreck cannot outvote the rest.
        val keys = detection.blocks.keys.toLongArray()
            .filter { votes(it) && (votingKinds.isEmpty() || detection.blocks.get(it) in votingKinds) }
            .toLongArray().also { it.sort() }
        if (keys.size < minBlocks) {
            lastReport = "${keys.size} blocks that may vote, too few"
            return null
        }
        val step = maxOf(1, keys.size / this.samples)
        val samples = (keys.indices step step).map { keys[it] }

        class Candidate(val template: Template, val rotation: Rotation, val origin: Long, val votes: Int)
        val candidates = ArrayList<Candidate>()
        // One tally, emptied for each design and turn: made anew, it grew from nothing each time.
        val votes = Long2IntOpenHashMap()
        for (template in templates) {
            for (rotation in Rotation.entries) {
                votes.clear()
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
                // streets) can put the true one second or third. Those as well voted as the last of
                // them too, so which go on to be checked never rests on the order the tally keeps.
                val enough = minOf(samples.size * agreement, minVotes.toDouble())
                val best = IntArray(TOP_STARTS)
                val counts = votes.values.iterator()
                while (counts.hasNext()) {
                    val count = counts.nextInt()
                    if (count < enough || count <= best[TOP_STARTS - 1]) continue
                    var i = TOP_STARTS - 1
                    while (i > 0 && best[i - 1] < count) { best[i] = best[i - 1]; i-- }
                    best[i] = count
                }
                val last = maxOf(best[TOP_STARTS - 1], 1)
                val top = ArrayList<Pair<Long, Int>>()
                val iterator = votes.long2IntEntrySet().fastIterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.intValue >= enough && entry.intValue >= last) top.add(entry.longKey to entry.intValue)
                }
                top.sortWith(compareByDescending<Pair<Long, Int>> { it.second }.thenBy { it.first })
                for ((origin, count) in top.take(MAX_TIED)) {
                    candidates.add(Candidate(template, rotation, origin, count))
                }
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
        val checked = if (closest) candidates else candidates.sortedByDescending { it.votes }.take(MAX_CHECKS)
        for (candidate in checked) {
            val match = check(candidate.template, candidate.rotation, candidate.origin, level)
            val refused = match?.let(accept)
            lastReport += "; ${candidate.template.name} ${candidate.rotation}: " + (match?.let { "${it.matched}/${it.known}" } ?: lastCheck) +
                (refused?.let { " ($it)" } ?: "")
            if (match == null || refused != null) continue
            // A degraded wreck is a full one with blocks missing, so on an intact wreck both fit;
            // the one explaining more blocks is the right one.
            val better = when {
                best == null -> true
                // Within a few points, the one explaining more blocks: a small design fits inside a big one about as well.
                closest -> match.share > best.share + CLOSE || (match.share >= best.share - CLOSE && match.matched > best.matched)
                else -> match.matched > best.matched
            }
            if (better) best = match
        }
        return best
    }

    private var lastCheck = ""

    /** Why the design [name], turned [rotation] at [origin], fits or does not, as the log says it: for the tools that check fits. */
    fun explain(name: String, rotation: Rotation, origin: Long, level: BlockSource): String {
        val template = templates.firstOrNull { it.name == name } ?: return "no design $name"
        val match = check(template, rotation, origin, level)
        return (if (match != null) "fits " else "does not fit: ") + lastCheck
    }

    /** How much of the design [name] the world still has where the game laid it, turned [rotation] at [origin]: matched and checked, or null for none of these designs. */
    fun remaining(name: String, rotation: Rotation, origin: Long, level: BlockSource): Pair<Int, Int>? {
        val template = templates.firstOrNull { it.name == name } ?: return null
        check(template, rotation, origin, level, minMatch = 0.0)?.let { return it.matched to it.known }
        return Regex("""(\d+)/(\d+)""").find(lastCheck)?.groupValues?.let { it[1].toInt() to it[2].toInt() }
    }

    private fun check(template: Template, rotation: Rotation, origin: Long, level: BlockSource, minMatch: Double = MIN_MATCH): Match? {
        val ox = BlockPos.getX(origin)
        val oy = BlockPos.getY(origin)
        val oz = BlockPos.getZ(origin)
        var known = 0
        var matched = 0
        var disguised = 0
        var knownFirm = 0
        var matchedFirm = 0
        var firmSize = 0
        val cursor = BlockPos.MutableBlockPos()
        for ((pos, block) in template.blocks) {
            if (block !in loose) firmSize++
            val turned = pos.rotate(rotation)
            cursor.set(ox + turned.x, oy + turned.y, oz + turned.z)
            if (!level.hasChunk(cursor.x shr 4, cursor.z shr 4)) continue
            val seen = level.getBlockState(cursor).block
            val hit = family(seen) == block
            // Not seen at all: a server's anti-xray sends one of these in place of a block nobody has uncovered.
            if (!hit && seen in DISGUISES) {
                disguised++
                continue
            }
            known++
            if (hit) matched++
            if (block !in loose) {
                knownFirm++
                if (hit) matchedFirm++
            }
        }
        lastCheck = "$matched/$known of ${template.blocks.size} match" + (if (disguised > 0) ", $disguised hidden by anti-xray" else "") +
            if (loose.isNotEmpty()) " ($matchedFirm/$knownFirm without ${loose.joinToString()})" else ""
        // Enough blocks to mean something, but never more than most of the design: a taiga village's
        // meeting point is only 72 blocks, and asking for 60 matched threw away a real one at 49.
        // Of what anti-xray let be seen: an ocean ruin half buried in the sea floor shows only its top.
        // Where some kinds could be there anyway ([loose]), the rest is what must match, in share and
        // in number, not the whole: those are as often gone in the world, an ocean ruin's sea-floor
        // sand and gravel water, trail ruins' gravel dug out and a ruined portal's gold taken, and
        // ocean ruins whose stonework fitted nine blocks in ten were thrown away for it.
        val enough = minOf(MIN_MATCHED_BLOCKS, (firmSize * MIN_MATCHED_SHARE).toInt()) * known / maxOf(1, known + disguised)
        if (known + disguised < template.blocks.size * 0.6 || known < template.blocks.size * MIN_SEEN_SHARE ||
            matchedFirm < knownFirm * minMatch || matchedFirm < enough) return null
        if (open != null) openRefusal(template, rotation, ox, oy, oz, level, open, matchedFirm)?.let {
            lastCheck += ", but $it"
            return null
        }
        val a = BlockPos.ZERO.rotate(rotation)
        val b = BlockPos(template.sizeX - 1, template.sizeY - 1, template.sizeZ - 1).rotate(rotation)
        val box = Box(
            ox + minOf(a.x, b.x), oy + minOf(a.y, b.y), oz + minOf(a.z, b.z),
            ox + maxOf(a.x, b.x), oy + maxOf(a.y, b.y), oz + maxOf(a.z, b.z),
        )
        return Match(template, box, matched, known, rotation, origin)
    }

    /**
     * Why [template]'s open space, placed at [ox], [oy], [oz] turned by [rotation], is not as the game
     * leaves it, or null when it is: most of it what [open] says, and little of it the design's own
     * blocks, which the game never lays there. A player's wall of stone bricks under the sea covered
     * half a small ruin's bricks, with three times as many standing in its open water.
     */
    private fun openRefusal(
        template: Template, rotation: Rotation, ox: Int, oy: Int, oz: Int, level: BlockSource, open: (BlockState, Int) -> Boolean, matched: Int,
    ): String? {
        var known = 0
        var opened = 0
        var own = 0
        val tops = if (openTopOnly) openTops.getOrPut(template) { topsOf(template.air) } else null
        // Of [openTopOnly] columns, those with some of it seen, and those open: at the top, over it,
        // or below it, where the sea fills a design that stands under a ledge. Ruins on the floor of
        // an underwater cliff in testing had stone over most of their tops, and the
        // sea inside them.
        val columnsSeen = HashSet<Long>()
        val columnsOpen = HashSet<Long>()
        val columnsSea = HashSet<Long>()
        var inSea = 0
        val inSeaOpen: (BlockState, Int) -> Boolean = { state, _ -> sea?.invoke(state) == true }
        val cursor = BlockPos.MutableBlockPos()
        val over = BlockPos.MutableBlockPos()
        for (pos in template.air) {
            val turned = pos.rotate(rotation)
            cursor.set(ox + turned.x, oy + turned.y, oz + turned.z)
            if (!level.hasChunk(cursor.x shr 4, cursor.z shr 4)) continue
            val state = level.getBlockState(cursor)
            val isOpen = open(state, cursor.y)
            val isSea = sea?.invoke(state) == true
            // What anti-xray shows in place of a block nobody has seen says nothing either way: a
            // ruin half in the face of a cliff there had ore in all of its open space inside it.
            val hidden = !isOpen && state.block in DISGUISES
            if (tops == null) {
                if (hidden) continue
                known++
                if (isOpen) opened++
                if (isSea) inSea++
            } else {
                val column = BlockPos.asLong(pos.x, 0, pos.z)
                if (isOpen || (pos in tops && openOver(cursor, level, open, over))) columnsOpen.add(column)
                if (sea != null && (isSea || (pos in tops && openOver(cursor, level, inSeaOpen, over)))) columnsSea.add(column)
                if (!hidden) columnsSeen.add(column)
            }
            if (!isOpen && !hidden) family(state.block).let { if (it !in loose && it in template.byFamily) own++ }
        }
        if (tops != null) {
            opened = columnsOpen.size
            known = (columnsSeen + columnsOpen).size
            inSea = columnsSea.size
        }
        return when {
            known == 0 || opened < known * MIN_MATCH -> "its open space is not: $opened/$known"
            sea != null && inSea * IN_SEA < known && outOfSea?.invoke(BlockPos.asLong(ox, oy, oz), level) != true ->
                "it is not in the sea: $inSea/$known"
            own * OWN_IN_OPEN > matched -> "$own of its own blocks stand in its open space"
            else -> null
        }
    }

    /**
     * Whether the column over [top] is [open] once past what can lie over a buried design: the sand
     * and gravel that settled ([loose]) and the ores anti-xray shows; rock first says it is not.
     */
    private fun openOver(top: BlockPos, level: BlockSource, open: (BlockState, Int) -> Boolean, over: BlockPos.MutableBlockPos): Boolean {
        for (up in 1..OPEN_ABOVE) {
            val state = level.getBlockState(over.set(top.x, top.y + up, top.z))
            if (open(state, top.y + up)) return true
            if (state.block !in DISGUISES && family(state.block) !in loose) return false
        }
        return false
    }

    /** Each design's highest open cell in each of its columns ([openTopOnly]). */
    private val openTops = java.util.concurrent.ConcurrentHashMap<Template, Set<BlockPos>>()

    private fun topsOf(air: List<BlockPos>): Set<BlockPos> =
        air.groupBy { it.x to it.z }.values.mapTo(HashSet()) { column -> column.maxBy { it.y } }

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
        return if (mirrored) loaded + loaded.map(::mirror) else loaded
    }

    /** [template] mirrored front to back (its x turned round), as the game's `Mirror.FRONT_BACK` places it. */
    private fun mirror(template: Template): Template = Template(
        "${template.name} mirrored", template.sizeX, template.sizeY, template.sizeZ,
        template.blocks.map { (pos, block) -> BlockPos(template.sizeX - 1 - pos.x, pos.y, pos.z) to block },
        template.woods,
        template.entrances.map { (pos, facing) ->
            BlockPos(template.sizeX - 1 - pos.x, pos.y, pos.z) to if (facing.axis == Direction.Axis.X) facing.opposite else facing
        },
        template.air.map { BlockPos(template.sizeX - 1 - it.x, it.y, it.z) },
    )

    private fun parse(name: String, tag: CompoundTag): Template {
        val size = tag.getListOrEmpty("size")
        // One palette, or several (the same design in different woods); the first gives the shape.
        val palettes = tag.getList("palette").map { listOf(it) }.orElseGet {
            val many = tag.getListOrEmpty("palettes")
            (0 until many.size).map { many.getListOrEmpty(it) }
        }
        val blockPalettes = palettes.map { paletteTag ->
            (0 until paletteTag.size).map { i ->
                val entry = paletteTag.getCompoundOrEmpty(i)
                // 26.3 renamed a template's `Name` and `Properties` to `id` and `properties`.
                val id = entry.getString("id").orElseGet { entry.getStringOr("Name", "minecraft:air") }
                BuiltInRegistries.BLOCK.getValue(Identifier.parse(id))
            }
        }
        val palette = blockPalettes.firstOrNull() ?: emptyList()
        val blocksTag = tag.getListOrEmpty("blocks")
        val blocks = ArrayList<Pair<BlockPos, String>>()
        val entrances = ArrayList<Pair<BlockPos, Direction>>()
        val air = ArrayList<BlockPos>()
        for (i in 0 until blocksTag.size) {
            val entry = blocksTag.getCompoundOrEmpty(i)
            val state = entry.getIntOr("state", -1)
            val block = palette.getOrNull(state) ?: continue
            val pos = entry.getListOrEmpty("pos")
            val at = BlockPos(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0))
            if (block == Blocks.JIGSAW && entry.getCompoundOrEmpty("nbt").getStringOr("name", "") == BUILDING_ENTRANCE) {
                // Its orientation is the way it faces then which way is up, as `west_up`.
                val stateTag = palettes.first().getCompoundOrEmpty(state)
                val facing = stateTag.getCompound("properties").orElseGet { stateTag.getCompoundOrEmpty("Properties") }
                    .getStringOr("orientation", "").substringBefore('_')
                Direction.entries.firstOrNull { it.serializedName == facing }?.let { entrances.add(at to it) }
            }
            if (open != null && (block == Blocks.AIR || block == Blocks.CAVE_AIR)) air.add(at)
            if (block in IGNORED) continue
            blocks.add(at to family(block))
        }
        val woods = blockPalettes.flatten().filterTo(HashSet()) { it !in IGNORED }
        return Template(name, size.getIntOr(0, 0), size.getIntOr(1, 0), size.getIntOr(2, 0), blocks, woods, entrances, air)
    }
}
