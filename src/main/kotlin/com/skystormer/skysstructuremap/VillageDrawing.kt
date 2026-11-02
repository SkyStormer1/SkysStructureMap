package com.skystormer.skysstructuremap

/** Draws the villages around you ([VillagePieces.draw]) on a thread of its own ([OffThread]). */
object VillageDrawing : OffThread<VillagePieces.Drawn>("drawing", everyMillis = 10_000L) {

    /**
     * Asks for the village [first] (a piece of [detection] that fitted) is a piece of, unless already
     * asked for what has been seen of it, and gives it to [done]. Main thread.
     */
    fun request(
        detection: Detection, first: TemplateFit.Match, centreRefusal: (TemplateFit.Match, BlockSource) -> String?, done: (VillagePieces.Drawn) -> Unit,
    ) = request(detection, VillagePieces.reachFrom(first), { level -> VillagePieces.draw(level, first) { centreRefusal(it, level) } }, done)
}
