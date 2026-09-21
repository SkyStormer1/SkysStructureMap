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
        private set

    var legendOpen = true
    /** Off unless turned on in the legend: the icons are usually enough. */
    var outlines = false

    /** Also show structures recognised nearby that you have not been inside yet, faintly. */
    var showUndiscovered = false

    /** A chat line (seen only by you) whenever you discover one. */
    var announce = true

    /** How big the icons are on the world map and on the minimap, as a multiple of their normal size. */
    var iconScale = 1f
    var minimapIconScale = 1f

    /**
     * How close you must come to a structure, in blocks, for it to count as discovered: 0 means
     * being inside its box. Only sideways distance counts, so flying over one or passing above a
     * buried one is enough.
     */
    var discoverDistance = DEFAULT_DISCOVER_DISTANCE

    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 3f
    const val DEFAULT_DISCOVER_DISTANCE = 32
    const val MAX_DISCOVER_DISTANCE = 128

    fun isShown(type: StructureType) = type in shown

    fun setShown(type: StructureType, value: Boolean) {
        shown = if (value) shown + type else shown - type
        save()
    }

    fun load() {
        try {
            if (!Files.exists(path)) return save()
            val json = Files.newBufferedReader(path).use { JsonParser.parseReader(it) }.asJsonObject
            json.getAsJsonArray("shown")?.let { array -> shown = array.mapNotNull { StructureType.byId(it.asString) }.toSet() }
            legendOpen = json.get("legendOpen")?.asBoolean ?: legendOpen
            outlines = json.get("outlines")?.asBoolean ?: outlines
            showUndiscovered = json.get("showUndiscovered")?.asBoolean ?: showUndiscovered
            announce = json.get("announce")?.asBoolean ?: announce
            iconScale = (json.get("iconScale")?.asFloat ?: iconScale).coerceIn(MIN_SCALE, MAX_SCALE)
            minimapIconScale = (json.get("minimapIconScale")?.asFloat ?: minimapIconScale).coerceIn(MIN_SCALE, MAX_SCALE)
            discoverDistance = (json.get("discoverDistance")?.asInt ?: discoverDistance).coerceIn(0, MAX_DISCOVER_DISTANCE)
        } catch (e: Exception) {
            Log.error("Could not read $path; using the defaults", e)
        }
    }

    fun save() {
        try {
            val json = JsonObject()
            json.add("shown", JsonArray().also { array -> StructureType.entries.filter { it in shown }.forEach { array.add(it.id) } })
            json.addProperty("legendOpen", legendOpen)
            json.addProperty("outlines", outlines)
            json.addProperty("showUndiscovered", showUndiscovered)
            json.addProperty("announce", announce)
            json.addProperty("iconScale", iconScale)
            json.addProperty("minimapIconScale", minimapIconScale)
            json.addProperty("discoverDistance", discoverDistance)
            Files.writeString(path, GSON.toJson(json))
        } catch (e: Exception) {
            Log.error("Could not save $path", e)
        }
    }
}
