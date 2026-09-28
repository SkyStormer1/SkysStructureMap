package com.skystormer.skysstructuremap

import com.google.common.net.PercentEscaper
import com.mojang.serialization.Codec
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.chunk.Strategy
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * The chunks the Bobby mod saved of this server, read straight from its files: every block and
 * biome of every chunk you have had loaded since installing Bobby, which [BobbyScan] looks through
 * for structures you passed before this mod was there to see them. Nothing is ever written to
 * Bobby's files, and nothing here needs Bobby to be installed.
 *
 * Bobby keeps them in `.bobby/` in the game folder: a folder per server (its address with `:` as
 * `_`; the world's name in single player), in it one per world seed, then one per dimension
 * (`minecraft/overworld`), holding region files in the game's own format (`r.X.Z.mca`, 32 × 32
 * chunks each). With Bobby's "dynamic multi-world" option on, a dimension's folder holds numbered
 * folders of region files instead, one per world Bobby told apart.
 */
object BobbyCache {

    /** The dimensions structures are looked for in, in the order they are scanned. */
    val DIMENSIONS = listOf(OVERWORLD, NETHER, END)

    /** What was found of this server's cache: region files by dimension, and anything worth knowing about them. */
    class Found(val folder: Path, val regions: Map<String, List<Path>>, val notes: List<String>) {
        val regionCount: Int get() = regions.values.sumOf { it.size }
    }

    /** This server's cache, or why there is none, as a line to show. */
    fun locate(minecraft: Minecraft, level: ClientLevel): Result<Found> {
        val root = minecraft.gameDirectory.toPath().resolve(".bobby")
        if (!Files.isDirectory(root)) return Result.failure(Missing("There is no Bobby cache in this game folder (no .bobby folder)"))
        val name = serverName(minecraft) ?: return Result.failure(Missing("Not on a server or in a single-player world"))
        // Bobby uses the name as it is where the system allows it, and escapes it where not.
        val server = listOf(name, ESCAPER.escape(name)).distinct().map { root.resolve(it) }.firstOrNull { Files.isDirectory(it) }
            ?: return Result.failure(Missing("Bobby has nothing saved for \"$name\". Folders in .bobby: " + listFolders(root)))
        val notes = ArrayList<String>()
        val seeds = Files.list(server).use { s -> s.filter { it.isDirectory() }.toList() }
        if (seeds.isEmpty()) return Result.failure(Missing("Bobby's folder for \"$name\" is empty"))
        // Bobby files a world under its seed's hash, so a server that was reset keeps the old world
        // in a folder of its own; only the world you are in now is wanted.
        val current = seedFolder(level)
        val seed = seeds.firstOrNull { it.name == current } ?: run {
            val newest = seeds.maxBy { Files.getLastModifiedTime(it).toMillis() }
            if (seeds.size > 1) notes.add("Bobby has ${seeds.size} worlds saved for this server; reading the newest (${newest.name})")
            newest
        }
        val regions = LinkedHashMap<String, List<Path>>()
        for (dimension in DIMENSIONS) {
            val (namespace, path) = dimension.split(':', limit = 2)
            val folder = seed.resolve(namespace).resolve(path)
            if (!Files.isDirectory(folder)) continue
            val files = regionFiles(folder).toMutableList()
            val worlds = Files.list(folder).use { s -> s.filter { it.isDirectory() }.toList() }
            for (world in worlds) files.addAll(regionFiles(world))
            if (worlds.isNotEmpty()) {
                notes.add("Bobby told ${worlds.size} worlds apart in the ${dimensionName(dimension)}; all were read")
            }
            if (files.isNotEmpty()) regions[dimension] = files
        }
        if (regions.isEmpty()) return Result.failure(Missing("Bobby's folder for \"$name\" has no chunks saved in it"))
        return Result.success(Found(seed, regions, notes))
    }

    /** Why there is nothing to read, said to the player as it is. */
    class Missing(message: String) : Exception(message)

    /** The name Bobby files this server under. */
    private fun serverName(minecraft: Minecraft): String? {
        minecraft.singleplayerServer?.let { return it.worldData.levelName }
        val server = minecraft.currentServer ?: return null
        if (server.isRealm) return "realms"
        return server.ip.replace(':', '_').ifEmpty { "<empty>" }
    }

    private val ESCAPER = PercentEscaper(".-_ ", false)

