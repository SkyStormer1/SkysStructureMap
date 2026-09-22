package com.skystormer.skysstructuremap

import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * How each kind of structure is found: the blocks that give it away (chosen from the game's own
 * structure templates, or its structure code for the ones built without templates), where they
 * count, when a group of them is enough to be sure, and what being "inside" means.
 */
class Spec(
    /** Signature blocks. */
    val blocks: Set<Block>,
    /** Also signature: any block in one of these tags. */
    val tags: List<net.minecraft.tags.TagKey<Block>> = emptyList(),
    val minY: Int = Int.MIN_VALUE,
    val maxY: Int = Int.MAX_VALUE,
    /**
     * Only in these biomes (their ids without `minecraft:`), taken from the game's own list of
     * where each structure can generate; null for anywhere. A player's base elsewhere, say on a
     * mushroom island, is never taken for one.
     */
    val biomes: Set<String>? = null,
    /** Never in biomes whose id contains one of these. */
    val notBiomes: List<String> = emptyList(),
    /** Blocks this close to a group join it rather than starting another. */
    val merge: Int,
    /** The structure's box once enough is seen, else null. */
    val recognise: (Detection) -> Box?,
    /**
     * How far past each seen cell of blocks still counts as inside, sideways/down/up, for
     * structures whose shape is only known from what has been seen. Null: the box is exact and
     * being inside it is what counts.
     */
    val reach: Reach? = Reach(1, 1, 4),
    /** A waypoint goes on top of it (a surface building) rather than in its middle. */
    val waypointAtTop: Boolean = false,
) {
    class Reach(val sideways: Int, val down: Int, val up: Int)

    fun matches(state: BlockState): Boolean = state.block in blocks || tags.any { state.`is`(it) }

    val biomeFiltered: Boolean get() = biomes != null || notBiomes.isNotEmpty()

    fun biomeAllowed(path: String): Boolean =
        (biomes == null || path in biomes) && notBiomes.none { it in path }
}

/** Groups of biomes the game's structure lists refer to (its `is_ocean`, `is_beach`, `is_mountain` tags). */
object Biomes {
    val OCEAN = setOf(
        "ocean", "deep_ocean", "cold_ocean", "deep_cold_ocean", "lukewarm_ocean", "deep_lukewarm_ocean",
        "warm_ocean", "frozen_ocean", "deep_frozen_ocean",
    )
    val BEACH = setOf("beach", "snowy_beach")
    val MOUNTAIN = setOf("meadow", "frozen_peaks", "jagged_peaks", "stony_peaks", "snowy_slopes", "cherry_grove")
}

object Specs {

    private fun has(detection: Detection, vararg blocks: Block) = blocks.any { (detection.kinds[it] ?: 0) > 0 }

    private fun boundsIf(detection: Detection, enough: Boolean): Box? = if (enough) detection.bounds else null

    private val MONUMENT = Spec(
        setOf(Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, Blocks.SEA_LANTERN),
        minY = Recognise.MONUMENT_MIN_Y, maxY = Recognise.MONUMENT_MAX_Y, merge = 24,
        recognise = { d -> if (d.count >= Recognise.MONUMENT_BLOCKS) d.bounds?.let(Recognise::monumentBox) else null },
        reach = null, waypointAtTop = true,
    )

    private val SHIPWRECK by lazy {
        Spec(
            ShipwreckFit.signatureBlocks, minY = 0, biomes = Biomes.OCEAN + Biomes.BEACH, merge = 4,
            recognise = { d -> d.box }, reach = null, waypointAtTop = true,
        )
    }

    private val FORTRESS = Spec(
        setOf(Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE, Blocks.NETHER_BRICK_STAIRS), merge = 32,
        recognise = { d -> boundsIf(d, d.count >= Recognise.FORTRESS_BLOCKS) },
    )

