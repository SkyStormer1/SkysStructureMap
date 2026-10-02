package com.skystormer.skysstructuremap

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/** Display settings, the same for every server: `config/skysstructuremap.json`. */
object Config {

    private val GSON = GsonBuilder().setPrettyPrinting().create()
    private val path get() = FabricLoader.getInstance().configDir.resolve("skysstructuremap.json")

    /** The kinds shown on the map; the legend's filter. */
    var shown: Set<StructureType> = StructureType.entries.toSet()

    /** The kinds the legend's Hide switch hid, so pressing it again brings back only those. */
    var hiddenByHide: Set<StructureType> = emptySet()
        private set

    var legendOpen = true

    /** Where the legend sits: its right edge this far in from the screen's, its top this far down. Moved by dragging its header. */
    var legendRight = 30
    var legendTop = 4

    /** How many lines the legend shows before scrolling. Changed by dragging its bottom edge. */
    var legendRows = 6

    /** The legend's size (1 = the game's own), the width added by dragging its right edge, and the panel it is docked under ("" for none). */
    var legendScale = 1f
    /** The legend's width when its place was saved, so it reads back to the same spot. */
    var legendWidth = 0
    var legendExtra = 0
    var legendUnder = ""

    /** The biggest the map panels may be made, before the letters look too blocky. */
    var panelMaxScale = 2f
    /** Off unless turned on in the legend: the icons are usually enough. */
    /** Everything this mod draws, off in one switch: icons, outlines and spawn boxes. */
    var show = true

    /** Structures marked as completed are left off the maps. */
    var hideCompleted = false

    var outlines = false

    /** Also show structures recognised nearby that you have not discovered yet, faintly. */
    var showUndiscovered = false

    /** A chat line (seen only by you) whenever you discover one. */
    var announce = true

    /**
     * The boxes that structures with mobs of their own spawn them in ([SpawnBoxes]): drawn on the
     * world map, and in the world around you (the second can also be switched with a key).
     */
    var spawnBoxesOnMap = true
    var spawnBoxesInWorld = true

    /** How big the icons are on the world map and on the minimap, as a multiple of their normal size. */
    var iconScale = 1f
    var minimapIconScale = 1f

    /**
     * How close you must come to a structure, in blocks, for it to count as discovered: 0 means
     * being inside its box, [DISCOVER_ALL] anywhere in the chunks the server sends you. Only
     * sideways distance counts, so flying over one or passing above a buried one is enough.
     */
    var discoverDistance = DEFAULT_DISCOVER_DISTANCE

    /**
     * The furthest a structure can be found from, in blocks: the chunks the server sends you
     * (its view distance, or yours if that is smaller). Structures are only recognised from
     * blocks the server has sent, so nothing further out could be discovered anyway.
     */
    fun discoverRange(): Int = net.minecraft.client.Minecraft.getInstance().options.effectiveRenderDistance * 16

    /** The command a private share is sent with, without its slash: `tell`, or `msg` or `w` on servers that change it. */
    var privateShareCommand = "tell"

    /** Tints the chunks Bobby has saved on the world map ([BobbyCoverage]); only offered with Bobby installed. */
    var bobbyCoverage = false

    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 3f
    const val DEFAULT_DISCOVER_DISTANCE = 32
    const val DISCOVER_ALL = -1

    fun isShown(type: StructureType) = type in shown

    fun setShown(type: StructureType, value: Boolean) {
        shown = if (value) shown + type else shown - type
        // Shown or hidden by hand: no longer Hide's to bring back.
        hiddenByHide = hiddenByHide - type
        save()
    }

    /**
     * The legend's Hide switch for [types] (one dimension's): hides those showing and remembers
     * them; with none showing, brings back the ones it hid, or all of them if it remembers none.
     */
    fun toggleHide(types: List<StructureType>) {
        val showing = types.filter { it in shown }
        if (showing.isNotEmpty()) {
            shown = shown - showing.toSet()
            hiddenByHide = hiddenByHide + showing
        } else {
            val back = types.filter { it in hiddenByHide }.ifEmpty { types }
            shown = shown + back
            hiddenByHide = hiddenByHide - back.toSet()
        }
        save()
    }

