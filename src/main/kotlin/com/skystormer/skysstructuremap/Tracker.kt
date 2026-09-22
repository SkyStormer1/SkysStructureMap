package com.skystormer.skysstructuremap

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.UUID

/**
 * Turns the blocks [ChunkScanner] finds into structures: groups them, decides when a group is
 * recognised, and saves it as discovered once you come near it ([Detection.touches]).
 *
 * Everything here runs on the client's main thread (chunk loading, ticking and drawing the map
 * all do), so nothing needs locking.
 */
object Tracker {

    /** Blocks of the same kind this close to a group join it rather than starting another. */
    private fun mergeDistance(type: StructureType): Int = Specs.of(type).merge

    /** The groups in the dimension you are in. Chunks are sent again on changing dimension, so this starts over. */
    var detections: List<Detection> = emptyList()
        private set

    private var dimension: String? = null
    private var nextId = 1
    private var ticks = 0L

    fun clear() {
        detections = emptyList()
        dimension = null
    }

    fun addBlocks(type: StructureType, dimension: String, found: List<ChunkScanner.Found>) {
        if (dimension != this.dimension) {
            detections = emptyList()
            this.dimension = dimension
        }
        // Matched against the game's designs, which need to know each block.
        val keepBlocks = fitterFor(type) != null
        for (group in found.groupBy { Detection.cellKey(it.x, it.y, it.z) }.values) {
            var cell = Box.of(group[0].x, group[0].y, group[0].z)
            for (f in group) cell = cell.including(f.x, f.y, f.z)
            val reach = mergeDistance(type)
            val near = detections.filter { it.type == type && it.bounds?.grow(reach)?.overlaps(cell) == true }
            val target = near.firstOrNull() ?: Detection(nextId++, type, dimension).also { detections = detections + it }
            if (near.size > 1) {
                for (other in near.drop(1)) target.absorb(other)
                detections = detections - near.drop(1).toSet()
                Log.info("Joined {} groups of {} blocks into #{}", near.size, type.id, target.id)
            }
            for (f in group) target.add(f.x, f.y, f.z, f.block, keepBlocks, f.inBiome)
        }
    }