    /**
     * The folder Bobby files the world you are in under: the hash of its seed the game gives
     * clients for mixing biomes, which is all a client knows of the seed. Null when it cannot be
     * read, and then the newest folder is taken.
     */
    private fun seedFolder(level: ClientLevel): String? = try {
        val field = BiomeManager::class.java.getDeclaredField("biomeZoomSeed")
        field.isAccessible = true
        field.getLong(level.biomeManager).toString()
    } catch (e: Exception) {
        Log.warn("Could not tell which of Bobby's worlds is this one: {}", e.toString())
        null
    }

    private fun listFolders(root: Path): String =
        Files.list(root).use { s -> s.filter { it.isDirectory() }.map { it.name }.toList() }.ifEmpty { listOf("none") }.joinToString(", ")

    private val REGION_FILE = Regex("""r\.(-?\d+)\.(-?\d+)\.mca""")

    private fun regionFiles(folder: Path): List<Path> =
        Files.list(folder).use { s -> s.filter { REGION_FILE.matches(it.name) }.toList() }

    /** The region a file holds, as its region x and z. */
    fun regionOf(file: Path): Pair<Int, Int> {
        val match = REGION_FILE.matchEntire(file.name) ?: error("not a region file: $file")
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    /** One chunk as read: its sections from [minSectionY] up, air-only ones left out. */
    class Chunk(val minSectionY: Int, val sections: Array<LevelChunkSection?>) {

        fun getBlockState(x: Int, y: Int, z: Int): BlockState {
            val index = (y shr 4) - minSectionY
            if (index < 0 || index >= sections.size) return AIR
            return sections[index]?.getBlockState(x and 15, y and 15, z and 15) ?: AIR
        }
    }

    private val AIR: BlockState = Blocks.AIR.defaultBlockState()

    /**
     * Turns what Bobby saved into chunks: made on the main thread (it needs the game's list of
     * biomes), then used from the scan's own thread. The same codecs Bobby itself reads them with.
     */
    class Reader(level: ClientLevel) {

        private val biomeCodec: Codec<PalettedContainer<Holder<Biome>>>
        private val plains: Holder<Biome>
        private val biomeStrategy: Strategy<Holder<Biome>>
        private val dataVersion = SharedConstants.getCurrentVersion().dataVersion().version()

        init {
            val registry = level.registryAccess().lookupOrThrow(Registries.BIOME)
            plains = registry.get(net.minecraft.world.level.biome.Biomes.PLAINS.identifier()).orElseThrow()
            biomeStrategy = Strategy.createForBiomes(registry.asHolderIdMap())
            biomeCodec = PalettedContainer.codecRW(registry.holderByNameCodec(), biomeStrategy, plains)
        }

        /** Chunks saved by another version of the game, left out: Bobby's `/bobby upgrade` brings them up to date. */
        @Volatile
        var otherVersion = 0
            private set

        /** The chunk, or null when it was saved by another version of the game (see [otherVersion]). */
        fun chunk(tag: CompoundTag): Chunk? {
            if (tag.getIntOr("DataVersion", 0) != dataVersion) {
                otherVersion++
                return null
            }
            val list = tag.getListOrEmpty("sections")
            val read = ArrayList<Pair<Int, LevelChunkSection>>()
            for (i in 0 until list.size) {
                val sectionTag = list.getCompound(i).orElse(null) ?: continue
                val y = sectionTag.getByteOr("Y", 0.toByte()).toInt()
                val blocksTag = sectionTag.getCompound("block_states").orElse(null) ?: continue
                val blocks = BLOCK_CODEC.parse(NbtOps.INSTANCE, blocksTag).promotePartial { }.getOrThrow()
                val biomes = sectionTag.getCompound("biomes")
                    .map { biomeCodec.parse(NbtOps.INSTANCE, it).promotePartial { }.getOrThrow() }
                    .orElseGet { PalettedContainer(plains, biomeStrategy) }
                val section = LevelChunkSection(blocks, biomes)
                section.recalcBlockCounts()
                if (!section.hasOnlyAir()) read.add(y to section)
            }
            if (read.isEmpty()) return Chunk(0, emptyArray())
            val min = read.minOf { it.first }
            val sections = arrayOfNulls<LevelChunkSection>(read.maxOf { it.first } - min + 1)
            for ((y, section) in read) sections[y - min] = section
            return Chunk(min, sections)
        }
    }

    private val BLOCK_CODEC: Codec<PalettedContainer<BlockState>> = PalettedContainer.codecRW(
        BlockState.CODEC,
        Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),
        Blocks.AIR.defaultBlockState(),
    )

