package com.skystormer.skysstructuremap

import net.minecraft.client.Minecraft
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.world.phys.AABB

/**
 * The boxes that structures with mobs of their own spawn them in, like MiniHUD's structure
 * boxes but worked out from what you have seen.
 *
 * The game gives four structures their own spawns: fortresses (blazes, wither skeletons, skeletons,
 * magma cubes, zombified piglins) inside each of their pieces, and ocean monuments (guardians),
 * pillager outposts (pillagers) and witch huts (witches, cats) inside their whole box. So a
 * fortress shows its own box and each of its pieces, the rest their box.
 *
 * Drawn in the world around you with the game's own debug shapes, and on the map by [Outlines].
 */
object SpawnBoxes {

    /** The kinds of structure with spawns of their own. */
    val TYPES = setOf(StructureType.FORTRESS, StructureType.MONUMENT, StructureType.OUTPOST, StructureType.WITCH_HUT)

    /** The colour a fortress's pieces are drawn in, apart from its own box. */
    const val PIECE_COLOUR = 0xFFFF9020.toInt()

    /** How far away, sideways, boxes are still drawn in the world. */
    private const val RANGE = 192.0

    fun hasSpawns(type: StructureType) = type in TYPES

    /** A box to draw and its colour. */
    class Drawn(val box: Box, val colour: Int)

    /** What to draw for [marker]: its own box, then its pieces. Empty for a structure without spawns of its own. */
    fun boxesOf(marker: Marker): List<Drawn> {
        if (!hasSpawns(marker.type)) return emptyList()
        val colour = 0xFF000000.toInt() or (marker.type.colour and 0xFFFFFF)
        return listOf(Drawn(marker.box, colour)) + marker.pieces.map { Drawn(it.box, PIECE_COLOUR) }
    }

    private var failed = false

    /** The markers with spawns within reach of chunk [nearX], [nearZ], from the list [nearFrom]. */
    private var near: List<Marker> = emptyList()
    private var nearFrom: List<Marker>? = null
    private var nearX = 0
    private var nearZ = 0

    /**
     * Called every client tick: the boxes near you, for the game to draw over the next tick. The
     * game draws whatever is added to its per-tick shapes, debug screen or not.
     */
    fun tick(minecraft: Minecraft) {
        if (!Config.spawnBoxesInWorld) return
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        try {
            val dimension = dimensionId(level)
            // Those within reach of the chunk you are in, looked for again only when you cross
            // into another or the markers change, rather than among all 25,000 every tick.
            val chunkX = player.blockX shr 4
            val chunkZ = player.blockZ shr 4
            val markers = Markers.visibleIn(dimension)
            if (markers !== nearFrom || chunkX != nearX || chunkZ != nearZ) {
                val middleX = chunkX * 16 + 8.0
                val middleZ = chunkZ * 16 + 8.0
                near = markers.filter { hasSpawns(it.type) && it.box.horizontalDistance(middleX, middleZ) <= RANGE + CHUNK_REACH }
                nearFrom = markers
                nearX = chunkX
                nearZ = chunkZ
            }
            val within = near.filter { it.box.horizontalDistance(player.x, player.z) <= RANGE }
            if (within.isEmpty()) return
            minecraft.collectPerTickGizmos().use {
                for (marker in within) {
                    for (drawn in marker.boxes) {
                        val b = drawn.box
                        Gizmos.cuboid(
                            AABB(b.minX.toDouble(), b.minY.toDouble(), b.minZ.toDouble(), b.maxX + 1.0, b.maxY + 1.0, b.maxZ + 1.0),
                            GizmoStyle.stroke(drawn.colour, LINE_WIDTH),
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            if (!failed) {
                failed = true
                Log.error("Could not draw spawn boxes in the world (logged once)", e)
            }
        }
    }

    private const val LINE_WIDTH = 2f
    /** How far a point of a chunk can be from its middle, sideways. */
    private const val CHUNK_REACH = 12.0
}
