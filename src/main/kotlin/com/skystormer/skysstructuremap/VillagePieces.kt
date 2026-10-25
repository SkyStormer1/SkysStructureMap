package com.skystormer.skysstructuremap

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoorBlock

/**
 * A village drawn from its own pieces, as the game builds it: a town centre, streets, and houses
 * along them, each house's entrance on a street ([VillageStreets]), no two overlapping, and
 * nothing further than 80 blocks from its centre. From one piece that fitted, its town centre is
 * looked for ([findCentre]); then the houses and farms near the pieces found are fitted to the
 * game's designs one by one, each found by a door, job block or composter, and then those near
 * them, until no more are found. Its box is those pieces, and the streets joined to them.
 *
 * Near, not along the streets: round spawn in a survival server's world players had dug up most of the
 * streets between the houses still standing. Players' paths, doors, beds and job blocks there took
 * villages' boxes, worked out from everything seen near a house, up to twice as wide as the village;
 * their builds fit none of the game's designs. Of the four villages there with most of their houses
 * and their town centre standing, drawn this way their boxes overlapped their real ones 0.74 to 0.98,
 * where taken from everything seen near a house 0.55 to 0.98.
 */
object VillagePieces {

    /** A village's pieces that fitted, its box, and its town centre if that was among them. */
    class Drawn(val pieces: List<TemplateFit.Match>, val box: Box, val centre: TemplateFit.Match?)

    /**
     * The village [first] (a piece of it that fitted) is a piece of, drawn from it; [centreRefusal]
     * says why a town centre that fits cannot be one.
     */
    fun draw(level: BlockSource, first: TemplateFit.Match, centreRefusal: (TemplateFit.Match) -> String?): Drawn {
        val pieces = arrayListOf(first)
        val centre = first.takeIf { TOWN_CENTRES in it.template.name } ?: findCentre(level, first, centreRefusal)?.also(pieces::add)
        var built = pieces.map { it.box }.reduce(Box::union)
        val looked = LongOpenHashSet()
        val tried = LongOpenHashSet()
        val budget = intArrayOf(DRAW_BUDGET)
        val cursor = BlockPos.MutableBlockPos()
        // No further than the game builds from its town centre: from it when found, else from
        // [first] as far as one could be, wherever in the village [first] is.
        val within = centre?.box?.let { reachOf(it, TOWN_REACH) } ?: reachOf(first.box, ACROSS)
        var rounds = 0
        var fresh = true
        while (fresh && rounds++ < MAX_ROUNDS && budget[0] > 0) {
            fresh = false
            // As high and low as the village can reach: one in a survival server's world ran down a
            // hillside from 133 to 63, its centre near the top.
            val near = built.grow(GAP).let { Box(it.minX, within.minY, it.minZ, it.maxX, within.maxY, it.maxZ) }.clampedTo(within)
            // What could tell a piece, in what has not been looked through yet, nearest the pieces first.
            val anchors = ArrayList<Pair<Long, Block>>()
            for (x in near.minX..near.maxX) for (z in near.minZ..near.maxZ) {
                if (!level.hasChunk(x shr 4, z shr 4) || !looked.add(BlockPos.asLong(x, 0, z))) continue
                for (y in near.minY..near.maxY) {
                    val block = level.getBlockState(cursor.set(x, y, z)).block
                    if (block !in ANCHORS) continue
                    if (block is DoorBlock && level.getBlockState(cursor.set(x, y - 1, z)).block == block) continue
                    anchors.add(BlockPos.asLong(x, y, z) to block)
                }
            }
            anchors.sortBy { (key, _) -> built.horizontalDistance(BlockPos.getX(key) + 0.5, BlockPos.getZ(key) + 0.5) }
            for ((key, block) in anchors) {
                if (budget[0] <= 0) break
                val x = BlockPos.getX(key)
                val y = BlockPos.getY(key)
                val z = BlockPos.getZ(key)
                if (pieces.any { it.box.contains(x, y, z) } || !tried.add(key)) continue
                val fitter = if (block == Blocks.COMPOSTER) VillageFarmFit else VillageHouseFit
                val match = fitter.fitAt(key, block, level, accept = { m ->
                    if (pieces.any { it.box.overlaps(m.box) }) "over a piece already found" else VillageStreets.refuse(m, level)
                }, budget = budget) ?: continue
                pieces.add(match)
                built = built.union(match.box)
                fresh = true
            }
        }
        val box = streets(level, built, pieces, centre)
        Log.info("Village drawn from {} at {}: {} pieces ({}), box {} in {} rounds, {} checks left",
            first.template.name, first.box, pieces.size, pieces.drop(1).joinToString { "${it.template.name.substringAfterLast('/')} ${it.matched}/${it.known} at ${it.box.minX},${it.box.minY},${it.box.minZ}" },
            box, rounds, budget[0])
        return Drawn(pieces, box, centre)
    }

