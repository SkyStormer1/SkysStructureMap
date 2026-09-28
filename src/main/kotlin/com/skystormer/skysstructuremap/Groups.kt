package com.skystormer.skysstructuremap

import java.util.EnumMap

/**
 * Signature blocks gathered into groups, one [Detection] for each structure they could be: blocks
 * close to a group of the same kind join it, and two groups a block joins become one. [Tracker]
 * keeps one of these for the chunks around you, and a scan of Bobby's cache ([BobbyScan]) one of
 * its own for each dimension it reads.
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
            val target = near.firstOrNull() ?: Detection(nextId++, type, dimension).also {
                detections = detections + it
                ofType.add(it)
            }
            if (near.size > 1) {
                for (other in near.drop(1)) target.absorb(other)
                val gone = near.drop(1).toSet()
                detections = detections - gone
                ofType.removeAll(gone)
                Log.info("Joined {} groups of {} blocks into #{}", near.size, type.id, target.id)
            }
            for (f in group) target.add(f.x, f.y, f.z, f.block, keepBlocks, f.inBiome)
        }
    }
}
