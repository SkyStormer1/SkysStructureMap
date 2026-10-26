package com.skystormer.skysstructuremap

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
     * Boxes inside it that matter on their own: for a fortress, its pieces, which mobs spawn in.
     * Kept once found, so they stay after the structure is torn down.
     */
    val pieces: List<Piece> = emptyList(),
    /** Marked as done (looted, cleared, whatever you count as done): a tick is drawn beside its icon. */
    val completed: Boolean = false,
    /**
     * Ancient cities: the box of its centre piece, once seen; mineshafts: of its start room. Each
     * has one, and two can stand close enough that their boxes touch (mineshafts cross into each
     * other), so this is what tells them apart ([twoCentres]).
     */
    val centre: Box? = null,
    /** The chunk it starts in, x to z, when what was seen tells ([Placement]). */
    val start: Pair<Int, Int>? = null,
) {
    val name: String get() = type.displayName

    /** Where a waypoint goes: on top of a surface building, in the middle of anything else. */
    val waypointY: Int
        get() = waypointY(type, box)
}

/**
 * A named box inside a structure: a fortress piece, named by the game's id for it (`nebcr` for a
 * crossroads, see [FortressPieces]). Saved before pieces were told apart: `crossroads`.
 */
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

    /**
     * Entries in the file this version could not read (a kind from a newer version, a hand edit
     * gone wrong): written back exactly as they were on every save, so nothing is ever dropped
     * from the file unless you delete it.
     */
    private var unread = Contents()

    /**
     * Set when the file was there but could not be read at all: it is copied to `backups/` and
     * never written over this session, so a damaged file loses nothing more.
     */
    private var locked = false

    val isOpen: Boolean get() = file != null

    /**
     * Joined a world whose file is still being read ([open]). Nothing is matched against what is
     * saved meanwhile ([Tracker] waits), or a structure already saved would be saved again.
     */
    @Volatile
    var loading = false
        private set

    /** Raised on each [open] and [close], so a file read for a world since left is let go of. */
    private var opened = 0

    fun open(minecraft: Minecraft) {
        val world = worldOf(minecraft)
        if (world == null) {
            Log.warn("Joined a world with no server address or single-player folder; nothing will be saved")
            return close()
        }
        val (name, key) = world
        val path = FabricLoader.getInstance().configDir.resolve("skysstructuremap").resolve("$key.json")
        // Read and checked for duplicates off the game's thread: 70 to 120 ms for 25,000 structures.
        val run = ++opened
        loading = true
        val reading = Saves.read { read(path)?.let { it to joinDuplicates(it.structures) } }
        Thread({
            val read = try { reading.get() } catch (e: Exception) { null }
            minecraft.execute {
                if (run != opened) return@execute
                loading = false
                openWith(path, name, read?.first, read?.second)
            }
        }, "Sky's Structure Map open").apply { isDaemon = true }.start()
    }

    /** Takes in the file at [path], as [loaded] (null: it could not be read), its structures with duplicates [joined]. */
    private fun openWith(path: Path, name: String, loaded: Contents?, joined: Pair<List<Structure>, Int>?) {
        worldName = name
        file = path
        if (loaded == null) {
            all = emptyList()
            deleted = emptyList()
            unread = Contents()
            val copy = try { backup("unreadable") } catch (e: Exception) { null }
            locked = true
            Menus.tell("Sky's Structure Map could not read your saved structures for $name" +
                (copy?.let { " (copied to backups/${it.fileName})" } ?: "") +
                ". Nothing will be saved for this world until the file is fixed; see the log.")
            return
        }
        all = loaded.structures
        deleted = loaded.deleted
        unread = Contents(unreadStructures = loaded.unreadStructures, unreadDeleted = loaded.unreadDeleted)
        mergeDuplicates(joined ?: joinDuplicates(all))
        // Mineshafts saved in pieces before they could be joined, joined now, the file kept as it was first.
        if (joinedMineshafts(all, null, null).second.isNotEmpty()) {
            try { backup("before-joining-mineshafts", saved = false) } catch (e: Exception) { Log.warn("Could not back up before joining mineshafts: {}", e.toString()) }
            joinMineshafts()
        }
        Log.info("Loaded {} structure(s) for {} from {}", all.size, name, file)
    }

    fun close() {
        opened++
        loading = false
        saveNow()
        Saves.flush()
        file = null
        worldName = null
        all = emptyList()
        deleted = emptyList()
        unread = Contents()
        locked = false
    }

    fun byId(id: String): Structure? {
        val now = all
        return ids(now)[id]?.let(now::get)
    }

    /**
     * Where each id is in [all], for the list [idsFrom]: [put] and [byId] are asked each time a box
     * nearby is seen better, and looking through 25,000 ids each time added up. Main thread only.
     */
    private var ids = HashMap<String, Int>()
    private var idsFrom: List<Structure>? = null

    private fun ids(now: List<Structure>): HashMap<String, Int> {
        if (idsFrom !== now) {
            // The first of an id wins, as a search from the start would find.
            ids = HashMap<String, Int>(now.size * 2).also { map -> now.forEachIndexed { i, s -> map.putIfAbsent(s.id, i) } }
            idsFrom = now
        }
        return ids
    }

    /** [all] by dimension, remade when [all] is replaced: asked every frame and every tick, too often to filter each time. */
    private class ByDimension(val from: List<Structure>, val lists: Map<String, List<Structure>>)

    @Volatile private var byDimension = ByDimension(emptyList(), emptyMap())

    fun inDimension(dimension: String): List<Structure> {
        val now = all
        val known = byDimension.takeIf { it.from === now } ?: ByDimension(now, now.groupBy { it.dimension }).also { byDimension = it }
        return known.lists[dimension].orEmpty()
    }

    /**
     * Adds [structure], or replaces the one with its id. Saved within a few seconds. The lists are
     * new ones (the render thread reads the old), but the id index and the by-dimension lists are
     * brought along rather than made again from all 25,000.
     */
    fun put(structure: Structure) {
        val now = all
        val ids = ids(now)
        val index = ids[structure.id]
        val old = index?.let(now::get)
        val next = ArrayList<Structure>(now.size + 1).apply { addAll(now) }
        if (index == null) {
            ids[structure.id] = next.size
            next.add(structure)
        } else {
            next[index] = structure
        }
        idsFrom = next
        val known = byDimension
        if (known.from === now && (old == null || old.dimension == structure.dimension)) {
            val list = known.lists[structure.dimension].orEmpty()
            val at = if (old == null) -1 else list.indexOfFirst { it === old }
            val changed = ArrayList<Structure>(list.size + 1).apply { addAll(list) }
            if (at < 0) changed.add(structure) else changed[at] = structure
            byDimension = ByDimension(next, known.lists + (structure.dimension to changed))
        }
        all = next
        dirty = true
    }

    /** The last one deleted, kept so a delete by accident can be undone (this session only). */
    private var lastDeleted: Structure? = null

    /** Deletes a structure for good: it goes on [deleted], so it is not added back when seen again. */
    fun remove(id: String) {
        byId(id)?.let { gone ->
            deleted = deleted + gone
            lastDeleted = gone
        }
        all = all.filter { it.id != id }
        saveNow()
    }

    /**
     * Puts the last deleted structure back on the map, off the deleted list, as it was. Answers
     * what came back, or null when there is nothing to undo.
     */
    fun undoDelete(): Structure? {
        val back = lastDeleted ?: return null
        lastDeleted = null
        // By its own id: this is the very structure deleted, not merely one where it stood.
        deleted = deleted.filterNot { it.id == back.id }
        put(back)
        saveNow()
        return back
    }

    /** Called every few seconds: writes the file if anything changed. */
    fun saveIfChanged() {
        if (dirty) saveNow()
    }

    /**
     * Writes the file as it is now, on [Saves]' thread: the lists are replaced on each change, never
     * changed in place, so the ones taken here stay as they are while being written.
     */
    private fun saveNow() {
        dirty = false
        val path = file ?: return
        if (locked) return
        val contents = Contents(all, deleted, unread.unreadStructures, unread.unreadDeleted)
        Saves.later(path.toString()) { SafeFiles.writeString(path, write(contents)) }
    }

    /** What a structures file holds: what was read, and the entries that could not be, as they were. */
    class Contents(
        val structures: List<Structure> = emptyList(),
        val deleted: List<Structure> = emptyList(),
        val unreadStructures: List<JsonElement> = emptyList(),
        val unreadDeleted: List<JsonElement> = emptyList(),
    )

    /** A structures file's text, with the entries that could not be read put back unchanged. */
    fun write(contents: Contents): String {
        val json = JsonObject()
        json.addProperty("version", 1)
        json.add("structures", JsonArray().also { a ->
            contents.structures.forEach { a.add(toJson(it)) }
            contents.unreadStructures.forEach(a::add)
        })
        if (contents.deleted.isNotEmpty() || contents.unreadDeleted.isNotEmpty()) json.add("deleted", JsonArray().also { a ->
            contents.deleted.forEach { a.add(toJson(it)) }
            contents.unreadDeleted.forEach(a::add)
        })
        return GSON.toJson(json)
    }

    /**
     * A structures file's contents; null when it cannot be read at all. An entry that cannot be
     * read is kept as it was ([Contents.unreadStructures]), never dropped.
     */
    fun parse(text: String): Contents? {
        val json = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            Log.error("Could not read the structures file", e)
            return null
        }
        fun list(name: String): Pair<List<Structure>, List<JsonElement>>? {
            val element = json.get(name)?.takeUnless { it.isJsonNull } ?: return emptyList<Structure>() to emptyList()
            if (!element.isJsonArray) return null
            val read = ArrayList<Structure>()
            val kept = ArrayList<JsonElement>()
            for (entry in element.asJsonArray) {
                try {
                    read.add(fromJson(entry.asJsonObject))
                } catch (e: Exception) {
                    Log.warn("Keeping an entry in \"{}\" that could not be read, as it was: {}", name, e.toString())
                    kept.add(entry)
                }
            }
            return read to kept
        }
        val structures = list("structures") ?: return null
        val deleted = list("deleted") ?: return null
        return Contents(structures.first, deleted.first, structures.second, deleted.second)
    }

    /**
     * Copies this world's structures file, as saved right now, into `backups/` beside it, named
     * after the world, the time and [label]; answers the copy. Done before a scan of Bobby's cache
     * adds to it, and (as it was read, [saved] false) before loading joins mineshafts saved in
     * pieces. Put back by hand: with the game closed, copy it over `<world>.json`.
     */
    fun backup(label: String, saved: Boolean = true): Path {
        val path = file ?: error("Not in a world")
        if (saved) {
            saveNow()
            Saves.flush()
        }
        if (!Files.exists(path)) error("Could not save $path")
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val name = path.fileName.toString().removeSuffix(".json")
        val target = path.resolveSibling("backups").resolve("${name}_${stamp}_${safe(label)}.json")
        Files.createDirectories(target.parent)
        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
        Log.info("Backed up {} structure(s) to {}", all.size, target)
        return target
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
    private fun mergeDuplicates(result: Pair<List<Structure>, Int>) {
        val (kept, joined) = result
        if (joined > 0) {
            Log.info("Joined {} structure(s) that had been saved twice", joined)
            all = kept
            dirty = true
        }
    }

    /** [mergeDuplicates] without the file: the structures left, and how many were joined into others. */
    fun joinDuplicates(structures: List<Structure>): Pair<List<Structure>, Int> {
        val kept = ArrayList<Structure>()
        // Only one of the same kind in the same dimension can be the same: each is looked for among
        // those (where each sits in [kept]) rather than among all kept so far.
        val alike = HashMap<Pair<StructureType, String>, ArrayList<Int>>()
        var joined = 0
        for (s in structures.sortedBy { it.discovered }) {
            val same = alike.getOrPut(s.type to s.dimension) { ArrayList() }
            val index = same.firstOrNull { i -> val it = kept[i]; Specs.sameStructure(s.type, it.box, s.box) &&
                !twoFortresses(it, s) && !twoCentres(it.centre, s.centre) && !twoMineshafts(it, s) } ?: -1
            if (index < 0) {
                same.add(kept.size)
                kept.add(s)
                continue
            }
            val k = kept[index]
            // An exact box (a monument, a wreck) stays as it was; the rest grow to cover both.
            val box = if (Specs.of(s.type).reach == null) k.box else k.box.union(s.box)
            kept[index] = k.copy(box = box, outlined = k.outlined || s.outlined, completed = k.completed || s.completed, centre = k.centre ?: s.centre,
                pieces = k.pieces + s.pieces.filter { p -> k.pieces.none { it.box == p.box } })
            joined++
        }
        return kept to joined
    }

    /**
     * Joins the saved mineshafts that are pieces of one ([joinedMineshafts]), in [dimension] (every
     * one when null) and only those with no room of their own that overlap [near] (every one when
     * null). Answers which ids went into which, for groups tied to those saved to follow.
     */
    fun joinMineshafts(dimension: String? = null, near: Box? = null): Map<String, String> {
        val (kept, into) = joinedMineshafts(all, dimension, near)
        if (into.isNotEmpty()) {
            all = kept
            dirty = true
            Log.info("Joined {} mineshaft(s) seen in pieces into the mineshafts they are", into.size)
        }
        return into
    }

    /**
     * [structures] with each mineshaft whose start room was never seen joined into the one it is a
     * piece of: the one whose room reaches over [JOIN_SHARE] of it or more (the game lays every piece
     * within that reach), else one also room-less within [JOIN_GAP] of it; while the two together are
     * no bigger than a mineshaft's box can be: as wide and high as a room's reach ([MineshaftPieces.reachOf]),
     * and no wider than [JOIN_ACROSS] without a room (in testing the widest was 198 and the highest
     * 45). Neither is cut to fit: what is seen of either stays marked. Never two that each
     * have a room. One mineshaft split in two is worse than two joined: in testing, this took those
     * saved in two or more pieces from 139 to 41, and marked more of their corridors. Answers the structures left and which
     * ids went into which.
     */
    fun joinedMineshafts(structures: List<Structure>, dimension: String?, near: Box?): Pair<List<Structure>, Map<String, String>> {
        val list = structures.toMutableList<Structure?>()
        fun hasRoom(s: Structure) = s.centre?.let { !MineshaftPieces.isStretch(it) } == true
        fun volume(b: Box) = b.sizeX.toLong() * b.sizeY * b.sizeZ
        fun shared(a: Box, b: Box): Long {
            val x = minOf(a.maxX, b.maxX) - maxOf(a.minX, b.minX) + 1
            val y = minOf(a.maxY, b.maxY) - maxOf(a.minY, b.minY) + 1
            val z = minOf(a.maxZ, b.maxZ) - maxOf(a.minZ, b.minZ) + 1
            return if (x <= 0 || y <= 0 || z <= 0) 0 else x.toLong() * y * z
        }
        fun fits(b: Box, across: Int) = maxOf(b.sizeX, b.sizeZ) <= across && b.sizeY <= MineshaftPieces.MOST_HIGH
        val shafts = list.indices.filter { i -> list[i]!!.let { it.type == StructureType.MINESHAFT && (dimension == null || it.dimension == dimension) } }
        val into = LinkedHashMap<String, String>()
        val reachOut = JOIN_GAP + MineshaftPieces.REACH
        var changed = true
        while (changed) {
            changed = false
            // Where each stands, by cells of the grid, its box grown as far as it can join from.
            val cells = HashMap<Long, MutableList<Int>>()
            for (i in shafts) {
                val b = list[i]?.box?.grow(reachOut) ?: continue
                for (cx in (b.minX shr CELL)..(b.maxX shr CELL)) for (cz in (b.minZ shr CELL)..(b.maxZ shr CELL)) {
                    cells.getOrPut(cellOf(cx, cz)) { ArrayList() }.add(i)
                }
            }
            val guests = shafts.filter { i -> list[i]?.let { !hasRoom(it) && (near == null || it.box.overlaps(near)) } == true }
                .sortedBy { volume(list[it]!!.box) }
            for (g in guests) {
                val guest = list[g] ?: continue
                val b = guest.box
                val around = HashSet<Int>()
                for (cx in (b.minX shr CELL)..(b.maxX shr CELL)) for (cz in (b.minZ shr CELL)..(b.maxZ shr CELL)) cells[cellOf(cx, cz)]?.let(around::addAll)
                var host = -1
                var rank = 0
                var score = Double.NEGATIVE_INFINITY
                var joined: Box? = null
                for (h in around) {
                    val other = list[h] ?: continue
                    if (h == g || other.dimension != guest.dimension) continue
                    if (hasRoom(other)) {
                        val reach = MineshaftPieces.reachOf(other.centre!!)
                        val share = shared(b, reach).toDouble() / volume(b)
                        val both = other.box.union(b)
                        if (share < JOIN_SHARE || !fits(both, reach.sizeX) || rank > 2 || (rank == 2 && share <= score)) continue
                        host = h
                        rank = 2
                        score = share
                        joined = both
                    } else {
                        val gap = other.box.gap(b)
                        val both = other.box.union(b)
                        if (gap > JOIN_GAP || !fits(both, JOIN_ACROSS) || rank > 1 || (rank == 1 && -gap <= score)) continue
                        host = h
                        rank = 1
                        score = -gap.toDouble()
                        joined = both
                    }
                }
                if (host < 0) continue
                val kept = list[host]!!
                list[host] = kept.copy(box = joined!!, completed = kept.completed || guest.completed, outlined = kept.outlined || guest.outlined,
                    discovered = minOf(kept.discovered, guest.discovered))
                list[g] = null
                into[guest.id] = kept.id
                changed = true
            }
        }
        // One joined into another that was then joined on goes to the last.
        for (id in into.keys.toList()) {
            var to = into.getValue(id)
            while (to in into) to = into.getValue(to)
            into[id] = to
        }
        return list.filterNotNull() to into
    }

    private fun cellOf(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    private const val JOIN_GAP = 64
    private const val JOIN_SHARE = 0.3
    private const val JOIN_ACROSS = MineshaftPieces.MOST_WIDE
    private const val CELL = 7

    /**
     * Whether two saved fortresses are certainly two: together they would hold more crossroads
     * than one fortress has ([FortressPieces.MOST_CROSSROADS]). Fortresses close together or on
     * top of each other are within each other's span, so nearness alone would join them.
     */
    fun twoFortresses(a: Structure, b: Structure): Boolean {
        if (a.type != StructureType.FORTRESS) return false
        return FortressPieces.tooManyCrossroads(a.pieces, b.pieces)
    }

    /**
     * Whether two saved mineshafts may be two: unless both were saved from the same start room.
     * They cross and touch each other, and every load joined the ones within 24 blocks: 191 on one
     * load, one grown to 484 by 423 blocks.
     */
    fun twoMineshafts(a: Structure, b: Structure): Boolean =
        a.type == StructureType.MINESHAFT && (a.centre == null || a.centre != b.centre)

    /** Whether two ancient cities or mineshafts are certainly two: each has a centre seen, and they are not the same one. */
    fun twoCentres(a: Box?, b: Box?): Boolean = a != null && b != null && !a.overlaps(b)

    /**
     * The saved structure already starting in the square [type]'s start at [start] would share
     * ([Placement.square]), from another chunk: the game starts only one there, so both cannot be.
     */
    fun startTaken(type: StructureType, dimension: String, start: Pair<Int, Int>): Structure? {
        val square = Placement.square(type, start.first, start.second) ?: return null
        // One saved before starts were kept has its start worked out from its box, where it can be.
        return all.firstOrNull { s ->
            if (s.type != type || s.dimension != dimension) return@firstOrNull false
            val its = s.start ?: Placement.startFromBox(s.type, s.box) ?: return@firstOrNull false
            its != start && Placement.square(s.type, its.first, its.second) == square
        }
    }

    /** Whether [type] at [box] in [dimension] is one you deleted. */
    fun isDeleted(type: StructureType, dimension: String, box: Box): Boolean =
        deleted.any { it.type == type && it.dimension == dimension && Specs.sameStructure(type, it.box, box) }

    /**
     * Forgets the structures deleted within [reach] of a spot, so they can be found again. Answers
     * how many. This is what "look again" does: a delete otherwise stands for good, and a group
     * found where one was deleted is thrown away without a word.
     */
    fun forgetDeletedNear(dimension: String, x: Int, z: Int, reach: Double): Int {
        val near = deleted.filter { it.dimension == dimension && it.box.horizontalDistance(x.toDouble(), z.toDouble()) <= reach }
        deleted = deleted - near.toSet()
        if (near.isNotEmpty()) saveNow()
        return near.size
    }

    /** Takes [type] at [box] off the deleted list, when you add it back yourself (from a share). */
    fun undelete(type: StructureType, dimension: String, box: Box) {
        deleted = deleted.filterNot { it.type == type && it.dimension == dimension && Specs.sameStructure(type, it.box, box) }
    }

    /** The file at [path] ([parse]); empty when there is none yet, null when it cannot be read. */
    private fun read(path: Path): Contents? {
        if (!Files.exists(path)) return Contents()
        return try {
            parse(Files.readString(path))
        } catch (e: Exception) {
            Log.error("Could not read $path", e)
            null
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
        structure.centre?.let { c -> add("centre", JsonArray().also { a -> listOf(c.minX, c.minY, c.minZ, c.maxX, c.maxY, c.maxZ).forEach(a::add) }) }
        structure.start?.let { (x, z) -> add("start", JsonArray().also { a -> a.add(x); a.add(z) }) }
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
            // Only a help in telling cities apart: one that cannot be read is left out, not the city.
            centre = runCatching { json.getAsJsonArray("centre")?.map { it.asInt }?.takeIf { it.size == 6 } }.getOrNull()
                ?.let { Box(it[0], it[1], it[2], it[3], it[4], it[5]) },
            start = runCatching { json.getAsJsonArray("start")?.map { it.asInt }?.takeIf { it.size == 2 } }.getOrNull()?.let { it[0] to it[1] },
            pieces = json.getAsJsonArray("pieces")?.mapNotNull { element ->
                val p = element.asJsonObject
                val pb = p.getAsJsonArray("box")?.map { it.asInt }?.takeIf { it.size == 6 } ?: return@mapNotNull null
                Piece(p.get("kind")?.asString ?: return@mapNotNull null, Box(pb[0], pb[1], pb[2], pb[3], pb[4], pb[5]))
            } ?: emptyList(),
        )
    }
}
