package com.skystormer.skysstructuremap

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** A structure you have been inside, saved. */
data class Structure(
    val id: String,
    val type: StructureType,
    val dimension: String,
    val box: Box,
    /** When it was discovered, in milliseconds since 1970. */
    val discovered: Long,
    /** Shipwrecks: which of the game's shipwreck templates it is. */
    val variant: String? = null,
    /** Its box outline drawn on the map even while outlines are off for everything. */
    val outlined: Boolean = false,
) {
    val name: String get() = type.displayName

    /** Where a waypoint goes: on top of a surface building, in the middle of anything else. */
    val waypointY: Int
        get() = waypointY(type, box)
}

/** Where a waypoint to a [type] with this [box] goes. */
fun waypointY(type: StructureType, box: Box): Int = if (Specs.of(type).waypointAtTop) box.maxY + 1 else box.centreY

/**
 * The structures discovered on the server or single-player world you are in: one file per server
 * in `config/skysstructuremap/`, named after its address, holding every dimension's. Loaded on
 * joining, saved when something changes.
 */
object StructureStore {

    private val GSON = GsonBuilder().setPrettyPrinting().create()

    private var file: Path? = null

    var worldName: String? = null
        private set

    /** Read by the render thread every frame, so replaced whole rather than changed in place. */
    @Volatile
    var all: List<Structure> = emptyList()
        private set

    private var dirty = false

    val isOpen: Boolean get() = file != null

    fun open(minecraft: Minecraft) {
        val world = worldOf(minecraft)
        if (world == null) {
            Log.warn("Joined a world with no server address or single-player folder; nothing will be saved")
            return close()
        }
        val (name, key) = world
        worldName = name
        file = FabricLoader.getInstance().configDir.resolve("skysstructuremap").resolve("$key.json")
        all = read(file!!)
        Log.info("Loaded {} structure(s) for {} from {}", all.size, name, file)
    }

    fun close() {
        saveNow()
        file = null
        worldName = null
        all = emptyList()
    }

    fun byId(id: String): Structure? = all.firstOrNull { it.id == id }

    fun inDimension(dimension: String): List<Structure> = all.filter { it.dimension == dimension }

    /** Adds [structure], or replaces the one with its id. Saved within a few seconds. */
    fun put(structure: Structure) {
        val index = all.indexOfFirst { it.id == structure.id }
        all = if (index < 0) all + structure else all.toMutableList().also { it[index] = structure }
        dirty = true
    }

    fun remove(id: String) {
        all = all.filter { it.id != id }
        saveNow()
    }

    /** Called every few seconds: writes the file if anything changed. */
    fun saveIfChanged() {
        if (dirty) saveNow()
    }

    private fun saveNow() {
        dirty = false
        val path = file ?: return
        try {
            val array = JsonArray()
            all.forEach { array.add(toJson(it)) }
            val json = JsonObject()
            json.addProperty("version", 1)
            json.add("structures", array)
            Files.createDirectories(path.parent)
            // Written beside it and moved into place, so a crash mid-write cannot lose the list.
            val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.writeString(temporary, GSON.toJson(json))
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Log.error("Could not save $path", e)
        }
    }

    /** The server address as typed in the server list, or the single-player world's folder. */
    private fun worldOf(minecraft: Minecraft): Pair<String, String>? {
        minecraft.currentServer?.ip?.let { address ->
            val name = address.trim().lowercase()
            return name to safe(name)
        }
        val server = minecraft.singleplayerServer ?: return null
        val folder = server.worldData.levelName
        return folder to "singleplayer-${safe(folder)}"
    }

    private fun safe(text: String): String = text.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "_" }

    private fun read(path: Path): List<Structure> {
        if (!Files.exists(path)) return emptyList()
        return try {
            val json = Files.newBufferedReader(path).use { JsonParser.parseReader(it) }.asJsonObject
            json.getAsJsonArray("structures")?.mapNotNull { element ->
                try {
                    fromJson(element.asJsonObject)
                } catch (e: Exception) {
                    Log.warn("Skipping a structure in {} that could not be read: {}", path, e.toString())
                    null
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.error("Could not read $path", e)
            emptyList()
        }
    }

    private fun toJson(structure: Structure) = JsonObject().apply {
        addProperty("id", structure.id)
        addProperty("type", structure.type.id)
        addProperty("dimension", structure.dimension)
        val b = structure.box
        add("box", JsonArray().also { a -> listOf(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ).forEach(a::add) })
        addProperty("discovered", structure.discovered)
        structure.variant?.let { addProperty("variant", it) }
        if (structure.outlined) addProperty("outlined", true)
    }

    private fun fromJson(json: JsonObject): Structure {
        val box = json.getAsJsonArray("box").map { it.asInt }
        require(box.size == 6) { "box needs 6 numbers" }
        return Structure(
            id = json.get("id").asString,
            type = StructureType.byId(json.get("type").asString) ?: error("unknown type ${json.get("type")}"),
            dimension = json.get("dimension").asString,
            box = Box(box[0], box[1], box[2], box[3], box[4], box[5]),
            discovered = json.get("discovered")?.asLong ?: 0L,
            variant = json.get("variant")?.asString,
            outlined = json.get("outlined")?.asBoolean ?: false,
        )
    }
}
