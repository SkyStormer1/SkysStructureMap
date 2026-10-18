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

    /**
     * The groups in the dimension you are in, a fortress group holding more than one fortress as
     * one for each ([Detection.parts]). Chunks are sent again on changing dimension, so this starts over.
     */
    val detections: List<Detection> get() = groups.detections.flatMap { it.parts.ifEmpty { listOf(it) } }

    private var dimension: String? = null
    private var ticks = 0L

    fun clear() {
        startOver()
        dimension = null
    }

    /** Forgets every group, and everything kept about them by id elsewhere, which would otherwise pile up. */
    private fun startOver() {
        groups.clear()
        FortressLabelling.clear()
        Markers.outlinedNearby.clear()
    }

    fun addBlocks(type: StructureType, dimension: String, found: List<ChunkScanner.Found>) {
        if (dimension != this.dimension) {
            startOver()
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
            startOver()
            dimension = here
        }
        ticks++
        if (ticks % 10 == 0L) {
            val blocks = BlockSource.of(level)
            for (detection in groups.detections) {
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
        splitOffNext(groups, detection)
        for (one in detection.parts.ifEmpty { listOf(detection) }) {
            val box = one.box ?: continue
            if (one.storedId == null) link(one, box)
            val stored = one.storedId?.let(StructureStore::byId) ?: continue
            keepUpToDate(one, stored, box)
        }
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
        checkStart(detection)
    }

    /**
     * Refuses one whose start chunk ([Detection.start]) is where the game starts none of its kind
     * ([Placement.allows]), or in a square that already holds one saved from another chunk
     * ([StructureStore.startTaken]). Kinds whose start cannot be told from what is seen are not asked.
     */
    private fun checkStart(detection: Detection) {
        if (detection.box == null) return
        if (detection.start == null) detection.start = startFromBox(detection)
        val start = detection.start ?: return
        val refusal = if (!Placement.allows(detection.type, start.first, start.second)) "its kind never starts in chunk ${start.first},${start.second}"
        else StructureStore.startTaken(detection.type, detection.dimension, start)?.takeIf { it.id != detection.storedId }
            ?.let { "the ${it.name} (box ${it.box}) is the one start in its square" }
        if (refusal != null) {
            Log.info("{} #{} at {}: not one, starting in chunk {},{}: {}", detection.type.id, detection.id, detection.box, start.first, start.second, refusal)
            detection.box = null
        }
    }

    /**
     * The chunk a design that fits says its structure starts in: a wreck's from how it was laid,
     * a village's from its town centre, trail ruins' from their tower, which the game lays from
     * their chunk's corner. Null when the design does not tell (a village's house).
     */
    private fun startOf(type: StructureType, match: TemplateFit.Match): Pair<Int, Int>? = when {
        type == StructureType.SHIPWRECK -> ShipwreckFit.startChunk(match)
        type == StructureType.VILLAGE && TOWN_CENTRES in match.template.name || type == StructureType.TRAIL_RUINS ->
            Math.floorDiv(BlockPos.getX(match.origin), 16) to Math.floorDiv(BlockPos.getZ(match.origin), 16)
        else -> null
    }

    /** For the kinds whose box is laid out from their start chunk ([Placement.startFromBox]): that chunk, once proved. */
    private fun startFromBox(detection: Detection): Pair<Int, Int>? {
        val box = detection.box ?: return null
        val laidOut = detection.proved || detection.type !in setOf(StructureType.DESERT_TEMPLE, StructureType.JUNGLE_TEMPLE)
        return if (laidOut) Placement.startFromBox(detection.type, box) else null
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
                fitter.fit(detection, level, votes = { key -> BlockPos.getY(key) >= base + OUTPOST_TOWER_FROM })
            }
            // The bell says where a town centre is on its own, and the few other blocks the game
            // puts there do the same once the bell has been taken, as players do. Failing those,
            // one of the village's houses, by its job block or its door.
            StructureType.VILLAGE -> fitter.fitAnchored(detection, level, TOWN_CENTRE_ANCHORS, accept = { townCentreRefusal(it, level) })
                ?: VillageHouseFit.also { used = it }.fitAnchored(detection, level, JOB_BLOCKS)
                ?: VillageHouseFit.fitAnchored(detection, level, DOORS)
                ?: worn(detection, level)?.also { used = VillageHouseFit }
            StructureType.RUINED_PORTAL ->
                if (RuinedPortalFit.worthTrying(detection)) fitter.fit(detection, level, accept = RuinedPortalFit.refusal(level)) else null
            StructureType.SHIPWRECK -> fitter.fit(detection, level, accept = ShipwreckFit::refusal)
            StructureType.TRAIL_RUINS -> fitter.fit(detection, level, accept = TrailRuinsFit::refusal)
            else -> fitter.fit(detection, level)
        }
        if (match != null) {
            startOf(detection.type, match)?.let { detection.start = it }
            detection.variant = match.template.name
            detection.piece = match.box
            if (detection.type == StructureType.ANCIENT_CITY && match.template.name.startsWith(CITY_CENTRE)) detection.centre = match.box
            if (detection.type == StructureType.VILLAGE && TOWN_CENTRES in match.template.name) detection.centre = match.box
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
            if (detection.piece == null) return detection.bounds
            return detection.bounds?.let { around(detection, it) }
        }
        return when (detection.type) {
            StructureType.OUTPOST -> Recognise.outpostBox(match.box, match.rotation)
            in PROVED_THEN_SEEN -> detection.bounds?.let { around(detection, it) }
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
                StructureType.DESERT_TEMPLE -> TemplePieces.desertCross(detection, level)?.also { detection.piece = TemplePieces.desertBox(it) } != null
                StructureType.JUNGLE_TEMPLE -> TemplePieces.jungleTrap(detection, level)
                StructureType.MONUMENT -> MonumentLayout.matches(detection.box!!, level)
                StructureType.MINESHAFT -> MineshaftPieces.proved(detection, level)
                StructureType.DUNGEON -> Dungeons.room(detection, level)?.also { detection.piece = it } != null
                else -> true
            }
            if (!detection.proved) detection.box = null
        }
        // A dungeon's box is its room, found round the spawner when it was proved; a desert
        // pyramid's is measured from its floor cross.
        if ((detection.type == StructureType.DUNGEON || detection.type == StructureType.DESERT_TEMPLE) && detection.box != null) detection.box = detection.piece
        if (detection.type == StructureType.MINESHAFT && detection.box != null) detection.box = mineshaftBox(detection, level)
        val bricks = detection.bounds
        if (detection.type == StructureType.FORTRESS && detection.box != null && bricks != null) {
            // Its pieces: at once in a scan of Bobby's cache (its own thread), in the background
            // around you, where they arrive a moment later and this runs again.
            if (now) detection.pieces = FortressPieces.label(level, bricks)
            else FortressLabelling.request(detection, bricks)
            // Nether bricks that do not lay out fortress pieces are a build.
            detection.box = if (!FortressPieces.isFortress(detection.pieces)) null else FortressPieces.outerBox(bricks, detection.pieces)
            detection.parts = if (detection.box == null) emptyList() else partsOf(detection)
        }
        if (detection.box != null && before == null) {
            Log.info("Recognised {} #{} from {} blocks ({}), seen {}: box {}", detection.type.id, detection.id,
                detection.count, describeKinds(detection), detection.bounds, detection.box)
        }
    }

    /**
     * A mineshaft's box: what is seen of it, as far as the game reaches from its start room
     * ([Detection.centre]). Mineshafts cross into each other, so their fences chain into one group
     * for as far as they go: one was saved over a kilometre across. Without its room in sight, a
     * group wider than any one mineshaft is cut to a stretch that wide ([MineshaftPieces.stretch]).
     */
    private fun mineshaftBox(detection: Detection, level: BlockSource): Box? {
        val seen = detection.bounds ?: return null
        if (detection.centre == null) {
            detection.centre = MineshaftPieces.startRoom(detection, level)
                ?: MineshaftPieces.stretch(seen, detection).takeIf { maxOf(seen.sizeX, seen.sizeZ) > 2 * MineshaftPieces.REACH }
        }
        val centre = detection.centre ?: return seen
        return seen.clampedTo(MineshaftPieces.reachOf(centre))
    }

    /**
     * For a structure whose box was worked out from its one centre piece (an ancient city's centre,
     * a mineshaft's start room): the blocks beyond it, which chained in from the next one, split
     * off into a group of their own to be matched by themselves ([Groups.splitOff]). Two cities
     * stand close enough that their blocks join, and mineshafts cross into each other; the group
     * would otherwise be saved as one, the others never. Only from a centre: a box worked out from
     * another of a city's buildings may not reach across the whole city.
     */
    fun splitOffNext(groups: Groups, detection: Detection): Detection? {
        val centre = detection.centre ?: return null
        val box = detection.box ?: return null
        return when (detection.type) {
            StructureType.ANCIENT_CITY -> groups.splitOff(detection, box.grow(CITY_MARGIN), CITY_SPLIT_BLOCKS)
            StructureType.MINESHAFT -> groups.splitOff(detection, MineshaftPieces.reachOf(centre), MineshaftPieces.SUPPORTS * 4)
            else -> null
        }
    }

    private const val CITY_CENTRE = "city_center/"

    /** How far past a city's box its own blocks may still be, and how many blocks beyond that are another city. */
    private const val CITY_MARGIN = 16
    private const val CITY_SPLIT_BLOCKS = 500

    /**
     * One group for each fortress among [detection]'s pieces ([FortressPieces.split]), or none when
     * they are one. A part that shares pieces with one from before is kept, with what it was saved
     * as. Each takes the group's cells nearest its pieces, which make its outline and box.
     */
    private fun partsOf(detection: Detection): List<Detection> {
        val split = FortressPieces.split(detection.pieces)
        if (split.size < 2) return emptyList()
        val cells = detection.cells.values.groupBy { cell -> split.indices.minBy { i -> split[i].minOf { cell.gap(it.box) } } }
        val before = detection.parts.toMutableList()
        if (before.isEmpty()) Log.info("Fortress #{}: its pieces are {} fortresses", detection.id, split.size)
        return split.mapIndexed { i, pieces ->
            val part = before.filter { old -> old.pieces.any { it in pieces } }.maxByOrNull { old -> old.pieces.count { it in pieces } }
                ?.also { before.remove(it) }
                ?: Detection(Detection.newPartId(), detection.type, detection.dimension)
            part.partOf = detection
            part.pieces = pieces
            part.takeCells(cells[i].orEmpty())
            part.box = FortressPieces.outerBox(part.bounds ?: pieces.map { it.box }.reduce(Box::union), pieces)
            part
        }
    }

    /** Ties a group to the structure already saved where it stands, or to one deleted on purpose. */
    private fun link(detection: Detection, box: Box) {
        // Found again after rejoining, or seen from another side: it is the one already saved.
        val near = StructureStore.inDimension(detection.dimension)
            .filter { it.type == detection.type && Specs.sameStructure(it.type, it.box, box) }
            // Never the one a group split from it is, nor one with another centre.
            .filter { saved -> detection.apart.none { it.storedId == saved.id } && !StructureStore.twoCentres(saved.centre, detection.centre) }
            .sortedByDescending { saved -> saved.centre != null && saved.centre == detection.centre }
        val saved = when {
            detection.type == StructureType.FORTRESS && detection.pieces.isNotEmpty() -> fortressFor(detection, near)
            // Mineshafts cross each other: only one it overlaps, the one it overlaps most.
            detection.type == StructureType.MINESHAFT -> near.filter { it.box.overlaps(box) }
                .maxWithOrNull(compareBy<Structure> { it.centre != null && it.centre == detection.centre }.thenBy { it.box.overlapArea(box) })
            else -> near.firstOrNull()
        }
        if (saved != null) {
            detection.storedId = saved.id
            Log.info("{} #{} is the one discovered before ({})", detection.type.id, detection.id, saved.id)
        } else if (StructureStore.isDeleted(detection.type, detection.dimension, box)) {
            // Deleted on purpose: never shown or discovered again.
            detection.storedId = Menus.DELETED
            Log.info("{} #{} is one you deleted; ignoring it", detection.type.id, detection.id)
        }
    }

    /**
     * Of the fortresses saved near [fortress], the one it is: never one that would then hold more
     * crossroads than a fortress has, once it gives up the pieces of the group's other fortresses
     * (it may have been saved before they were told apart), nor one another of them is already;
     * of the rest, the one sharing most pieces with it, then the nearest.
     */
    private fun fortressFor(fortress: Detection, near: List<Structure>): Structure? {
        val theirs = othersPieces(fortress)
        val taken = fortress.partOf?.parts.orEmpty().filter { it !== fortress }.mapNotNull { it.storedId }.toSet()
        val box = fortress.box ?: return null
        fun distance(saved: Structure) = Math.abs(saved.box.centreX - box.centreX).toLong() + Math.abs(saved.box.centreZ - box.centreZ)
        return near.filter { it.id !in taken }
            .map { saved -> saved to saved.pieces.filterNot { old -> theirs.any { it.box.overlaps(old.box) } } }
            .filterNot { (_, kept) -> FortressPieces.tooManyCrossroads(fortress.pieces, kept) }
            .maxWithOrNull(compareBy<Pair<Structure, List<Piece>>> { (_, kept) -> kept.count { old -> fortress.pieces.any { it.box == old.box } } }
                .thenBy { (saved, _) -> -distance(saved) })
            ?.first
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
                fortressBase(detection, stored).union(box).let { if (box.minY == FortressPieces.LOWEST) it.copy(minY = FortressPieces.LOWEST) else it }
            // As far as the game reaches from its start room, however far its fences chain.
            // Without its room in sight, no wider than one can be from where it was saved: fences
            // chained across the mineshafts beside it grew one to 484 blocks.
            // Only by its own fences: those in another saved one are that one's, where they cross.
            detection.type == StructureType.MINESHAFT -> {
                val reach = MineshaftPieces.reachOf(stored.centre ?: detection.centre ?: MineshaftPieces.stretch(stored.box))
                (ownFences(detection, reach, stored.id)?.let { stored.box.union(it.first) } ?: stored.box).clampedTo(reach)
            }
            else -> stored.box.union(box)
        }.let { grown -> if (detection.type in PROVED_THEN_SEEN && detection.piece != null) around(detection, grown, stored.centre) else grown }
        // Pieces stay once saved, so they are still there after the structure is torn down or out of
        // sight; but one a piece found now overlaps gives way to it (saved before pieces could be
        // told apart, or a block off).
        val kept = stored.pieces.filterNot { old -> detection.pieces.any { it.box != old.box && it.box.overlaps(old.box) } }
            .filterNot { old -> othersPieces(detection).any { it.box.overlaps(old.box) } }
        if (kept.size != stored.pieces.size) {
            Log.info("{}: {} saved pieces replaced by ones found now", stored.name, stored.pieces.size - kept.size)
        }
        val pieces = kept + detection.pieces.filter { new -> kept.none { it.box == new.box } }
        val centre = stored.centre ?: detection.centre
        val start = stored.start ?: detection.start
        if (better != stored.box || pieces != stored.pieces || centre != stored.centre || start != stored.start)
            StructureStore.put(stored.copy(box = better, pieces = pieces, centre = centre, start = start))
    }

    /** The pieces of the other fortresses in [part]'s group. */
    private fun othersPieces(part: Detection): List<Piece> = part.partOf?.parts.orEmpty().filter { it !== part }.flatMap { it.pieces }

    /**
     * What a saved fortress's box grows from: the box saved, or, when it was saved holding the
     * group's other fortresses too (before they were told apart), only around the pieces it keeps.
     */
    private fun fortressBase(part: Detection, stored: Structure): Box {
        val theirs = othersPieces(part)
        if (stored.pieces.none { old -> theirs.any { it.box.overlaps(old.box) } }) return stored.box
        val own = stored.pieces.filterNot { old -> theirs.any { it.box.overlaps(old.box) } }
        Log.info("{}: saved with {} piece(s) of another fortress, now told apart", stored.name, stored.pieces.size - own.size)
        return own.map { it.box }.fold(part.box ?: stored.box, Box::union)
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

    /**
     * The outline of [detection]'s fences within [within] that stand in no saved mineshaft but
     * the one [except] names, and how many there are; null for none.
     */
    private fun ownFences(detection: Detection, within: Box, except: String?): Pair<Box, Int>? {
        val others = StructureStore.inDimension(detection.dimension)
            .filter { it.type == StructureType.MINESHAFT && it.id != except && it.box.overlaps(within) }.map { it.box }
        var bounds: Box? = null
        var count = 0
        val iterator = detection.positions.iterator()
        while (iterator.hasNext()) {
            val key = iterator.nextLong()
            val x = BlockPos.getX(key)
            val y = BlockPos.getY(key)
            val z = BlockPos.getZ(key)
            if (!within.contains(x, y, z) || others.any { it.contains(x, y, z) }) continue
            bounds = bounds?.including(x, y, z) ?: Box.of(x, y, z)
            count++
        }
        return bounds?.let { it to count }
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
        // One whose start room is not in sight is a mineshaft of its own only with enough fences
        // outside those saved, a rail or cobweb by one of their supports: the rest are theirs,
        // chained in where mineshafts cross.
        if (detection.type == StructureType.MINESHAFT && detection.centre?.let(MineshaftPieces::isStretch) == true) {
            val own = ownFences(detection, box, null)
            if (own == null || own.second < MineshaftPieces.SUPPORTS * 4 || !MineshaftPieces.furnishedIn(detection, own.first)) return
            detection.box = own.first
        }
        val structure = Structure(
            id = UUID.randomUUID().toString(),
            type = detection.type,
            dimension = detection.dimension,
            box = detection.box ?: box,
            discovered = System.currentTimeMillis(),
            variant = detection.variant,
            pieces = detection.pieces,
            centre = detection.centre,
            start = detection.start,
        )
        StructureStore.put(structure)
        detection.storedId = structure.id
        Log.info("Discovered {} #{} at {} {} {}, box {}", detection.type.id, detection.id, structure.box.centreX, structure.waypointY, structure.box.centreZ, structure.box)
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
        StructureType.RUINED_PORTAL -> RuinedPortalFit
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
     * that big. A village whose town centre is known ([Detection.centre], or [savedCentre]) is cut
     * to what the game can reach from it ([TOWN_REACH]); from a house it could be anywhere in the
     * village, so the whole width is allowed round that: player builds beside villages took their
     * boxes up to 96 blocks past the last house. An ancient city with its centre known is cut the
     * same way ([CITY_REACH]): the deepslate kept past its box for the next city ([CITY_MARGIN])
     * had made its box 16 blocks too wide.
     */
    private fun around(detection: Detection, seen: Box, savedCentre: Box? = null): Box {
        val reachFromCentre = when (detection.type) {
            StructureType.VILLAGE -> TOWN_REACH
            StructureType.ANCIENT_CITY -> CITY_REACH
            else -> null
        }
        val centre = if (reachFromCentre != null) detection.centre ?: savedCentre else null
        val piece = centre ?: detection.piece ?: return seen
        val reach = if (centre != null) reachFromCentre!! else Specs.spanOf(detection.type) / 2
        return Box(
            maxOf(seen.minX, piece.centreX - reach), maxOf(seen.minY, piece.minY - HEIGHT_REACH), maxOf(seen.minZ, piece.centreZ - reach),
            minOf(seen.maxX, piece.centreX + reach), minOf(seen.maxY, piece.maxY + HEIGHT_REACH), minOf(seen.maxZ, piece.centreZ + reach),
        )
    }

    /**
     * How far a village reaches from the middle of its town centre: the game lays its pieces at most
     * 80 blocks from there (`max_distance_from_center`), and every one of 497 in the test worlds kept
     * within 80.5; a little more for a house's eaves.
     */
    private const val TOWN_REACH = 84

    /** How far an ancient city reaches from the middle of its centre piece: 116.5 at most in the test worlds. */
    private const val CITY_REACH = 120

    private const val TOWN_CENTRES = "/town_centers/"

    /**
     * Why a town centre that fits cannot be one, or null when it can: the game picks a village's
     * kind (plains, desert, savanna, snowy, taiga) by the biome where it starts, which is its town
     * centre, so a taiga centre in the plains is a player's spruce build. Asked at its middle and
     * corners on the ground, any of them enough: a village at a biome's edge starts in one corner of
     * it. One seen in the plains matched a taiga centre half way, anchored on a spruce trapdoor in
     * someone's base, and was saved as a village 130 blocks from the real one.
     */
    private fun townCentreRefusal(match: TemplateFit.Match, level: BlockSource): String? {
        // The village starts from it, laid from its chunk's corner: all 497 in the test worlds were.
        if (match.inChunk != (0 to 0)) return "a town centre off its chunk's corner"
        val kind = match.template.name.substringBefore('/')
        val allowed = VILLAGE_BIOMES[kind] ?: return null
        val b = match.box
        val y = b.minY + 1
        val spots = listOf(b.centreX to b.centreZ, b.minX to b.minZ, b.minX to b.maxZ, b.maxX to b.minZ, b.maxX to b.maxZ)
        val biomes = spots.mapNotNull { (x, z) -> level.biome(BlockPos(x, y, z)) }
        if (biomes.isEmpty() || biomes.any { it in allowed }) return null
        return "a $kind town centre in ${biomes.distinct().joinToString("/")}"
    }

    /** The biomes each kind of village can start in (the game's `has_structure/village_*` tags). */
    private val VILLAGE_BIOMES = mapOf(
        "plains" to setOf("plains", "meadow"),
        "desert" to setOf("desert"),
        "savanna" to setOf("savanna"),
        "snowy" to setOf("snowy_plains"),
        "taiga" to setOf("taiga"),
    )

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