    /**
     * The town centre the village [first] is a piece of grows from, or null when none is seen: one of
     * the centre designs of its kind (plains, desert, savanna, snowy, taiga) laid from a chunk's corner
     * no further from [first] than the game builds from one, found by any block of it, which players
     * leave when they take its bell. Every block it is checked from says where its corner is; only
     * corners on a chunk's corner are checked, so a field of path or fences costs little.
     *
     * A town centre stands on the ground, in the open, so anti-xray hides none of it: the five round
     * spawn in a survival server's world were 86 to 100% seen. Half of a quarter seen matched a plains
     * meeting point among the ores anti-xray showed 30 blocks underground there.
     */
    private fun findCentre(level: BlockSource, first: TemplateFit.Match, centreRefusal: (TemplateFit.Match) -> String?): TemplateFit.Match? {
        val kind = first.template.name.substringBefore('/')
        val designs = TownCentreFit.templates.filter { it.name.startsWith("$kind/") }
        val kinds = designs.flatMapTo(HashSet()) { it.woods }.filterTo(HashSet()) { it !in GROUND }
        if (kinds.isEmpty()) return null
        val names = designs.mapTo(HashSet()) { it.name }
        val area = Box(
            first.box.minX - TOWN_REACH, first.box.minY - HEIGHT, first.box.minZ - TOWN_REACH,
            first.box.maxX + TOWN_REACH, first.box.maxY + HEIGHT, first.box.maxZ + TOWN_REACH,
        )
        val checked = HashSet<Triple<String, net.minecraft.world.level.block.Rotation, Long>>()
        val cursor = BlockPos.MutableBlockPos()
        var best: TemplateFit.Match? = null
        for (x in area.minX..area.maxX) for (z in area.minZ..area.maxZ) {
            if (!level.hasChunk(x shr 4, z shr 4)) continue
            for (y in area.minY..area.maxY) {
                val block = level.getBlockState(cursor.set(x, y, z)).block
                if (block !in kinds) continue
                val match = TownCentreFit.fitAt(BlockPos.asLong(x, y, z), block, level, maxSpots = Int.MAX_VALUE,
                    accept = { m -> if (m.known < m.template.blocks.size * CENTRE_SEEN) "hidden, so not on the surface" else centreRefusal(m) },
                    origins = { template, rotation, origin ->
                        template.name in names && Math.floorMod(BlockPos.getX(origin), 16) == 0 && Math.floorMod(BlockPos.getZ(origin), 16) == 0 &&
                            checked.add(Triple(template.name, rotation, origin))
                    }) ?: continue
                if (Math.abs(match.box.centreX - first.box.centreX) > TOWN_REACH || Math.abs(match.box.centreZ - first.box.centreZ) > TOWN_REACH) continue
                if (best == null || match.share > best.share) best = match
            }
        }
        return best
    }

