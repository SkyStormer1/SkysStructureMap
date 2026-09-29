package com.skystormer.skysstructuremap

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Blocks
import java.util.UUID

/**
 * Turns the blocks [ChunkScanner] finds into structures: groups them, decides when a group is
 * recognised, and saves it as discovered once you come near it ([Detection.touches]).
 *
 * Everything here runs on the client's main thread (chunk loading, ticking and drawing the map
 * all do), so nothing needs locking.
 */
object Tracker {

    private val groups = Groups()

    /** The groups in the dimension you are in. Chunks are sent again on changing dimension, so this starts over. */
    val detections: List<Detection> get() = groups.detections

    private var dimension: String? = null
    private var ticks = 0L

    fun clear() {
        groups.clear()
        dimension = null
    }

    fun addBlocks(type: StructureType, dimension: String, found: List<ChunkScanner.Found>) {
        if (dimension != this.dimension) {
            groups.clear()
            this.dimension = dimension
        }
        groups.add(type, dimension, found, keepsBlocks(type))
    }

    /** Matched against the game's designs, which need to know each block. */
    fun keepsBlocks(type: StructureType): Boolean = fitterFor(type) != null

    fun tick(minecraft: Minecraft) {
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val here = level.dimension().identifier().toString()
        if (here != dimension) {
            if (detections.isNotEmpty()) Log.info("Changed dimension to {}; starting over on {} group(s)", here, detections.size)
            groups.clear()
            dimension = here
        }
        ticks++
        if (ticks % 10 == 0L) {
            val blocks = BlockSource.of(level)
            for (detection in detections) {
                if (detection.changed || (fitterFor(detection.type) != null && detection.fitDirty)) update(detection, blocks)
            }
        }
        if (ticks % 4 == 0L) {
            val hitbox = player.boundingBox
            for (detection in detections) {
                if (detection.box != null && detection.storedId == null && detection.touches(hitbox)) discover(detection)
            }
        }
        if (ticks % 100 == 0L) StructureStore.saveIfChanged()
    }

    /**
     * Looks at one group again: works out whether it is a structure and what its box is, then ties
     * it to what is already saved and keeps that up to date.
     */
    private fun update(detection: Detection, level: BlockSource) {
        recognise(detection, level)
        val box = detection.box ?: return
        if (detection.storedId == null) link(detection, box)
        val stored = detection.storedId?.let(StructureStore::byId) ?: return
        keepUpToDate(detection, stored, box)
    }

    /**
     * Works out whether a group is a structure, and what its box is ([Detection.box], null when it
     * is not one). [now]: without waiting since the last try, for a group whose blocks have all
     * been read already (from Bobby's cache, which calls this from its own thread).
     */
    fun recognise(detection: Detection, level: BlockSource, now: Boolean = false) {
        detection.changed = false
        val before = detection.box
        val fitter = fitterFor(detection.type)
        if (fitter != null) matchDesign(detection, level, fitter, now) else proveInWorld(detection, level, before, now)
    }

    /**
     * For the kinds matched against the game's own designs: fits one, and takes the box from it.
     * Tried at most every other second per group, and only once enough of it is in the right biome.
     */
    private fun matchDesign(detection: Detection, level: BlockSource, fitter: TemplateFit, now: Boolean) {
        if (detection.blocks.size < TemplateFit.MIN_BLOCKS || (!now && ticks - detection.lastFitTick < FIT_EVERY)) return
        if (Specs.of(detection.type).biomeAsWhole && detection.inBiome < BIOME_BLOCKS) {
            if (detection.blocks.size >= LOG_FROM && !detection.biomeLogged) {
                detection.biomeLogged = true
                Log.info("{} #{} at {}: only {} of {} blocks in its biomes, so not matched",
                    detection.type.id, detection.id, detection.bounds, detection.inBiome, detection.count)
            }
            return
        }
        detection.fitDirty = false
        detection.lastFitTick = ticks
        val before = detection.box
        val started = System.nanoTime()
        var used: TemplateFit = fitter
        val match = when (detection.type) {
            // An outpost's cages, tents and log piles are the same wood as its tower; only the
            // tower stands well above its base, so only those blocks vote.
            StructureType.OUTPOST -> {
                val base = detection.bounds?.minY ?: 0
                fitter.fit(detection, level) { key -> BlockPos.getY(key) >= base + OUTPOST_TOWER_FROM }
            }
            // The bell says where a town centre is on its own, and the few other blocks the game
            // puts there do the same once the bell has been taken, as players do. Failing those,
            // one of the village's houses, by its job block or its door.
            StructureType.VILLAGE -> fitter.fitAnchored(detection, level, TOWN_CENTRE_ANCHORS)
                ?: VillageHouseFit.also { used = it }.fitAnchored(detection, level, JOB_BLOCKS)
                ?: VillageHouseFit.fitAnchored(detection, level, DOORS)
                ?: worn(detection, level)?.also { used = VillageHouseFit }
            else -> fitter.fit(detection, level)
        }
        if (match != null) {
            detection.variant = match.template.name
            detection.piece = match.box
        }
        detection.box = boxFrom(detection, match)
        if (match?.box != before || (match == null && detection.blocks.size >= LOG_FROM)) {
            Log.info("{} #{} at {}: {} in {} ms ({})", detection.type.id, detection.id, detection.bounds,
                match?.let { "${it.template.name}, box ${detection.box}" } ?: "no template fits",
                (System.nanoTime() - started) / 1_000_000, used.lastReport)
        }
    }

