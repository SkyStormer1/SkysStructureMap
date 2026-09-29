package com.skystormer.skysstructuremap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The tint's rectangles cover exactly the chunks Bobby saved: none left out, none added. */
class BobbyCoverageTest {

    private fun covered(regions: Map<Long, IntArray>): Set<Pair<Int, Int>> {
        val rectangles = BobbyCoverage.rectanglesOf(regions)
        val out = HashSet<Pair<Int, Int>>()
        for (i in rectangles.indices step 4) {
            for (x in 0 until rectangles[i + 2]) for (z in 0 until rectangles[i + 3]) {
                check(out.add(rectangles[i] + x to rectangles[i + 1] + z)) { "chunk covered twice" }
            }
        }
        return out
    }

    @Test
    fun rectanglesCoverExactlyTheSavedChunks() {
        val random = java.util.Random(7)
        val regions = HashMap<Long, IntArray>()
        val saved = HashSet<Pair<Int, Int>>()
        for ((rx, rz) in listOf(0 to 0, -1 to -1, 1 to -1, -3 to 2)) {
            val times = IntArray(1024)
            // Blocks and scattered chunks, so runs both join down the rows and break up.
            for (i in 0 until 1024) {
                val x = i and 31
                val z = i shr 5
                if ((x in 4..20 && z in 3..25) || random.nextInt(4) == 0) {
                    times[i] = 1_700_000_000
                    saved.add(rx * 32 + x to rz * 32 + z)
                }
            }
            regions[BobbyCoverage.key(rx, rz)] = times
        }
        assertEquals(saved, covered(regions))
    }

    @Test
    fun aWholeRegionIsOneRectangle() {
        val regions = mapOf(BobbyCoverage.key(1, -1) to IntArray(1024) { 5 })
        assertEquals(listOf(32, -32, 32, 32), BobbyCoverage.rectanglesOf(regions).toList())
    }
}