    fun tick(minecraft: Minecraft) {
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val here = level.dimension().identifier().toString()
        if (here != dimension) {
            if (detections.isNotEmpty()) Log.info("Changed dimension to {}; starting over on {} group(s)", here, detections.size)
            detections = emptyList()
            dimension = here
        }
        ticks++
        if (ticks % 10 == 0L) {
            for (detection in detections) {
                if (detection.changed || (fitterFor(detection.type) != null && detection.fitDirty)) update(detection, level)
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

    private fun update(detection: Detection, level: net.minecraft.world.level.Level) {
        detection.changed = false
        val before = detection.box
        val fitter = fitterFor(detection.type)
        if (fitter != null) {
            if (detection.blocks.size < TemplateFit.MIN_BLOCKS || ticks - detection.lastFitTick < 40) return
            if (Specs.of(detection.type).biomeAsWhole && detection.inBiome < BIOME_BLOCKS) {
                if (detection.blocks.size >= 100 && !detection.biomeLogged) {
                    detection.biomeLogged = true
                    Log.info("{} #{} at {}: only {} of {} blocks in its biomes, so not matched", detection.type.id, detection.id, detection.bounds, detection.inBiome, detection.count)
                }
                return
            }
            detection.fitDirty = false
            detection.lastFitTick = ticks
            val started = System.nanoTime()
            // An outpost's cages, tents and log piles are the same wood as its tower; only the
            // tower stands well above its base, so only those blocks vote.
            val base = detection.bounds?.minY ?: 0
            val match = when (detection.type) {
                StructureType.OUTPOST -> fitter.fit(detection, level) { key -> net.minecraft.core.BlockPos.getY(key) >= base + OUTPOST_TOWER_FROM }
                // The town centre is around the bell; streets and houses further out do not vote.
                // Each bell on its own: a village can have more than one, and pooling the blocks
                // around both drowned out the town centre in testing.
                StructureType.VILLAGE -> detection.blocks.long2ObjectEntrySet()
                    .filter { it.value == net.minecraft.world.level.block.Blocks.BELL }.map { it.longKey }
                    .firstNotNullOfOrNull { bell -> fitter.fit(detection, level) { key -> near(bell, key, TOWN_CENTRE_REACH) } }
                else -> fitter.fit(detection, level)
            }
            val millis = (System.nanoTime() - started) / 1_000_000
            if (match != null) {
                detection.variant = match.template.name
                detection.box = when (detection.type) {
                    StructureType.OUTPOST -> Recognise.outpostBox(match.box)
                    // The town centre (or tower) proves it; the rest is everything seen around it.
                    StructureType.VILLAGE, StructureType.TRAIL_RUINS, StructureType.END_CITY -> detection.bounds
                    else -> match.box
                }
            }
            // A village is proved once; after that its box is everything seen, as more of it loads.
            if (match == null && detection.type in PROVED_THEN_SEEN && detection.variant != null) detection.box = detection.bounds
            if (match?.box != before || (match == null && detection.blocks.size >= 100)) {
                Log.info("{} #{} at {}: {} in {} ms ({})", detection.type.id, detection.id, detection.bounds,
                    match?.let { "${it.template.name}, box ${detection.box}" } ?: "no template fits", millis, fitter.lastReport)
                if (match == null && detection.type == StructureType.VILLAGE) Log.info("  bells seen: {}", detection.blocks.long2ObjectEntrySet()
                    .filter { it.value == net.minecraft.world.level.block.Blocks.BELL }
                    .joinToString { "${net.minecraft.core.BlockPos.getX(it.longKey)} ${net.minecraft.core.BlockPos.getY(it.longKey)} ${net.minecraft.core.BlockPos.getZ(it.longKey)}" })
            }
        } else {
            detection.box = Recognise.box(detection)
            val bricks = detection.bounds
            if (detection.type == StructureType.FORTRESS && detection.box != null && bricks != null) {
                val crossroads = FortressPieces.crossroads(detection, level)
                if (crossroads.size != detection.pieces.size) Log.info("Fortress #{}: {} crossroads {}", detection.id, crossroads.size, crossroads)
                detection.pieces = crossroads.map { Piece(FortressPieces.CROSSROADS, it) }
                detection.box = FortressPieces.outerBox(bricks, crossroads)
                // Every fortress starts from a crossroads; nether bricks without one are a build.
                if (crossroads.isEmpty()) detection.box = null
            }
            if (detection.box != null && before == null) {
                Log.info("Recognised {} #{} from {} blocks ({}), seen {}: box {}", detection.type.id, detection.id, detection.count, describeKinds(detection), detection.bounds, detection.box)
            }
        }
        val box = detection.box ?: return
        if (detection.storedId == null) {
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
        val stored = detection.storedId?.let(StructureStore::byId) ?: return
        // More seen of a structure whose shape comes from what is seen makes its box bigger; an
        // exact box (a monument) can only get more certain, and a wreck's matched box stays.
        val better = when {
            detection.type == StructureType.SHIPWRECK -> stored.box
            Specs.of(detection.type).reach == null -> box
            // A fortress's bottom is worked out, not seen: the new one replaces any older guess.
            detection.type == StructureType.FORTRESS -> stored.box.union(box).let { if (box.minY == 48) it.copy(minY = 48) else it }
            else -> stored.box.union(box)
        }
        // Pieces are only ever added, so they stay after the structure is torn down.
        val pieces = stored.pieces + detection.pieces.filter { new -> stored.pieces.none { it.box == new.box } }
        if (better != stored.box || pieces.size != stored.pieces.size) StructureStore.put(stored.copy(box = better, pieces = pieces))
    }

    private fun discover(detection: Detection) {
        val box = detection.box ?: return
        if (!StructureStore.isOpen) {
            Log.warn("Inside {} #{}, but no structures file is open (not in a world?)", detection.type.id, detection.id)
            detection.storedId = ""
            return
        }
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
        if (Config.announce) {
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
        else -> null
    }

    /** Kinds proved once by one piece matching, whose box is then everything seen around it. */
    private val PROVED_THEN_SEEN = setOf(StructureType.VILLAGE, StructureType.TRAIL_RUINS, StructureType.END_CITY)

    /** How far from a bell, sideways, a village's town centre reaches. */
    private const val TOWN_CENTRE_REACH = 12

    private fun near(a: Long, b: Long, reach: Int): Boolean {
        val dx = net.minecraft.core.BlockPos.getX(a) - net.minecraft.core.BlockPos.getX(b)
        val dz = net.minecraft.core.BlockPos.getZ(a) - net.minecraft.core.BlockPos.getZ(b)
        val dy = net.minecraft.core.BlockPos.getY(a) - net.minecraft.core.BlockPos.getY(b)
        return dx * dx + dz * dz <= reach * reach && Math.abs(dy) <= reach
    }

    /** Blocks in an allowed biome a group needs, when the biome is asked of the whole group. */
    const val BIOME_BLOCKS = 20

    /** Blocks this far above an outpost's base can only be its tower. */
    private const val OUTPOST_TOWER_FROM = 6

    /** Recognised but not yet discovered, in [dimension]: shown faintly when that is turned on. */
    fun undiscovered(dimension: String): List<Detection> =
        detections.filter { it.dimension == dimension && it.box != null && it.storedId == null }
}