    /**
     * The box after a design has, or has not, fitted this time: the matched piece itself for the
     * kinds whose shape it fixes, and everything seen around that piece for the kinds that sprawl.
     * Those keep their box once proved, growing as more of them loads.
     */
    private fun boxFrom(detection: Detection, match: TemplateFit.Match?): Box? {
        if (match == null) {
            if (detection.type !in PROVED_THEN_SEEN || detection.variant == null) return detection.box
            val piece = detection.piece ?: return detection.bounds
            return detection.bounds?.let { around(detection.type, it, piece) }
        }
        return when (detection.type) {
            StructureType.OUTPOST -> Recognise.outpostBox(match.box)
            in PROVED_THEN_SEEN -> detection.bounds?.let { around(detection.type, it, match.box) }
            else -> match.box
        }
    }

    /**
     * For the kinds the game builds in code rather than from a design: the blocks it is made of,
     * and then a piece of the game's own layout, which is what tells it from a player's build.
     */
    private fun proveInWorld(detection: Detection, level: BlockSource, before: Box?, now: Boolean) {
        detection.box = Recognise.box(detection)
        if (detection.box != null && !detection.proved) {
            detection.proved = when (detection.type) {
                StructureType.DESERT_TEMPLE -> TemplePieces.desertCross(detection, level)
                StructureType.JUNGLE_TEMPLE -> TemplePieces.jungleTrap(detection, level)
                else -> true
            }
            if (!detection.proved) detection.box = null
        }
        val bricks = detection.bounds
        if (detection.type == StructureType.FORTRESS && detection.box != null && bricks != null) {
            // Its pieces: at once in a scan of Bobby's cache (its own thread), in the background
            // around you, where they arrive a moment later and this runs again.
            if (now) detection.pieces = FortressPieces.label(level, bricks)
            else FortressLabelling.request(detection, bricks)
            // Nether bricks that do not lay out fortress pieces are a build.
            detection.box = if (!FortressPieces.isFortress(detection.pieces)) null else FortressPieces.outerBox(bricks, detection.pieces)
        }
        if (detection.box != null && before == null) {
            Log.info("Recognised {} #{} from {} blocks ({}), seen {}: box {}", detection.type.id, detection.id,
                detection.count, describeKinds(detection), detection.bounds, detection.box)
        }
    }

    /** Ties a group to the structure already saved where it stands, or to one deleted on purpose. */
    private fun link(detection: Detection, box: Box) {
        // Found again after rejoining, or seen from another side: it is the one already saved.
        val saved = StructureStore.inDimension(detection.dimension)
            .firstOrNull { it.type == detection.type && Specs.sameStructure(it.type, it.box, box) }
        if (saved != null) {
            detection.storedId = saved.id
            Log.info("{} #{} is the one discovered before ({})", detection.type.id, detection.id, saved.id)
        } else if (StructureStore.isDeleted(detection.type, detection.dimension, box)) {
            // Deleted on purpose: never shown or discovered again.
            detection.storedId = Menus.DELETED
            Log.info("{} #{} is one you deleted; ignoring it", detection.type.id, detection.id)
        }
    }