    /**
     * One region file, read whole into memory and never written to: Bobby may be saving to it
     * while it is read, and a chunk caught half written is only skipped.
     */
    class Region(private val file: Path) {
        private val bytes: ByteArray = Files.readAllBytes(file)
        private val regionX = regionOf(file).first
        private val regionZ = regionOf(file).second

        /** The chunks in it that were saved, as their x and z. */
        fun chunks(): List<Pair<Int, Int>> {
            if (bytes.size < HEADER) return emptyList()
            val header = ByteBuffer.wrap(bytes, 0, HEADER)
            return (0 until 1024).filter { header.getInt(it * 4) != 0 }
                .map { regionX * 32 + (it and 31) to regionZ * 32 + (it shr 5) }
        }

        /** A chunk's saved data, or null when it is not there or cannot be read. */
        fun tag(chunkX: Int, chunkZ: Int): CompoundTag? {
            if (bytes.size < HEADER) return null
            val entry = ByteBuffer.wrap(bytes).getInt(((chunkX and 31) + (chunkZ and 31) * 32) * 4)
            val start = (entry ushr 8) * SECTOR
            if (entry == 0 || start + 5 > bytes.size) return null
            val length = ByteBuffer.wrap(bytes).getInt(start)
            val kind = bytes[start + 4].toInt() and 0xFF
            val raw: InputStream = if (kind and EXTERNAL != 0) {
                // Too big for the region file, so in a file of its own beside it.
                val outside = file.resolveSibling("c.$chunkX.$chunkZ.mcc")
                if (!Files.exists(outside)) return null
                ByteArrayInputStream(Files.readAllBytes(outside))
            } else {
                if (length < 1 || start + 4 + length > bytes.size) return null
                ByteArrayInputStream(bytes, start + 5, length - 1)
            }
            val stream = when (kind and EXTERNAL.inv()) {
                1 -> GZIPInputStream(raw)
                2 -> InflaterInputStream(raw)
                3 -> raw
                else -> return null
            }
            return DataInputStream(BufferedInputStream(stream)).use { NbtIo.read(it, NbtAccounter.unlimitedHeap()) }
        }

        private companion object {
            const val SECTOR = 4096
            const val HEADER = 2 * SECTOR
            const val EXTERNAL = 128
        }
    }

    /**
     * Blocks read from Bobby's cache for laying the game's designs over a group ([BlockSource]):
     * chunks are read as asked for, and the last few hundred kept.
     */
    class CachedBlocks(regionFiles: List<Path>, private val reader: Reader) : BlockSource {

        /** A dimension's files by region; more than one when Bobby told several worlds apart. */
        private val files: Map<Pair<Int, Int>, List<Path>> = regionFiles.groupBy { regionOf(it) }

        private val regions = object : LinkedHashMap<Path, Region?>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, Region?>) = size > REGIONS_KEPT
        }

        private val chunks = object : LinkedHashMap<Long, Chunk?>(512, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Chunk?>) = size > CHUNKS_KEPT
        }

        private fun region(file: Path): Region? {
            if (regions.containsKey(file)) return regions[file]
            val region = try {
                Region(file)
            } catch (e: Exception) {
                Log.warn("Could not read {}: {}", file, e.toString())
                null
            }
            regions[file] = region
            return region
        }

        private fun chunk(chunkX: Int, chunkZ: Int): Chunk? {
            val key = (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)
            if (chunks.containsKey(key)) return chunks[key]
            val chunk = files[Math.floorDiv(chunkX, 32) to Math.floorDiv(chunkZ, 32)].orEmpty().firstNotNullOfOrNull { file ->
                try {
                    region(file)?.tag(chunkX, chunkZ)?.let(reader::chunk)
                } catch (e: Exception) {
                    null
                }
            }
            chunks[key] = chunk
            return chunk
        }

        override fun hasChunk(chunkX: Int, chunkZ: Int): Boolean = chunk(chunkX, chunkZ) != null

        override fun getBlockState(pos: BlockPos): BlockState =
            chunk(pos.x shr 4, pos.z shr 4)?.getBlockState(pos.x, pos.y, pos.z) ?: AIR

        private companion object {
            const val REGIONS_KEPT = 4
            const val CHUNKS_KEPT = 256
        }
    }
}