    private val BASTION = Spec(
        setOf(
            Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS,
            Blocks.POLISHED_BLACKSTONE_BRICK_WALL, Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS,
            Blocks.POLISHED_BLACKSTONE_BRICK_SLAB, Blocks.CHISELED_POLISHED_BLACKSTONE, Blocks.GILDED_BLACKSTONE,
        ),
        biomes = setOf("crimson_forest", "nether_wastes", "soul_sand_valley", "warped_forest"), merge = 24,
        recognise = { d ->
            val b = d.bounds
            boundsIf(d, b != null && d.count >= Recognise.BASTION_BLOCKS && maxOf(b.sizeX, b.sizeZ) >= Recognise.BASTION_WIDTH)
        },
    )

    /** Streets (dirt paths, or smooth sandstone in the desert), beds, the bell and job-site blocks. */
    private val VILLAGE = Spec(
        setOf(
            Blocks.DIRT_PATH, Blocks.BELL, Blocks.SMOOTH_SANDSTONE, Blocks.SMOOTH_SANDSTONE_SLAB, Blocks.SMOOTH_SANDSTONE_STAIRS,
            Blocks.COMPOSTER, Blocks.LECTERN, Blocks.SMOKER, Blocks.BLAST_FURNACE, Blocks.CARTOGRAPHY_TABLE,
            Blocks.FLETCHING_TABLE, Blocks.GRINDSTONE, Blocks.LOOM, Blocks.SMITHING_TABLE, Blocks.STONECUTTER,
            Blocks.BARREL, Blocks.HAY_BLOCK,
        ),
        tags = listOf(BlockTags.BEDS), biomes = setOf("plains", "meadow", "desert", "savanna", "snowy_plains", "taiga"), merge = 32,
        // Set once a town centre matches (see [TownCentreFit]).
        recognise = { d -> d.box },
        // Houses stand beside the streets, so a little way off the paths is still in the village.
        reach = Spec.Reach(6, 2, 8),
    )

    /**
     * Birch and dark oak planks with the white banners. Mansions (and ancient cities, underground)
     * use the same planks but never the banners, and a mansion's edge can reach out of its dark
     * forest, so the banners are what decide it: in testing a mansion was also taken for an outpost.
     */
    private val OUTPOST = Spec(
        setOf(
            Blocks.BIRCH_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_FENCE, Blocks.DARK_OAK_SLAB,
            Blocks.DARK_OAK_STAIRS, Blocks.WALL_BANNER.white(),
        ),
        minY = 55, biomes = setOf("desert", "plains", "savanna", "snowy_plains", "taiga", "grove") + Biomes.MOUNTAIN, merge = 24,
        // Set by matching the watchtower (see [WatchtowerFit]).
        recognise = { d -> d.box },
        reach = null, waypointAtTop = true,
    )

    private val MANSION = Spec(
        setOf(
            Blocks.BIRCH_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.OAK_PLANKS, Blocks.CARPET.red(), Blocks.POLISHED_ANDESITE,
            Blocks.WOOL.lightGray(), Blocks.WOOL.black(), Blocks.BOOKSHELF, Blocks.BIRCH_STAIRS, Blocks.DARK_OAK_STAIRS,
        ),
        minY = 50, biomes = setOf("dark_forest", "pale_garden"), merge = 24,
        recognise = { d -> boundsIf(d, d.count >= 300) },
    )

    /**
     * Stone bricks underground, and the portal room's frames. Only the frames decide it: stone
     * bricks alone were taken for a stronghold in a player's base. Once they are seen, the stone
     * brick corridors around them make up the rest of its box.
     */
    private val STRONGHOLD = Spec(
        setOf(
            Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS,
            Blocks.INFESTED_STONE_BRICKS, Blocks.INFESTED_MOSSY_STONE_BRICKS, Blocks.INFESTED_CRACKED_STONE_BRICKS,
            Blocks.INFESTED_CHISELED_STONE_BRICKS, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB, Blocks.END_PORTAL_FRAME,
        ),
        maxY = 60, notBiomes = listOf("ocean"), merge = 24,
        recognise = { d -> boundsIf(d, has(d, Blocks.END_PORTAL_FRAME)) },
    )

    /** Spruce planks and stairs on oak stilts, with the cauldron: nothing else in a swamp is spruce. */
    private val WITCH_HUT = Spec(
        setOf(Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS, Blocks.OAK_FENCE, Blocks.CAULDRON, Blocks.POTTED_RED_MUSHROOM, Blocks.CRAFTING_TABLE),
        biomes = setOf("swamp"), merge = 6,
        recognise = { d -> if (d.count >= 25 && has(d, Blocks.CAULDRON)) d.bounds?.let(Recognise::witchHutBox) else null },
        reach = null, waypointAtTop = true,
    )

