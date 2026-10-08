package com.skystormer.skysstructuremap

import java.util.EnumMap

/**
 * Signature blocks gathered into groups, one [Detection] for each structure they could be: blocks
 * close to a group of the same kind join it, and two groups a block joins become one, unless one
 * was split from the other ([splitOff]). [Tracker]
 * keeps one of these for the chunks around you, and a scan of Bobby's cache ([BobbyScan]) one of
 * its own for each dimension it reads.
 *
 * To monitor: nothing stops a group growing far past any one structure. Where players have built
 * all over (paths, doors, barrels, workstations), village blocks chain from one build to the next,
 * and a group becomes one [Detection] however many villages it holds, tried from only a few
 * anchors (`TemplateFit.fitAnchored`). On a busy survival server, a scan of Bobby's cache has
 * joined the whole area around spawn, well over a thousand blocks across, into one village group
 * and found no village in it, missing a real one there that live detection, which only sees the
 * chunks around you, had found. Ancient cities side by side chained the same way, and are now
 * split once one city's centre is known ([splitOff], `Tracker.splitOffNextCity`). Villages are not:
 * a house fits anywhere in a village, so a box from one may not cover the village, and splitting
 * there could cut a village in two. Watch for groups like it ("village #… at Box(…)" lines far
 * wider than a village in the log).
 */
class Groups(private var nextId: Int = 1) {

    /** Every group, oldest first. */
    var detections: List<Detection> = emptyList()
        private set

    /** The same, by kind, so a block is only compared with groups it could join. */
    private val byType = EnumMap<StructureType, MutableList<Detection>>(StructureType::class.java)

    fun clear() {
        detections = emptyList()
        byType.clear()
    }

    fun add(type: StructureType, dimension: String, found: List<ChunkScanner.Found>, keepBlocks: Boolean) {
        val ofType = byType.getOrPut(type) { ArrayList() }
        val reach = Specs.of(type).merge
        for (group in found.groupBy { Detection.cellKey(it.x, it.y, it.z) }.values) {
            var cell = Box.of(group[0].x, group[0].y, group[0].z)
            for (f in group) cell = cell.including(f.x, f.y, f.z)
            val near = ofType.filter { it.bounds?.grow(reach)?.overlaps(cell) == true }
            // The nearest takes the blocks; others near them join it, unless split from it.
            val target = near.minByOrNull { it.bounds!!.gap(cell) } ?: Detection(nextId++, type, dimension).also {
                detections = detections + it
                ofType.add(it)
            }
            val joining = near.filter { it !== target && it !in target.apart }
            if (joining.isNotEmpty()) {
                for (other in joining) target.absorb(other)
                val gone = joining.toSet()
                detections = detections - gone
                ofType.removeAll(gone)
                Log.info("Joined {} groups of {} blocks into #{}", joining.size + 1, type.id, target.id)
            }
            for (f in group) target.add(f.x, f.y, f.z, f.block, keepBlocks, f.inBiome)
        }
    }

    /**
     * Moves [detection]'s blocks outside [keep] into a group of their own, when there are at least
     * [least] of them, and keeps the two apart from then on: blocks of two structures that chain
     * into one group (two ancient cities side by side), once one is known, leave the other to be
     * matched by itself. Answers the new group.
     */
    fun splitOff(detection: Detection, keep: Box, least: Int): Detection? {
        if (detection.countOutside(keep) < least) return null
        val other = Detection(nextId++, detection.type, detection.dimension)
        detection.moveOutside(keep, other)
        detection.apart.add(other)
        other.apart.add(detection)
        detections = detections + other
        byType.getOrPut(detection.type) { ArrayList() }.add(other)
        Log.info("Split {} blocks of {} #{} beyond {} into #{}", other.count, detection.type.id, detection.id, keep, other.id)
        return other
    }
}