    /** Grows a saved structure's box and pieces as more of it is seen. */
    private fun keepUpToDate(detection: Detection, stored: Structure, box: Box) {
        // More seen of a structure whose shape comes from what is seen makes its box bigger; an
        // exact box (a monument) can only get more certain, and a wreck's matched box stays.
        val better = when {
            detection.type == StructureType.SHIPWRECK -> stored.box
            Specs.of(detection.type).reach == null -> box
            // A fortress's bottom is worked out, not seen: the new one replaces any older guess.
            detection.type == StructureType.FORTRESS ->
                stored.box.union(box).let { if (box.minY == FortressPieces.LOWEST) it.copy(minY = FortressPieces.LOWEST) else it }
            else -> stored.box.union(box)
        }.let { grown -> detection.piece?.let { around(detection.type, grown, it) } ?: grown }
        // Pieces stay once saved, so they are still there after the structure is torn down or out of
        // sight; but one a piece found now overlaps gives way to it (saved before pieces could be
        // told apart, or a block off).
        val kept = stored.pieces.filterNot { old -> detection.pieces.any { it.box != old.box && it.box.overlaps(old.box) } }
        if (kept.size != stored.pieces.size) {
            Log.info("{}: {} saved pieces replaced by ones found now", stored.name, stored.pieces.size - kept.size)
        }
        val pieces = kept + detection.pieces.filter { new -> kept.none { it.box == new.box } }
        if (better != stored.box || pieces != stored.pieces) StructureStore.put(stored.copy(box = better, pieces = pieces))
    }

    /** What became of a group found in Bobby's cache ([takeIn]). */
    enum class Taken { NEW, KNOWN, DELETED, NOT_ONE }

    /**
     * Saves a group recognised in Bobby's cache as discovered, without waiting for you to come near
     * it: you were there once, when Bobby saved it. One already saved is kept (its box grows if
     * more of it was seen), and one you deleted stays deleted, just as when you walk up to them.
     */
    fun takeIn(detection: Detection): Taken {
        val box = detection.box ?: return Taken.NOT_ONE
        link(detection, box)
        when (val id = detection.storedId) {
            Menus.DELETED -> return Taken.DELETED
            null -> {
                discover(detection, announce = false)
                return if (detection.storedId.isNullOrEmpty() || detection.storedId == Menus.DELETED) Taken.NOT_ONE else Taken.NEW
            }
            else -> {
                StructureStore.byId(id)?.let { keepUpToDate(detection, it, box) }
                return Taken.KNOWN
            }
        }
    }

    /**
     * Ties the groups recognised around you but not yet discovered to what is saved, after
     * structures were saved without you coming near them (a scan of Bobby's cache): one of them
     * may be among those, and should not be shown faintly beside it.
     */
    fun relinkUndiscovered() {
        for (detection in detections) {
            val box = detection.box ?: continue
            if (detection.storedId == null) link(detection, box)
        }
    }

    private fun discover(detection: Detection, announce: Boolean = Config.announce) {
        val box = detection.box ?: return
        if (!StructureStore.isOpen) {
            Log.warn("Inside {} #{}, but no structures file is open (not in a world?)", detection.type.id, detection.id)
            detection.storedId = ""
            return
        }
        // Asked again here, not only when it was first recognised: the file of what you have found
        // (and deleted) is read a moment after joining, and a structure recognised in that moment
        // was saved afresh, so one deleted on purpose came back.
        link(detection, box)
        if (detection.storedId != null) return
        val structure = Structure(
            id = UUID.randomUUID().toString(),
            type = detection.type,
            dimension = detection.dimension,
            box = box,
            discovered = System.currentTimeMillis(),
            variant = detection.variant,
            pieces = detection.pieces,
        )
        StructureStore.put(structure)
        detection.storedId = structure.id
        Log.info("Discovered {} #{} at {} {} {}, box {}", detection.type.id, detection.id, box.centreX, structure.waypointY, box.centreZ, box)
        if (announce) {
            Minecraft.getInstance().player?.sendSystemMessage(
                Component.literal("Discovered ").withStyle { it.withColor(0xAAAAAA) }
                    .append(Component.literal(structure.name).withStyle { it.withColor(structure.type.colour and 0xFFFFFF) })
                    .append(Component.literal(" at ${box.centreX} ${structure.waypointY} ${box.centreZ}").withStyle { it.withColor(0xAAAAAA) })
            )
        }
    }

    private fun describeKinds(detection: Detection): String =
        detection.kinds.entries.sortedByDescending { it.value }.take(6)
            .joinToString(", ") { "${it.value} ${net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(it.key).path}" }