    /** Cobblestone and mossy cobblestone above ground in a jungle (dungeons are below), with its chiselled stone bricks. */
    private val JUNGLE_TEMPLE = Spec(
        setOf(
            Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.CHISELED_STONE_BRICKS,
            Blocks.TRIPWIRE_HOOK, Blocks.DISPENSER, Blocks.STICKY_PISTON, Blocks.LEVER,
        ),
        minY = 50, biomes = setOf("jungle", "bamboo_jungle"), merge = 8,
        recognise = { d -> boundsIf(d, d.count >= 150 && has(d, Blocks.CHISELED_STONE_BRICKS)) },
        reach = Spec.Reach(1, 1, 3), waypointAtTop = true,
    )

    /** Cut and chiselled sandstone with the orange and blue terracotta floor, which desert villages never have. */
    private val DESERT_TEMPLE = Spec(
        setOf(
            Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE, Blocks.SANDSTONE_STAIRS, Blocks.SANDSTONE_SLAB,
            Blocks.DYED_TERRACOTTA.orange(), Blocks.DYED_TERRACOTTA.blue(), Blocks.TNT, Blocks.STONE_PRESSURE_PLATE,
        ),
        biomes = setOf("desert"), merge = 8,
        recognise = { d -> boundsIf(d, d.count >= 40 && has(d, Blocks.DYED_TERRACOTTA.blue()) && has(d, Blocks.DYED_TERRACOTTA.orange())) },
        reach = Spec.Reach(1, 1, 3), waypointAtTop = true,
    )

    /** Mud bricks, bricks, coloured and glazed terracotta and suspicious gravel: what trail ruins are built of. */
    private val TRAIL_RUINS = Spec(
        setOf(
            Blocks.MUD_BRICKS, Blocks.MUD_BRICK_STAIRS, Blocks.MUD_BRICK_SLAB, Blocks.MUD_BRICK_WALL, Blocks.PACKED_MUD,
            Blocks.SUSPICIOUS_GRAVEL, Blocks.BRICKS, Blocks.BRICK_SLAB, Blocks.BRICK_STAIRS, Blocks.BRICK_WALL,
        ) + Blocks.DYED_TERRACOTTA.asList() + Blocks.GLAZED_TERRACOTTA.asList(),
        biomes = setOf("taiga", "snowy_taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga", "old_growth_birch_forest", "jungle"), merge = 16,
        // Set once one of the game's towers matches ([TrailRuinsFit]): a player's mud brick or
        // terracotta build was taken for trail ruins, which the tower rules out.
        recognise = { d -> d.box },
        reach = Spec.Reach(1, 1, 3),
    )

    private val ANCIENT_CITY = Spec(
        setOf(
            Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS, Blocks.DEEPSLATE_BRICK_STAIRS, Blocks.DEEPSLATE_BRICK_SLAB,
            Blocks.DEEPSLATE_BRICK_WALL, Blocks.DEEPSLATE_TILES, Blocks.CRACKED_DEEPSLATE_TILES, Blocks.DEEPSLATE_TILE_STAIRS,
            Blocks.DEEPSLATE_TILE_SLAB, Blocks.DEEPSLATE_TILE_WALL, Blocks.CHISELED_DEEPSLATE, Blocks.POLISHED_DEEPSLATE,
            Blocks.POLISHED_DEEPSLATE_WALL, Blocks.WOOL.gray(), Blocks.REINFORCED_DEEPSLATE,
        ),
        maxY = 0, biomes = setOf("deep_dark"), merge = 32,
        recognise = { d -> boundsIf(d, d.count >= 300) },
        reach = Spec.Reach(2, 1, 5),
    )

    private val END_CITY = Spec(
        setOf(
            Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR, Blocks.PURPUR_STAIRS, Blocks.PURPUR_SLAB,
            Blocks.END_STONE_BRICKS, Blocks.END_ROD, Blocks.STAINED_GLASS.magenta(),
        ),
        biomes = setOf("end_highlands", "end_midlands"), merge = 24,
        recognise = { d -> boundsIf(d, d.count >= 60) },
    )

