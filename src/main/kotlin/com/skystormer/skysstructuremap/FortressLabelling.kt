package com.skystormer.skysstructuremap

/** Labels the fortresses around you ([FortressPieces.label]) on a thread of its own ([OffThread]). */
object FortressLabelling : OffThread<List<Piece>>("pieces", everyMillis = 5000L) {

    /** Asks for [detection]'s pieces, unless already asked for what has been seen of it. Main thread. */
    fun request(detection: Detection, bricks: Box) = request(detection, bricks, { FortressPieces.label(it, bricks) }) { pieces ->
        if (pieces.size != detection.pieces.size) Log.info("Fortress #{}: {} pieces", detection.id, pieces.size)
        detection.pieces = pieces
    }
}