    /** The game designs a kind is matched against, when it is matched that way. */
    private fun fitterFor(type: StructureType): TemplateFit? = when (type) {
        StructureType.SHIPWRECK -> ShipwreckFit
        StructureType.OUTPOST -> WatchtowerFit
        StructureType.VILLAGE -> TownCentreFit
        StructureType.TRAIL_RUINS -> TrailRuinsFit
        StructureType.END_CITY -> EndCityFit
        StructureType.BASTION -> BastionFit
        StructureType.ANCIENT_CITY -> AncientCityFit
        StructureType.MANSION -> MansionFit
        else -> null
    }

    /** Kinds proved once by one piece matching, whose box is then everything seen around it. */
    private val PROVED_THEN_SEEN = setOf(StructureType.VILLAGE, StructureType.TRAIL_RUINS, StructureType.END_CITY, StructureType.BASTION, StructureType.ANCIENT_CITY, StructureType.MANSION)

    /**
     * Blocks a village's town centre has in only a few places, so one of them says where the centre
     * is: the bell first, then what is left when someone has taken it (snowy centres are built of
     * packed ice and stripped wood, taiga ones of mossy cobblestone, with lanterns and trapdoors).
     */
    private val TOWN_CENTRE_ANCHORS = listOf(
        Blocks.BELL,
        Blocks.LANTERN,
        Blocks.SPRUCE_TRAPDOOR,
        Blocks.PACKED_ICE,
        Blocks.STRIPPED_SPRUCE_WOOD,
    )

    /**
     * Everything seen, within one structure's width of the piece that matched (see [Specs.spanOf],
     * measured from the structures the game had built in the test world): a taiga village's box ran
     * 262 blocks across and 127 high, picking up whatever lay near its streets, and no village is
     * that big.
     */
    private fun around(type: StructureType, seen: Box, piece: Box): Box {
        val reach = Specs.spanOf(type) / 2
        return Box(
            maxOf(seen.minX, piece.centreX - reach), maxOf(seen.minY, piece.minY - HEIGHT_REACH), maxOf(seen.minZ, piece.centreZ - reach),
            minOf(seen.maxX, piece.centreX + reach), minOf(seen.maxY, piece.maxY + HEIGHT_REACH), minOf(seen.maxZ, piece.centreZ + reach),
        )
    }

    /** How far above or below the piece that matched a structure still reaches. */
    private const val HEIGHT_REACH = 64

    /**
     * A village too worn for any house to fit by itself: a house that fits at least [WORN_MATCH]
     * and opens onto a street, confirmed by a farm that does too ([VillageStreets]). Neither is
     * enough alone; a farm on its own is what a player's farm with a path to it is as well. Around
     * player bases a big house design fitted up to 36%, and 48% once, but never with a street at
     * both it and a farm.
     */
    private fun worn(detection: Detection, level: BlockSource): TemplateFit.Match? {
        VillageFarmFit.fitAnchored(detection, level, listOf(Blocks.COMPOSTER)) { VillageStreets.refuse(it, level) } ?: return null
        return VillageHouseFit.fitAnchored(detection, level, JOB_BLOCKS + DOORS, WORN_MATCH) { VillageStreets.refuse(it, level) }
    }

    /** How much of a house must fit when a farm on a street confirms it. */
    private const val WORN_MATCH = 0.4

    private val DOORS = listOf(Blocks.OAK_DOOR, Blocks.SPRUCE_DOOR, Blocks.ACACIA_DOOR, Blocks.JUNGLE_DOOR)

    /** The job blocks of a village's working houses, one or two to a design. */
    private val JOB_BLOCKS = listOf(
        Blocks.LECTERN, Blocks.SMITHING_TABLE,
        Blocks.GRINDSTONE, Blocks.BLAST_FURNACE,
        Blocks.SMOKER, Blocks.CARTOGRAPHY_TABLE,
        Blocks.FLETCHING_TABLE, Blocks.LOOM,
        Blocks.STONECUTTER, Blocks.BARREL,
    )

    /** Blocks in an allowed biome a group needs, when the biome is asked of the whole group. */
    const val BIOME_BLOCKS = 20

    /** Ticks between two tries at fitting one group against the designs. */
    private const val FIT_EVERY = 40

    /** A group of this many blocks is worth a line in the log when nothing fits it. */
    private const val LOG_FROM = 100

    /** Blocks this far above an outpost's base can only be its tower. */
    private const val OUTPOST_TOWER_FROM = 6

    /** Recognised but not yet discovered, in [dimension]: shown faintly when that is turned on. */
    fun undiscovered(dimension: String): List<Detection> =
        detections.filter { it.dimension == dimension && it.box != null && it.storedId == null }
}
