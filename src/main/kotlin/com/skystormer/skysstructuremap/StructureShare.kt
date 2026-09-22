package com.skystormer.skysstructuremap

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import java.util.Base64
import java.util.UUID

/**
 * Sharing a structure in chat: a plain line anyone can read, such as
 * `Woodland Mansion: -894 90 742 (Overworld) · box -924 75 718 to -865 105 767`. Players without
 * this mod see just that; anyone with it sees an [Add to my map] button beside it, which puts the
 * structure on their map with its icon and box. (An earlier version put a coded string on the end,
 * which read as random characters to everyone else; those old lines are still understood.)
 *
 * The line carries the structure's own dimension, so it always lands on the right map. Nothing is
 * added without the other player clicking.
 */
object StructureShare {

    /** How old versions marked a shared line: the code followed it. */
    private const val TAG = "SSM1:"

    /** Between the readable part of a shared line and its box. */
    private const val BOX = " · box "

    /** The client-side command the add button runs. */
    const val COMMAND = "skysstructuremap"

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    /** What was shared: enough to make a saved structure. */
    class Shared(val type: StructureType, val dimension: String, val box: Box, val variant: String?)

    /** The chat line for [marker]: its name, where it is and its corners, readable to everyone. */
    fun message(marker: Marker): String = line(marker.name, marker.dimension, marker.box, marker.y)

    fun line(name: String, dimension: String, b: Box, y: Int): String =
        "$name: ${b.centreX} $y ${b.centreZ} (${dimensionName(dimension) ?: "Overworld"})$BOX${b.minX} ${b.minY} ${b.minZ} to ${b.maxX} ${b.maxY} ${b.maxZ}"