    /**
     * The gateway block itself: nothing else in the End makes one. Its box is the bedrock shell
     * around it, one block each side and two above and below.
     */
    private val END_GATEWAY = Spec(
        setOf(Blocks.END_GATEWAY), merge = 4,
        recognise = { d -> d.bounds?.grow(1, down = 2, up = 2) },
        reach = null,
    )

    /**
     * Tuff bricks, polished and chiselled tuff (plain tuff is natural, these are not), with the
     * trial spawners and vaults, deep underground.
     */
    private val TRIAL_CHAMBERS = Spec(
        setOf(
            Blocks.TUFF_BRICKS, Blocks.POLISHED_TUFF, Blocks.CHISELED_TUFF_BRICKS, Blocks.CHISELED_TUFF,
            Blocks.TRIAL_SPAWNER, Blocks.VAULT,
        ),
        maxY = 10, merge = 32,
        recognise = { d -> boundsIf(d, has(d, Blocks.TRIAL_SPAWNER, Blocks.VAULT) || d.count >= 200) },
        reach = Spec.Reach(2, 1, 5),
    )

    /**
     * The least distance, in blocks, between two structures of this kind, from the game's
     * structure sets: each sits somewhere in its own region of `spacing` chunks, at least
     * `separation` chunks from the next region's edge, so two are never less than
     * (separation + 1) chunks apart. Strongholds sit on rings far apart; end gateways in a ring 96
     * blocks out, each 90-odd from the next.
     */
    private fun apart(type: StructureType): Int = 16 * when (type) {
        StructureType.VILLAGE, StructureType.TRAIL_RUINS -> 9
        StructureType.OUTPOST, StructureType.WITCH_HUT, StructureType.JUNGLE_TEMPLE,
        StructureType.DESERT_TEMPLE, StructureType.ANCIENT_CITY -> 9
        StructureType.MANSION -> 21
        StructureType.STRONGHOLD -> 32
        StructureType.TRIAL_CHAMBERS -> 13
        StructureType.MONUMENT -> 6
        StructureType.SHIPWRECK, StructureType.FORTRESS, StructureType.BASTION -> 5
        StructureType.END_CITY -> 12
        StructureType.END_GATEWAY -> 4
    }

    /**
     * Whether two boxes of [type] are the same structure: close together (within its grouping
     * distance, as blocks seen in one visit are), or with middles less than half the least distance
     * two of them can be apart. The second is what joins a structure seen half on one visit and half
     * on the next: in testing one trial chambers was saved twice, the halves about 70 blocks apart.
     */
    fun sameStructure(type: StructureType, a: Box, b: Box): Boolean {
        if (a.grow(of(type).merge).overlaps(b)) return true
        val dx = Math.abs(a.centreX - b.centreX)
        val dz = Math.abs(a.centreZ - b.centreZ)
        return maxOf(dx, dz) < apart(type) / 2
    }

    fun of(type: StructureType): Spec = when (type) {
        StructureType.VILLAGE -> VILLAGE
        StructureType.OUTPOST -> OUTPOST
        StructureType.MANSION -> MANSION
        StructureType.STRONGHOLD -> STRONGHOLD
        StructureType.WITCH_HUT -> WITCH_HUT
        StructureType.JUNGLE_TEMPLE -> JUNGLE_TEMPLE
        StructureType.DESERT_TEMPLE -> DESERT_TEMPLE
        StructureType.TRAIL_RUINS -> TRAIL_RUINS
        StructureType.ANCIENT_CITY -> ANCIENT_CITY
        StructureType.TRIAL_CHAMBERS -> TRIAL_CHAMBERS
        StructureType.MONUMENT -> MONUMENT
        StructureType.SHIPWRECK -> SHIPWRECK
        StructureType.FORTRESS -> FORTRESS
        StructureType.BASTION -> BASTION
        StructureType.END_CITY -> END_CITY
        StructureType.END_GATEWAY -> END_GATEWAY
    }
}
