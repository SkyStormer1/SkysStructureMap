package com.skystormer.skysstructuremap

/**
 * Structures of one kind told apart among one group's pieces by how the game builds them: from one
 * start piece each, every further piece joined onto one before it. Each structure is what is joined
 * to its start, grown out from all the starts at once, so a piece is its nearest start's by joins
 * where two structures touch, and a stray join between their pieces is cut at the middle. A piece
 * joined to no start (its way to one not seen, or dug out) is the start's nearest it. Fortresses
 * ([FortressPieces.split]) and mineshafts ([MineshaftPieces]) are each built this way.
 */
object JoinedPieces {

    /**
     * [pieces] as one list for each of [starts] (which are among them), by [joined]. Each of [starts]
     * is one structure's: more than one piece where which of them is its start cannot be told.
     */
    fun byStarts(pieces: List<Piece>, starts: List<List<Piece>>, joined: (Piece, Piece) -> Boolean): List<List<Piece>> {
        val index = pieces.withIndex().associate { it.value to it.index }
        val links = Array(pieces.size) { ArrayList<Int>() }
        for (i in pieces.indices) for (j in i + 1 until pieces.size) {
            if (joined(pieces[i], pieces[j])) {
                links[i].add(j)
                links[j].add(i)
            }
        }
        val owner = IntArray(pieces.size) { -1 }
        var frontier = starts.flatMapIndexed { s, start -> start.mapNotNull { piece -> index[piece]?.also { owner[it] = s } } }
        while (frontier.isNotEmpty()) {
            val next = ArrayList<Int>()
            for (i in frontier) for (j in links[i]) {
                if (owner[j] < 0) {
                    owner[j] = owner[i]
                    next.add(j)
                }
            }
            frontier = next
        }
        for (i in pieces.indices) {
            if (owner[i] >= 0) continue
            val box = pieces[i].box
            owner[i] = starts.indices.minBy { s ->
                starts[s].minOf { maxOf(Math.abs(box.centreX - it.box.centreX), Math.abs(box.centreZ - it.box.centreZ)) }
            }
        }
        return starts.indices.map { s -> pieces.filterIndexed { i, _ -> owner[i] == s } }.filter { it.isNotEmpty() }
    }
}
