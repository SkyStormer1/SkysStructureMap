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

/** A structure you have discovered (or been sent), saved. */
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
    /**
     * Boxes inside it that matter on their own: for a fortress, its crossroads, which mobs spawn
     * in. Kept once found, so they stay after the structure is torn down.
     */
    val pieces: List<Piece> = emptyList(),
    /** Marked as done (looted, cleared, whatever you count as done): a tick is drawn beside its icon. */
    val completed: Boolean = false,
) {
    val name: String get() = type.displayName

    /** Where a waypoint goes: on top of a surface building, in the middle of anything else. */
    val waypointY: Int
        get() = waypointY(type, box)
}

/** A named box inside a structure: [FortressPieces.CROSSROADS], so far. */
data class Piece(val kind: String, val box: Box)

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

    /**
     * Structures you deleted, kept so they stay deleted: one recognised again where a deleted one
     * was is ignored, however often you come back.
     */
    @Volatile
    var deleted: List<Structure> = emptyList()
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
        deleted = read(file!!, "deleted")
        mergeDuplicates()
        Log.info("Loaded {} structure(s) for {} from {}", all.size, name, file)
    }

    fun close() {
        saveNow()
        file = null
        worldName = null
        all = emptyList()
        deleted = emptyList()
    }

    fun byId(id: String): Structure? = all.firstOrNull { it.id == id }

    fun inDimension(dimension: String): List<Structure> = all.filter { it.dimension == dimension }

    /** Adds [structure], or replaces the one with its id. Saved within a few seconds. */
    fun put(structure: Structure) {
        val index = all.indexOfFirst { it.id == structure.id }
        all = if (index < 0) all + structure else all.toMutableList().also { it[index] = structure }
        dirty = true
    }

    /** Deletes a structure for good: it goes on [deleted], so it is not added back when seen again. */
    fun remove(id: String) {
        byId(id)?.let { gone -> deleted = deleted + gone }
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
            if (deleted.isNotEmpty()) json.add("deleted", JsonArray().also { a -> deleted.forEach { a.add(toJson(it)) } })
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

    /**
     * Joins structures saved twice: before sightings were matched by the distance two can be
     * apart, one seen half on one visit and half on the next could be saved as two. The earliest
     * discovery is kept, with both boxes and all pieces.
     */
    private fun mergeDuplicates() {
        val kept = ArrayList<Structure>()
        var joined = 0
        for (s in all.sortedBy { it.discovered }) {
            val index = kept.indexOfFirst { it.type == s.type && it.dimension == s.dimension && Specs.sameStructure(s.type, it.box, s.box) }
            if (index < 0) {
                kept.add(s)
                continue
            }
            val k = kept[index]
            // An exact box (a monument, a wreck) stays as it was; the rest grow to cover both.
            val box = if (Specs.of(s.type).reach == null) k.box else k.box.union(s.box)
            kept[index] = k.copy(box = box, outlined = k.outlined || s.outlined, completed = k.completed || s.completed,
                pieces = k.pieces + s.pieces.filter { p -> k.pieces.none { it.box == p.box } })
            joined++
        }
        if (joined > 0) {
            Log.info("Joined {} structure(s) that had been saved twice", joined)
            all = kept
            dirty = true
        }
    }

    /** Whether [type] at [box] in [dimension] is one you deleted. */
    fun isDeleted(type: StructureType, dimension: String, box: Box): Boolean =
        deleted.any { it.type == type && it.dimension == dimension && Specs.sameStructure(type, it.box, box) }

    /** Takes [type] at [box] off the deleted list, when you add it back yourself (from a share). */
    fun undelete(type: StructureType, dimension: String, box: Box) {
        deleted = deleted.filterNot { it.type == type && it.dimension == dimension && Specs.sameStructure(type, it.box, box) }
    }

    private fun read(path: Path, list: String = "structures"): List<Structure> {
        if (!Files.exists(path)) return emptyList()
        return try {
            val json = Files.newBufferedReader(path).use { JsonParser.parseReader(it) }.asJsonObject
            json.getAsJsonArray(list)?.mapNotNull { element ->
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
        if (structure.completed) addProperty("completed", true)
        if (structure.pieces.isNotEmpty()) add("pieces", JsonArray().also { a ->
            structure.pieces.forEach { p ->
                a.add(JsonObject().apply {
                    addProperty("kind", p.kind)
                    val pb = p.box
                    add("box", JsonArray().also { c -> listOf(pb.minX, pb.minY, pb.minZ, pb.maxX, pb.maxY, pb.maxZ).forEach(c::add) })
                })
            }
        })
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
            completed = json.get("completed")?.asBoolean ?: false,
            pieces = json.getAsJsonArray("pieces")?.mapNotNull { element ->
                val p = element.asJsonObject
                val pb = p.getAsJsonArray("box")?.map { it.asInt }?.takeIf { it.size == 6 } ?: return@mapNotNull null
                Piece(p.get("kind")?.asString ?: return@mapNotNull null, Box(pb[0], pb[1], pb[2], pb[3], pb[4], pb[5]))
            } ?: emptyList(),
        )
    }
}
