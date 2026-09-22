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
 * Sharing a structure the way Xaero shares a waypoint and Sky's Map Shapes shares a shape: a line
 * of chat with the name and coordinates for anyone to read, and a short code on the end. Anyone
 * with this mod sees a message with an [Add to my map] button instead, which puts the structure on
 * their map with its icon and box.
 *
 * The code carries the structure's own dimension, so it always lands on the right map. Nothing is
 * added without the other player clicking.
 */
object StructureShare {

    /** The marker in a shared line of chat, followed by the code. */
    private const val TAG = "SSM1:"

    /** The client-side command the add button runs. */
    const val COMMAND = "skysstructuremap"

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    /** What was shared: enough to make a saved structure. */
    class Shared(val type: StructureType, val dimension: String, val box: Box, val variant: String?)

    /** The chat line for [marker]: readable to everyone, with the code on the end. */
    fun message(marker: Marker): String = "${marker.name}: ${Menus.coordinates(marker)} · $TAG${encode(marker)}"

    fun encode(marker: Marker): String {
        val json = JsonObject()
        json.addProperty("t", marker.type.id)
        json.addProperty("d", marker.dimension)
        val b = marker.box
        json.add("b", JsonArray().also { a -> listOf(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ).forEach(a::add) })
        (marker.structure?.variant ?: marker.detection?.variant)?.let { json.addProperty("v", it) }
        return encoder.encodeToString(json.toString().toByteArray(Charsets.UTF_8))
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
        val code = codeIn(text) ?: return true
        val shared = decode(code) ?: return true
        // Who said it and the readable part, as chat shows it, without the code on the end.
        val said = text.substringBefore(TAG).trim().removeSuffix("·").trim()
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
