package com.skystormer.skysstructuremap

import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.nio.file.Files

/**
 * TEMPORARY, for checking in game: draws the nether fortress pieces listed in
 * `config/skysstructuremap/debug-fortress-pieces.json` (labelled offline, each piece type in its own
 * colour, named above its box), like MiniHUD's structure boxes. Switched with
 * `/skysstructuremap debug pieces`. To be replaced by the mod finding the pieces itself once the
 * labelling is confirmed; nothing else uses it.
 */
object DebugPieces {

    private class Piece(val name: String, val id: String, val fit: Double, val box: AABB)

    private var pieces: List<Piece> = emptyList()
    private var on = false
    private var failed = false

    /** Turns the overlay on (reading the file again) or off, and says what came of it. */
    fun toggle() {
        if (on) {
            on = false
            return Menus.tell("Fortress piece overlay off")
        }
        val path = FabricLoader.getInstance().configDir.resolve("skysstructuremap/debug-fortress-pieces.json")
        if (!Files.exists(path)) return Menus.tell("No $path to show")
        pieces = try {
            JsonParser.parseString(Files.readString(path)).asJsonArray.map { e ->
                val a = e.asJsonArray
                val b = a[3].asJsonArray.map { it.asInt }
                Piece(a[0].asString, a[1].asString, a[2].asDouble, AABB(b[0].toDouble(), b[1].toDouble(), b[2].toDouble(), b[3] + 1.0, b[4] + 1.0, b[5] + 1.0))
            }
        } catch (e: Exception) {
            Log.error("Could not read $path", e)
            return Menus.tell("Could not read $path: $e")
        }
        on = true
        Menus.tell("Fortress piece overlay on: ${pieces.size} pieces (in the Nether, within $RANGE blocks)")
    }

    fun tick(minecraft: Minecraft) {
        if (!on) return
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        if (level.dimension().identifier().toString() != NETHER) return
        try {
            minecraft.collectPerTickGizmos().use {
                for (p in pieces) {
                    val c = p.box.center
                    if (Math.abs(c.x - player.x) > RANGE || Math.abs(c.z - player.z) > RANGE) continue
                    val colour = COLOURS[p.id] ?: 0xFFFFFFFF.toInt()
                    Gizmos.cuboid(p.box, GizmoStyle.stroke(colour, 2f))
                    val label = if (p.fit < 0.999) "${p.name} (${Math.round(p.fit * 100)}%)" else p.name
                    Gizmos.billboardText(label, Vec3(c.x, p.box.maxY + 0.5, c.z), TextGizmo.Style.forColorAndCentered(colour).withScale(0.6f))
                }
            }
        } catch (e: Throwable) {
            if (!failed) {
                failed = true
                Log.error("Could not draw the fortress piece overlay (logged once)", e)
            }
        }
    }

    private const val RANGE = 96

    /** A colour to each piece type, so neighbours stand apart. */
    private val COLOURS = mapOf(
        "nebcr" to 0xFFFF3030.toInt(),   // crossroads: red
        "nerc" to 0xFFFF9020.toInt(),    // room crossing: orange
        "nebs" to 0xFFFFFF40.toInt(),    // bridge: yellow
        "nebef" to 0xFFA0A0A0.toInt(),   // bridge end: grey
        "nesr" to 0xFF40FF40.toInt(),    // stairs room: green
        "nemt" to 0xFFFF40FF.toInt(),    // blaze spawner: magenta
        "nece" to 0xFFFFFFFF.toInt(),    // castle entrance: white
        "necsr" to 0xFF60FFC0.toInt(),   // stalk room: mint
        "nectb" to 0xFF40C0FF.toInt(),   // T balcony: sky blue
        "neccs" to 0xFF8080FF.toInt(),   // corridor stairs: lavender
        "nesc" to 0xFF4060FF.toInt(),    // corridor: blue
        "nesclt" to 0xFF00E0E0.toInt(),  // corridor turn: cyan
        "nescrt" to 0xFF00E0E0.toInt(),
        "nescsc" to 0xFFC060FF.toInt(),  // corridor crossing: purple
    )
}
