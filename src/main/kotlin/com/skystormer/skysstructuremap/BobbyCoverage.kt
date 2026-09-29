package com.skystormer.skysstructuremap

import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.joml.Matrix4f
import java.io.RandomAccessFile
import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A debug layer on Xaero's world map showing which chunks the Bobby mod has saved of this server:
 * those it has are tinted, so a part of your map without the tint is somewhere a Bobby scan
 * ([BobbyScan]) cannot look. Pointing at a chunk says when Bobby saved it.
 *
 * Only offered when Bobby is installed, and even then nothing of Bobby's is called: only the
 * first 8 KB of each of its region files is read, the table saying which chunks are in it and
 * when each was saved. It is read again every few seconds while the map is open, only for files
 * that changed, so chunks Bobby saves as you play appear on it.
 */
object BobbyCoverage {

    val available: Boolean by lazy { FabricLoader.getInstance().isModLoaded("bobby") }

    val shown: Boolean get() = available && Config.bobbyCoverage

    /** Turns the layer on or off, and says what came of it. */
    fun toggle() {
        if (!available) return Menus.tell("Bobby is not installed")
        Config.bobbyCoverage = !Config.bobbyCoverage
        Config.save()
        if (!Config.bobbyCoverage) return Menus.tell("Bobby's saved chunks hidden on the world map")
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return Menus.tell("Bobby's saved chunks will be tinted on the world map")
        BobbyCache.locate(minecraft, level).fold(
            { Menus.tell("Bobby's saved chunks are tinted on the world map; anywhere without the tint, Bobby has nothing saved") },
            { Menus.tell("Bobby's saved chunks will be tinted on the world map, but: ${it.message}") },
        )
        lastRead = 0
    }

    /** What was read last: each dimension's saved chunks. Replaced whole, never changed. */
    private class Snapshot(
        /** Save times in seconds (0 = not saved), 1024 per region, by region ([key]). */
        val times: Map<String, Map<Long, IntArray>>,
        /** Rectangles covering the saved chunks, as chunk x, z, width, depth, four ints each. */
        val rectangles: Map<String, IntArray>,
    )

    @Volatile
    private var snapshot = Snapshot(emptyMap(), emptyMap())

    @Volatile
    private var reading = false
    private var lastRead = 0L

    /** Each file's table as last read, with the time and size it had then, so unchanged ones are not read again. */
    private class Table(val modified: Long, val size: Long, val times: IntArray)

    private val tables = HashMap<Path, Table>()

    fun clear() {
        snapshot = Snapshot(emptyMap(), emptyMap())
        synchronized(tables) { tables.clear() }
        lastRead = 0
    }