    /**
     * A village's box: its [pieces], and the streets joined to them. With its [centre] known, every
     * stretch of street as far as the game builds from it that is joined to the pieces, or to street
     * that is, across a gap no wider than [STREET_GAP] where players dug it up; without, only street just
     * past the pieces ([STREET_END]), not where a path leads on.
     */
    private fun streets(level: BlockSource, built: Box, pieces: List<TemplateFit.Match>, centre: TemplateFit.Match?): Box {
        val window = centre?.box?.let { reachOf(it, TOWN_REACH) } ?: built.grow(STREET_END, down = REACH_Y, up = REACH_Y)
        val cursor = BlockPos.MutableBlockPos()
        // Each column of the window with street in it, at the height of its top street: the planks
        // by water in a mineshaft under a village in a survival server's world took its box down to -9.
        val street = it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap()
        val low = window.minY
        val high = window.maxY
        for (x in window.minX..window.maxX) for (z in window.minZ..window.maxZ) {
            if (!level.hasChunk(x shr 4, z shr 4)) continue
            for (y in high downTo low) if (VillageStreets.isStreet(level, cursor.set(x, y, z))) {
                street.put(BlockPos.asLong(x, 0, z), y)
                break
            }
        }
        val gap = if (centre != null) STREET_GAP else 1
        val queue = ArrayDeque<Long>()
        val reached = LongOpenHashSet()
        val iterator = street.keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.nextLong()
            val x = BlockPos.getX(key)
            val z = BlockPos.getZ(key)
            if (pieces.any { p -> x in p.box.minX - 2..p.box.maxX + 2 && z in p.box.minZ - 2..p.box.maxZ + 2 } && reached.add(key)) queue.add(key)
        }
        var box = built
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            val x = BlockPos.getX(key)
            val z = BlockPos.getZ(key)
            val y = street.get(key)
            box = box.including(x, y, z)
            // On along the ground, as a street follows it: up or down no more than [CLIMB] for each block
            // along, and [CLIMB] more. One a block was too little for steep villages, whose boxes lost
            // up to a third, while the planks of a mineshaft under one lay 50 down.
            for (dx in -gap..gap) for (dz in -gap..gap) {
                val next = BlockPos.asLong(x + dx, 0, z + dz)
                if (!street.containsKey(next) || Math.abs(street.get(next) - y) > CLIMB * (maxOf(Math.abs(dx), Math.abs(dz)) + 1)) continue
                if (reached.add(next)) queue.add(next)
            }
        }
        return if (centre != null) box.clampedTo(reachOf(centre.box, TOWN_REACH)) else box
    }

    /** As far as the village [first] (a piece of it that fitted) can reach: what [draw] looks at. */
    fun reachFrom(first: TemplateFit.Match): Box = reachOf(first.box, ACROSS)

    /** As far as [reach] round the middle of [piece] sideways, and [HEIGHT] above and below it. */
    private fun reachOf(piece: Box, reach: Int) = Box(
        piece.centreX - reach, piece.minY - HEIGHT, piece.centreZ - reach, piece.centreX + reach, piece.maxY + HEIGHT, piece.centreZ + reach,
    )

    /** The blocks a house or farm is found by: a farm's composter, a house's job block or door. */
    private val ANCHORS = setOf(
        Blocks.COMPOSTER,
        Blocks.LECTERN, Blocks.SMITHING_TABLE, Blocks.GRINDSTONE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
        Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE, Blocks.LOOM, Blocks.STONECUTTER, Blocks.BARREL,
        Blocks.OAK_DOOR, Blocks.SPRUCE_DOOR, Blocks.ACACIA_DOOR, Blocks.JUNGLE_DOOR,
    )

    private const val TOWN_CENTRES = "/town_centers/"

    /** How far a village reaches from the middle of its town centre (`Tracker`'s TOWN_REACH), and so across from any piece of it. */
    private const val TOWN_REACH = 84
    private const val ACROSS = 2 * TOWN_REACH

    /** How far above or below its town centre a village reaches: the game's 80 (`max_distance_from_center`) goes up and down too. */
    private const val HEIGHT = 80

    /** How far above or below its pieces a village's streets are looked for, when its centre is not known. */
    private const val REACH_Y = 12

    /** How far from the pieces found so far the next is looked for. */
    private const val GAP = 32

    /** How much of a town centre must be seen, not hidden by anti-xray. */
    private const val CENTRE_SEEN = 0.8

    /** Blocks the ground round a town centre is made of anyway, which say nothing of where it is. */
    private val GROUND = setOf(
        Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.SAND, Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.WATER, Blocks.SNOW,
        Blocks.AIR, Blocks.CAVE_AIR, Blocks.JIGSAW, Blocks.STRUCTURE_VOID, Blocks.STRUCTURE_BLOCK,
    )

    /** How wide a gap in a street, dug up, it is followed across once the village's centre is known. */
    private const val STREET_GAP = 12

    /** How far up or down a street may go for each block along it. */
    private const val CLIMB = 2

    /** How far past its pieces a village's streets are in its box, when its centre is not known. */
    private const val STREET_END = 8

    /** How many placements one village's drawing may check, and how many times it looks further out. */
    private const val DRAW_BUDGET = 40_000
    private const val MAX_ROUNDS = 24
}
