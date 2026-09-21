package com.skystormer.skysstructuremap

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.UUID

/**
 * Turns the blocks [ChunkScanner] finds into structures: groups them, decides when a group is
 * recognised, and saves it as discovered once your hitbox is inside its box.
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
        val keepBlocks = type == StructureType.SHIPWRECK
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
            for (f in group) target.add(f.x, f.y, f.z, f.block, keepBlocks)
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
                if (detection.changed || (detection.type == StructureType.SHIPWRECK && detection.fitDirty)) update(detection, level)
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
        if (detection.type == StructureType.SHIPWRECK) {
            if (detection.blocks.size < ShipwreckFit.MIN_BLOCKS || ticks - detection.lastFitTick < 40) return
            detection.fitDirty = false
            detection.lastFitTick = ticks
            val started = System.nanoTime()
            val match = ShipwreckFit.fit(detection, level)
            val millis = (System.nanoTime() - started) / 1_000_000
            if (match != null) {
                detection.box = match.box
                detection.variant = match.template.name
            }
            if (match?.box != before || (match == null && detection.blocks.size >= 100)) {
                Log.info("Shipwreck #{} at {}: {} in {} ms ({})", detection.id, detection.bounds,
                    match?.let { "${it.template.name}, box ${it.box}" } ?: "no template fits", millis, ShipwreckFit.lastReport)
            }
        } else {
            detection.box = Recognise.box(detection)
            if (detection.box != null && before == null) {
                Log.info("Recognised {} #{} from {} blocks ({}), seen {}: box {}", detection.type.id, detection.id, detection.count, describeKinds(detection), detection.bounds, detection.box)
            }
        }
        val box = detection.box ?: return
        if (detection.storedId == null) {
            // Found again after rejoining, or seen from another side: it is the one already saved.
            val saved = StructureStore.inDimension(detection.dimension)
                .firstOrNull { it.type == detection.type && it.box.grow(8).overlaps(box) }
            if (saved != null) {
                detection.storedId = saved.id
                Log.info("{} #{} is the one discovered before ({})", detection.type.id, detection.id, saved.id)
            }
        }
        val stored = detection.storedId?.let(StructureStore::byId) ?: return
        // More seen of a structure whose shape comes from what is seen makes its box bigger; an
        // exact box (a monument) can only get more certain, and a wreck's matched box stays.
        val better = when {
            detection.type == StructureType.SHIPWRECK -> stored.box
            Specs.of(detection.type).reach == null -> box
            else -> stored.box.union(box)
        }
        if (better != stored.box) StructureStore.put(stored.copy(box = better))
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

    /** Recognised but not yet discovered, in [dimension]: shown faintly when that is turned on. */
    fun undiscovered(dimension: String): List<Detection> =
        detections.filter { it.dimension == dimension && it.box != null && it.storedId == null }
}