    fun load() {
        try {
            if (!Files.exists(path)) return save()
            val json = Files.newBufferedReader(path).use { JsonParser.parseReader(it) }.asJsonObject
            json.getAsJsonArray("shown")?.let { array -> shown = array.mapNotNull { StructureType.byId(it.asString) }.toSet() }
            json.getAsJsonArray("hiddenByHide")?.let { array -> hiddenByHide = array.mapNotNull { StructureType.byId(it.asString) }.toSet() }
            legendOpen = json.get("legendOpen")?.asBoolean ?: legendOpen
            legendRight = json.get("legendRight")?.asInt ?: legendRight
            legendTop = json.get("legendTop")?.asInt ?: legendTop
            legendRows = (json.get("legendRows")?.asInt ?: legendRows).coerceIn(1, 32)
            legendWidth = json.get("legendWidth")?.asInt ?: legendWidth
            legendScale = (json.get("legendScale")?.asFloat ?: legendScale).coerceIn(0.5f, 4f)
            legendExtra = (json.get("legendExtra")?.asInt ?: legendExtra).coerceIn(0, 1000)
            legendUnder = json.get("legendUnder")?.asString ?: legendUnder
            panelMaxScale = (json.get("panelMaxScale")?.asFloat ?: panelMaxScale).coerceIn(1f, 4f)
            show = json.get("show")?.asBoolean ?: show
            hideCompleted = json.get("hideCompleted")?.asBoolean ?: hideCompleted
            outlines = json.get("outlines")?.asBoolean ?: outlines
            showUndiscovered = json.get("showUndiscovered")?.asBoolean ?: showUndiscovered
            announce = json.get("announce")?.asBoolean ?: announce
            spawnBoxesOnMap = json.get("spawnBoxesOnMap")?.asBoolean ?: spawnBoxesOnMap
            spawnBoxesInWorld = json.get("spawnBoxesInWorld")?.asBoolean ?: spawnBoxesInWorld
            iconScale = (json.get("iconScale")?.asFloat ?: iconScale).coerceIn(MIN_SCALE, MAX_SCALE)
            minimapIconScale = (json.get("minimapIconScale")?.asFloat ?: minimapIconScale).coerceIn(MIN_SCALE, MAX_SCALE)
            privateShareCommand = json.get("privateShareCommand")?.asString?.trim()?.removePrefix("/")?.takeIf { it.isNotEmpty() } ?: privateShareCommand
            bobbyCoverage = json.get("bobbyCoverage")?.asBoolean ?: bobbyCoverage
            discoverDistance = (json.get("discoverDistance")?.asInt ?: discoverDistance).coerceAtLeast(DISCOVER_ALL)
        } catch (e: Exception) {
            Log.error("Could not read $path; using the defaults", e)
        }
    }

    fun save() {
        try {
            val json = JsonObject()
            json.add("shown", JsonArray().also { array -> StructureType.entries.filter { it in shown }.forEach { array.add(it.id) } })
            json.add("hiddenByHide", JsonArray().also { array -> StructureType.entries.filter { it in hiddenByHide }.forEach { array.add(it.id) } })
            json.addProperty("legendOpen", legendOpen)
            json.addProperty("legendRight", legendRight)
            json.addProperty("legendTop", legendTop)
            json.addProperty("legendRows", legendRows)
            json.addProperty("legendWidth", legendWidth)
            json.addProperty("legendScale", legendScale)
            json.addProperty("legendExtra", legendExtra)
            json.addProperty("legendUnder", legendUnder)
            json.addProperty("panelMaxScale", panelMaxScale)
            json.addProperty("show", show)
            json.addProperty("hideCompleted", hideCompleted)
            json.addProperty("outlines", outlines)
            json.addProperty("showUndiscovered", showUndiscovered)
            json.addProperty("announce", announce)
            json.addProperty("spawnBoxesOnMap", spawnBoxesOnMap)
            json.addProperty("spawnBoxesInWorld", spawnBoxesInWorld)
            json.addProperty("iconScale", iconScale)
            json.addProperty("minimapIconScale", minimapIconScale)
            json.addProperty("discoverDistance", discoverDistance)
            json.addProperty("privateShareCommand", privateShareCommand)
            json.addProperty("bobbyCoverage", bobbyCoverage)
            SafeFiles.writeString(path, GSON.toJson(json))
        } catch (e: Exception) {
            Log.error("Could not save $path", e)
        }
    }
}
