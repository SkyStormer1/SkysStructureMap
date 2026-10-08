package com.skystormer.skysstructuremap

import net.minecraft.client.Minecraft
import java.nio.file.Path
import java.util.EnumMap

/**
 * Looks through everything Bobby saved of this server ([BobbyCache]) for structures, the same way
 * as the chunks the server sends ([ChunkScanner], [Tracker]), and saves each one found as
 * discovered straight away: you were there once, when Bobby saved it. Ones already saved are
 * kept as they are (their box grows if Bobby saw more of them), and ones you deleted stay deleted.
 *
 * The saved structures are backed up first ([StructureStore.backup]). Reading and matching run on
 * a thread of their own, a dimension at a time, so the game carries on; what is found is handed
 * to the main thread, which is the only one that touches the saved structures.
 */
object BobbyScan {

    @Volatile
    private var running: Job? = null

    val isRunning: Boolean get() = running != null

    /** Starts a scan of this server's Bobby cache, or says why it cannot. Main thread only. */
    fun start() {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        if (level == null || minecraft.player == null) return Menus.tell("Join a world first")
        if (!StructureStore.isOpen) return Menus.tell("No structures file is open for this world, so nothing could be saved")
        if (running != null) return Menus.tell("Already scanning Bobby's cache; /${StructureShare.COMMAND} bobby stop stops it")
        val found = try {
            BobbyCache.locate(minecraft, level).getOrThrow()
        } catch (e: BobbyCache.Missing) {
            return Menus.tell(e.message ?: "No Bobby cache found")
        } catch (e: Exception) {
            Log.error("Could not look for Bobby's cache", e)
            return Menus.tell("Could not look for Bobby's cache: $e")
        }
        val backup = try {
            StructureStore.backup("before-bobby-scan")
        } catch (e: Exception) {
            Log.error("Could not back up the structures file", e)
            return Menus.tell("Could not back up your structures, so the scan did not start: $e")
        }
        val reader = try {
            BobbyCache.Reader(level)
        } catch (e: Exception) {
            Log.error("Could not get ready to read Bobby's cache", e)
            return Menus.tell("Could not get ready to read Bobby's cache: $e")
        }
        Menus.tell("Backed up your ${StructureStore.all.size} structures to config/skysstructuremap/backups/${backup.fileName}")
        found.notes.forEach(Menus::tell)
        Menus.tell("Scanning ${found.regionCount} region files Bobby saved (${found.folder}). " +
            "You can keep playing; /${StructureShare.COMMAND} bobby stop stops it.")
        val job = Job(found, reader, StructureStore.worldName)
        running = job
        Thread({ job.run() }, "Sky's Structure Map Bobby scan").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** Stops a scan; what it had already saved stays. */
    fun stop(quietly: Boolean = false) {
        val job = running
        if (job == null) {
            if (!quietly) Menus.tell("Not scanning Bobby's cache")
            return
        }
        job.stopped = true
        running = null
        if (!quietly) Menus.tell("Stopping the scan of Bobby's cache")
    }

    private class Job(val found: BobbyCache.Found, val reader: BobbyCache.Reader, val world: String?) {

        @Volatile
        var stopped = false

        // Counted on the main thread only.
        val new = EnumMap<StructureType, Int>(StructureType::class.java)
        var known = 0
        var deleted = 0

        private var chunks = 0
        private var unreadable = 0
        private var regionsDone = 0
        private var lastProgress = System.currentTimeMillis()

        fun run() {
            val started = System.currentTimeMillis()
            try {
                for ((dimension, files) in found.regions) {
                    if (stopped) break
                    scan(dimension, files)
                }
            } catch (e: Throwable) {
                Log.error("The scan of Bobby's cache failed", e)
                onMain { Menus.tell("The scan of Bobby's cache failed: $e (see the log)") }
                stopped = true
            }
            val seconds = (System.currentTimeMillis() - started) / 1000
            Log.info("Bobby scan: {} chunks in {} s, {} unreadable, {} from another version", chunks, seconds, unreadable, reader.otherVersion)
            onMain { finish(seconds) }
        }

        /** One dimension: every chunk read for signature blocks, then each group matched and handed over. */
        private fun scan(dimension: String, files: List<Path>) {
            // Numbered well away from the groups around you, so the log tells them apart.
            val groups = Groups(nextId = 1_000_000)
            for (file in files) {
                if (stopped) return
                val region = try {
                    BobbyCache.Region(file)
                } catch (e: Exception) {
                    Log.warn("Could not read {}: {}", file, e.toString())
                    unreadable++
                    continue
                }
                for ((x, z) in region.chunks()) {
                    val chunk = try {
                        region.tag(x, z)?.let(reader::chunk)
                    } catch (e: Exception) {
                        unreadable++
                        null
                    } ?: continue
                    chunks++
                    for ((type, blocks) in ChunkScanner.find(chunk.sections, chunk.minSectionY, x * 16, z * 16, dimension)) {
                        groups.add(type, dimension, blocks, Tracker.keepsBlocks(type))
                    }
                }
                regionsDone++
                progress("Reading Bobby's cache: $regionsDone of ${found.regionCount} regions, $chunks chunks")
            }
            val blocks = BobbyCache.CachedBlocks(files, reader)
            val recognised = ArrayList<Detection>()
            // Grows as a city's group gives up the next city's blocks (Tracker.splitOffNextCity).
            val all = ArrayList(groups.detections)
            var index = 0
            while (index < all.size) {
                if (stopped) return
                val detection = all[index++]
                try {
                    Tracker.recognise(detection, blocks, now = true)
                    Tracker.splitOffNextCity(groups, detection)?.let(all::add)
                } catch (e: Exception) {
                    Log.error("Could not match ${detection.type.id} #${detection.id} from Bobby's cache", e)
                }
                if (detection.box != null) recognised.add(detection)
                progress("Matching what Bobby saw in the ${dimensionName(dimension)}: $index of ${all.size}")
            }
            Log.info("Bobby scan of {}: {} groups, {} recognised", dimension, all.size, recognised.size)
            onMain { takeIn(recognised) }
        }

        /** Saves what was recognised. Main thread. */
        private fun takeIn(recognised: List<Detection>) {
            if (stopped) return
            if (!StructureStore.isOpen || StructureStore.worldName != world) {
                Log.warn("Left {} during the Bobby scan; its finds were not saved", world)
                stopped = true
                return
            }
            // A fortress group holding more than one fortress is saved as one for each.
            for (detection in recognised.flatMap { it.parts.ifEmpty { listOf(it) } }) {
                when (Tracker.takeIn(detection)) {
                    Tracker.Taken.NEW -> new.merge(detection.type, 1, Int::plus)
                    Tracker.Taken.KNOWN -> known++
                    Tracker.Taken.DELETED -> deleted++
                    Tracker.Taken.NOT_ONE -> {}
                }
            }
            Tracker.relinkUndiscovered()
            StructureStore.saveIfChanged()
        }

        private fun finish(seconds: Long) {
            if (running === this) running = null
            val total = new.values.sum()
            val kinds = new.entries.joinToString(", ") { (type, n) -> "$n ${if (n == 1) type.displayName else type.plural}" }
            Menus.tell(
                (if (stopped) "Stopped the scan of Bobby's cache after $chunks chunks. " else "Scanned $chunks chunks from Bobby's cache in ${seconds}s. ") +
                    (if (total == 0) "No new structures found" else "Discovered $total new: $kinds") +
                    (if (known > 0) "; $known you already had" else "") +
                    (if (deleted > 0) "; $deleted you had deleted, left deleted" else "") + "."
            )
            if (reader.otherVersion > 0) {
                Menus.tell("${reader.otherVersion} chunks were saved by another Minecraft version and were skipped. " +
                    "Bobby's /bobby upgrade brings them up to date; then scan again.")
            }
            if (unreadable > 0) Menus.tell("$unreadable chunks could not be read and were skipped (see the log).")
        }

        /** A line on the action bar every few seconds, so a long scan is plainly still going. */
        private fun progress(message: String) {
            val now = System.currentTimeMillis()
            if (now - lastProgress < PROGRESS_EVERY) return
            lastProgress = now
            onMain { if (running === this) Menus.say(message) }
        }

        private fun onMain(action: () -> Unit) = Minecraft.getInstance().execute { action() }
    }

    private const val PROGRESS_EVERY = 3000L
}