    /** The code the add button's command carries (it never goes into chat). */
    fun encode(shared: Shared): String {
        val json = JsonObject()
        json.addProperty("t", shared.type.id)
        json.addProperty("d", shared.dimension)
        val b = shared.box
        json.add("b", JsonArray().also { a -> listOf(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ).forEach(a::add) })
        shared.variant?.let { json.addProperty("v", it) }
        return encoder.encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    /** Longest names first, so "End City" is never read out of "End Gateway" or the like. */
    private val names by lazy { StructureType.entries.sortedByDescending { it.displayName.length } }

    private val LINE = Regex("""(.*?)(\S[^:]*): (-?\d+) (-?\d+) (-?\d+) \(([^)]+)\) · box (-?\d+) (-?\d+) (-?\d+) to (-?\d+) (-?\d+) (-?\d+)\s*$""")

    /** A shared structure in a readable line of chat, and the part to show, or null when there is none. */
    fun readLine(text: String): Pair<String, Shared>? {
        val m = LINE.find(text) ?: return null
        val g = m.groupValues
        val named = g[2]
        val type = names.firstOrNull { named.endsWith(it.displayName) } ?: return null
        val n = (7..12).map { g[it].toInt() }
        val box = Box(n[0], n[1], n[2], n[3], n[4], n[5])
        if (box.minX > box.maxX || box.minY > box.maxY || box.minZ > box.maxZ) return null
        val dimension = when (g[6]) {
            "Overworld" -> OVERWORLD
            "Nether" -> NETHER
            "End" -> END
            else -> g[6].let { if (':' in it) it else "minecraft:$it" }
        }
        return text.substringBefore(BOX).trim() to Shared(type, dimension, box, null)
    }

    /** A shared structure from a code, or null if it is not one of ours or is damaged. */
    fun decode(code: String): Shared? = try {
        val json = JsonParser.parseString(String(decoder.decode(code.trim()), Charsets.UTF_8)).asJsonObject
        val b = json.getAsJsonArray("b").map { it.asInt }
        require(b.size == 6)
        Shared(
            type = StructureType.byId(json.get("t").asString) ?: error("unknown type"),
            dimension = json.get("d").asString,
            box = Box(b[0], b[1], b[2], b[3], b[4], b[5]),
            variant = json.get("v")?.asString,
        )
    } catch (e: Exception) {
        null
    }

    /** The code in a line of chat, or null when there is none. */
    fun codeIn(text: String): String? {
        val start = text.indexOf(TAG)
        if (start < 0) return null
        return text.substring(start + TAG.length).trim().takeWhile { !it.isWhitespace() }.takeIf { it.isNotEmpty() }
    }

    /**
     * Called for every line of chat. A shared structure is shown as a tidy message with an add
     * button in place of the line; anything else is left alone.
     *
     * @return whether the original line should still be shown.
     */
    fun onChat(text: String): Boolean {
        val (said, shared) = readLine(text) ?: run {
            // An old line, with the code on the end: shown without it.
            val shared = codeIn(text)?.let(::decode) ?: return true
            text.substringBefore(TAG).trim().removeSuffix("·").trim() to shared
        }
        val code = encode(shared)
        val b = shared.box
        Minecraft.getInstance().gui.chatListener().handleSystemMessage(
            Component.literal("$said  ").append(
                Component.literal("[Add to my map]").withStyle {
                    it.withColor(shared.type.colour and 0xFFFFFF).withBold(true)
                        .withClickEvent(ClickEvent.RunCommand("/$COMMAND $code"))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal(
                            "${shared.type.displayName}\n${b.centreX} ${waypointY(shared.type, b)} ${b.centreZ}\n" +
                                "${b.describeSize()}\n${dimensionName(shared.dimension)}\nClick to put it on your map"
                        )))
                }
            ),
            false,
        )
        return false
    }

    /** Adds a shared structure to your map, unless it is already there. Run by the add button. */
    fun accept(code: String) {
        val shared = decode(code) ?: return Menus.say("That structure code could not be read.")
        if (!StructureStore.isOpen) return Menus.say("Join a world first.")
        val existing = StructureStore.inDimension(shared.dimension)
            .firstOrNull { it.type == shared.type && Specs.sameStructure(it.type, it.box, shared.box) }
        if (existing != null) return Menus.say("That ${shared.type.displayName} is already on your map.")
        // Adding it yourself undoes having deleted it.
        StructureStore.undelete(shared.type, shared.dimension, shared.box)
        val structure = Structure(UUID.randomUUID().toString(), shared.type, shared.dimension, shared.box, System.currentTimeMillis(), shared.variant)
        StructureStore.put(structure)
        // It may already be in view but not visited: it is the saved one now.
        Tracker.detections.filter { it.type == shared.type && it.dimension == shared.dimension && it.box?.overlaps(shared.box) == true }
            .forEach { it.storedId = structure.id }
        Log.info("Added a shared {} at {}", shared.type.id, shared.box)
        Menus.say("Added the ${shared.type.displayName} to your ${dimensionName(shared.dimension)} map")
    }

    /** Sends [marker] to everyone as a line of chat. Only called from the share screen, by a click. */
    fun shareWithEveryone(marker: Marker): Boolean {
        val connection = Minecraft.getInstance().connection ?: return false
        val line = message(marker)
        if (line.length > MAX_CHAT) return false.also { Menus.say("That structure is too long to share in one line of chat") }
        connection.sendChat(line)
        Log.info("Shared {} with everyone", marker.name)
        return true
    }

    /** Private messages still to send: (player, line), one every [SEND_EVERY] ticks. */
    private val waiting = ArrayDeque<Pair<String, String>>()

    /**
     * Sends [marker] privately to each of [players], with [Config.privateShareCommand]. They go one
     * at a time, because a burst of messages looks like spam to a server and can get you kicked.
     */
    fun shareWith(marker: Marker, players: List<String>): Boolean {
        if (Minecraft.getInstance().connection == null) return false
        val line = message(marker)
        for (player in players) waiting.addLast(player to line)
        return true
    }

    private var ticks = 0

    /** Called every client tick: sends the next waiting private message. */
    fun tick() {
        if (waiting.isEmpty()) return
        if (++ticks < SEND_EVERY) return
        ticks = 0
        val connection = Minecraft.getInstance().connection ?: return waiting.clear()
        val (player, line) = waiting.removeFirst()
        val command = "${Config.privateShareCommand} $player $line"
        if (command.length > MAX_CHAT) {
            Menus.say("That structure is too long to send privately")
            return waiting.clear()
        }
        connection.sendCommand(command)
        Log.info("Sent a structure to {}", player)
    }

    /** Nothing left to send when leaving a world. */
    fun clear() = waiting.clear()

    /** What a server will take in one line of chat, and in one command. */
    const val MAX_CHAT = 256

    /** Ticks between private messages: half a second, which no server counts as spam. */
    private const val SEND_EVERY = 10
}