    /** Each tick: reads Bobby's tables again every few seconds while the world map is open. Main thread. */
    fun tick(minecraft: Minecraft) {
        if (!shown || reading) return
        if (minecraft.gui.screen()?.javaClass?.name != "xaero.map.gui.GuiMap") return
        val now = System.currentTimeMillis()
        if (now - lastRead < REFRESH_MILLIS) return
        lastRead = now
        val level = minecraft.level ?: return
        val found = BobbyCache.locate(minecraft, level).getOrNull() ?: run {
            snapshot = Snapshot(emptyMap(), emptyMap())
            return
        }
        reading = true
        Thread({
            try {
                snapshot = read(found)
            } catch (e: Exception) {
                Log.warn("Could not read Bobby's saved chunks for the map: {}", e.toString())
            } finally {
                reading = false
            }
        }, "Sky's Structure Map Bobby coverage").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun read(found: BobbyCache.Found): Snapshot {
        val times = HashMap<String, Map<Long, IntArray>>()
        val rectangles = HashMap<String, IntArray>()
        synchronized(tables) {
            val seen = HashSet<Path>()
            for ((dimension, files) in found.regions) {
                val regions = HashMap<Long, IntArray>()
                for (file in files) {
                    seen.add(file)
                    val table = table(file) ?: continue
                    val (regionX, regionZ) = BobbyCache.regionOf(file)
                    // More than one file for a region when Bobby told several worlds apart: the newest save wins.
                    val merged = regions.getOrPut(key(regionX, regionZ)) { IntArray(CHUNKS) }
                    for (i in 0 until CHUNKS) if (table.times[i] > merged[i]) merged[i] = table.times[i]
                }
                times[dimension] = regions
                rectangles[dimension] = rectanglesOf(regions)
            }
            tables.keys.retainAll(seen)
        }
        return Snapshot(times, rectangles)
    }

    /** A file's table of chunks, read again only when the file changed; null when it cannot be read. */
    private fun table(file: Path): Table? = try {
        val modified = Files.getLastModifiedTime(file).toMillis()
        val size = Files.size(file)
        tables[file]?.takeIf { it.modified == modified && it.size == size } ?: run {
            val header = ByteArray(HEADER)
            val read = RandomAccessFile(file.toFile(), "r").use { if (it.length() < HEADER) 0 else { it.readFully(header); HEADER } }
            val times = IntArray(CHUNKS)
            if (read == HEADER) {
                val buffer = ByteBuffer.wrap(header)
                for (i in 0 until CHUNKS) {
                    // A chunk is there when its place in the file is; its time can be 0 if Bobby never set it.
                    if (buffer.getInt(i * 4) != 0) times[i] = buffer.getInt(SECTOR + i * 4).coerceAtLeast(1)
                }
            }
            Table(modified, size, times).also { tables[file] = it }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * The saved chunks of each region as few rectangles as is easy: runs along each row, and a run
     * carried down while the next row has the same one. A region Bobby has whole is one rectangle.
     */
    internal fun rectanglesOf(regions: Map<Long, IntArray>): IntArray {
        val out = ArrayList<Int>()
        for ((key, times) in regions) {
            val baseX = (key shr 32).toInt() * 32
            val baseZ = key.toInt() * 32
            // Runs still open, by their start and end x, with the row they started in.
            var open = HashMap<Long, Int>()
            for (z in 0..32) {
                val next = HashMap<Long, Int>()
                if (z < 32) {
                    var x = 0
                    while (x < 32) {
                        if (times[x + z * 32] == 0) { x++; continue }
                        val start = x
                        while (x < 32 && times[x + z * 32] != 0) x++
                        val run = key(start, x)
                        next[run] = open.remove(run) ?: z
                    }
                }
                for ((run, top) in open) {
                    val start = (run shr 32).toInt()
                    out.add(baseX + start); out.add(baseZ + top); out.add(run.toInt() - start); out.add(z - top)
                }
                open = next
            }
        }
        return out.toIntArray()
    }

    /** Draws the tint on Xaero's world map, under the structures; called from its drawing hook. */
    fun drawWorldMap(dimension: String, buffer: VertexConsumer, matrix: Matrix4f, originX: Int, originZ: Int) {
        if (!shown) return
        val rectangles = snapshot.rectangles[dimension] ?: return
        var i = 0
        while (i < rectangles.size) {
            val x1 = (rectangles[i] * 16 - originX).toFloat()
            val z1 = (rectangles[i + 1] * 16 - originZ).toFloat()
            val x2 = x1 + rectangles[i + 2] * 16
            val z2 = z1 + rectangles[i + 3] * 16
            buffer.addVertex(matrix, x1, z1, 0f).setColor(RED, GREEN, BLUE, ALPHA)
            buffer.addVertex(matrix, x1, z2, 0f).setColor(RED, GREEN, BLUE, ALPHA)
            buffer.addVertex(matrix, x2, z2, 0f).setColor(RED, GREEN, BLUE, ALPHA)
            buffer.addVertex(matrix, x2, z1, 0f).setColor(RED, GREEN, BLUE, ALPHA)
            i += 4
        }
    }

    /** What Bobby has of the chunk at these block coordinates: when it saved it, or that it has not. */
    fun describe(dimension: String, blockX: Int, blockZ: Int): String {
        val chunkX = blockX shr 4
        val chunkZ = blockZ shr 4
        val regions = snapshot.times[dimension] ?: return "Bobby: nothing read for this dimension yet"
        val time = regions[key(chunkX shr 5, chunkZ shr 5)]?.get((chunkX and 31) + (chunkZ and 31) * 32) ?: 0
        return when {
            time == 0 -> "Bobby: chunk $chunkX, $chunkZ not saved"
            time == 1 -> "Bobby: chunk $chunkX, $chunkZ saved (no date)"
            else -> "Bobby: chunk $chunkX, $chunkZ saved ${DATE.format(Instant.ofEpochSecond(time.toLong()))}"
        }
    }

    /** Adds the line saying what Bobby has under the mouse to Xaero's world map screen. */
    fun addTo(screen: Screen) {
        if (available) net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(Label(screen))
    }

    /** The line at the top of the map; takes up no room, so it never gets a click. */
    private class Label(private val screen: Screen) : AbstractWidget(0, 0, 0, 0, Component.empty()) {

        override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
            if (!shown) return
            val dimension = Markers.mapDimension() ?: return
            val (blockX, blockZ) = mouseBlock(screen) ?: return
            val font = Minecraft.getInstance().font
            val text = describe(dimension, blockX, blockZ)
            val width = font.width(text)
            val left = screen.width / 2 - width / 2
            graphics.fill(left - 3, 3, left + width + 3, 5 + font.lineHeight + 1, 0xA0000000.toInt())
            graphics.text(font, text, left, 5, 0xFF000000.toInt() or COLOUR, false)
        }

        override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean = false

        override fun updateWidgetNarration(output: NarrationElementOutput) {}
    }

    private var mouseFields: Pair<Field, Field>? = null
    private var mouseFieldsFailed = false

    /** The block under the mouse, as Xaero worked it out; null if this Xaero keeps it elsewhere. */
    private fun mouseBlock(screen: Screen): Pair<Int, Int>? {
        if (mouseFieldsFailed) return null
        return try {
            val (x, z) = mouseFields ?: run {
                fun field(name: String) = screen.javaClass.getDeclaredField(name).also { it.isAccessible = true }
                (field("mouseBlockPosX") to field("mouseBlockPosZ")).also { mouseFields = it }
            }
            x.getInt(screen) to z.getInt(screen)
        } catch (e: Exception) {
            mouseFieldsFailed = true
            Log.warn("Cannot tell which block the mouse is over on this Xaero's World Map: {}", e.toString())
            null
        }
    }

    internal fun key(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    /** A light cyan, faint enough to see the map through. */
    private const val COLOUR = 0x40D0FF
    private const val RED = ((COLOUR shr 16) and 0xFF) / 255f
    private const val GREEN = ((COLOUR shr 8) and 0xFF) / 255f
    private const val BLUE = (COLOUR and 0xFF) / 255f
    private const val ALPHA = 0.28f

    private const val REFRESH_MILLIS = 5000L
    private const val SECTOR = 4096
    private const val HEADER = 2 * SECTOR
    private const val CHUNKS = 1024
}
