package com.skystormer.skysstructuremap

import net.minecraft.client.Minecraft
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.world.phys.AABB

/**
 * The boxes that structures with mobs of their own spawn them in, like MiniHUD's structure
 * boxes but worked out from what you have seen rather than from the seed.
 *
 * The game gives four structures their own spawns: fortresses (blazes, wither skeletons, skeletons,
 * magma cubes, zombified piglins) inside each of their pieces, and ocean monuments (guardians),
 * pillager outposts (pillagers) and witch huts (witches, cats) inside their whole box. So a
 * fortress shows its own box and its crossroads, the rest their box.
 *
 * Drawn in the world around you with the game's own debug shapes, and on the map by [Outlines].
 */
object SpawnBoxes {

    /** The kinds of structure with spawns of their own. */
    val TYPES = setOf(StructureType.FORTRESS, StructureType.MONUMENT, StructureType.OUTPOST, StructureType.WITCH_HUT)

    /** The colour a fortress's crossroads are drawn in, apart from its own box. */
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

    /**
     * Called every client tick: the boxes near you, for the game to draw over the next tick. The
     * game draws whatever is added to its per-tick shapes, debug screen or not.
     */
    fun tick(minecraft: Minecraft) {
        if (!Config.spawnBoxesInWorld) return
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        try {
            val dimension = level.dimension().identifier().toString()
            val near = Markers.visibleIn(dimension).filter {
                hasSpawns(it.type) && it.box.horizontalDistance(player.x, player.z) <= RANGE
            }
            if (near.isEmpty()) return
            minecraft.collectPerTickGizmos().use {
                for (marker in near) {
                    for (drawn in boxesOf(marker)) {
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
}
