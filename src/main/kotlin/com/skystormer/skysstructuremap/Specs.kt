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
    /**
     * The biome is asked of the group as a whole rather than of each block: blocks anywhere are
     * kept, and it counts once [biomeBlocks] of them stand in an allowed biome. The game
     * asks only where the structure starts, so an outpost's tower can stand in a strip of beach or
     * stony shore beside the plains it belongs to; checking each block lost that tower in testing.
     */
    val biomeAsWhole: Boolean = false,
    /** How many of a group's blocks must stand in an allowed biome, when the biome is asked of the whole group. */
    val biomeBlocks: Int = Tracker.BIOME_BLOCKS,
    /** Or else how near the group sideways one of its biomes must be, when the biome is asked of the whole group; 0 for not at all. */
    val biomeReach: Int = 0,
    /**
     * A block also counts as in its biomes when its section of the chunk (16 blocks each way), or
     * the one above or below, holds one of them. The game asks a wreck's biome only where it starts:
     * its hull runs on into a river, the snowy plains past a frozen ocean, or the plains behind a
     * beach, and wrecks seen whole in Bobby's cache were never found with those blocks left out.
     */
    val biomeNearby: Boolean = false,
    /** Signature blocks never found above these heights in this kind (see [MINESHAFT]). */
    val highest: Map<Block, Int> = emptyMap(),
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
    /** Blocks that alone prove it, which its box is kept near ([Detection.anchors]). */
    val anchors: Set<Block> = emptySet(),
    /**
     * Signature blocks that count only with air right under them, as a roof: an igloo's snow
     * blocks, which are also the ground of every snowy slope, where they lie on more snow or stone.
     */
    val overAir: Set<Block> = emptySet(),
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
        // Not sea lanterns: builds by the sea are lit with them, and a monument's prismarine is plenty.
        setOf(Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE),
        minY = Recognise.MONUMENT_MIN_Y, maxY = Recognise.MONUMENT_MAX_Y, merge = 24,
        recognise = { d ->
            if (d.count < Recognise.MONUMENT_BLOCKS) null
            else d.bounds?.let { seen -> Recognise.monumentBox(seen) { box -> d.countIn(box) } }
        },
        reach = null, waypointAtTop = true,
    )

    private val SHIPWRECK by lazy {
        Spec(
            ShipwreckFit.signatureBlocks, minY = 0, biomes = Biomes.OCEAN + Biomes.BEACH, biomeNearby = true, merge = 4,
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
        // The game asks the biome only where it starts, and the rest runs on into basalt deltas: five
        // bastions in a survival server's world had most of their blocks there and were never matched.
        biomeAsWhole = true,
        // Set once one of its ramparts, walls or bridge matches (see [BastionFit]). Counting blocks
        // and the share of them cracked was not enough: a player's blackstone build passed both.
        recognise = { d -> d.box },
    )

    /** Streets (dirt paths, or smooth sandstone in the desert), beds, the bell and job-site blocks. */
    private val VILLAGE = Spec(
        setOf(
            Blocks.DIRT_PATH, Blocks.BELL, Blocks.SMOOTH_SANDSTONE, Blocks.SMOOTH_SANDSTONE_SLAB, Blocks.SMOOTH_SANDSTONE_STAIRS,
            Blocks.COMPOSTER, Blocks.LECTERN, Blocks.SMOKER, Blocks.BLAST_FURNACE, Blocks.CARTOGRAPHY_TABLE,
            Blocks.FLETCHING_TABLE, Blocks.GRINDSTONE, Blocks.LOOM, Blocks.SMITHING_TABLE, Blocks.STONECUTTER,
            Blocks.BARREL, Blocks.HAY_BLOCK,
            // The doors of the houses, which say where a house is when its job block has been taken
            // (see [VillageHouseFit]): one wood to each kind of village.
            Blocks.OAK_DOOR, Blocks.SPRUCE_DOOR, Blocks.ACACIA_DOOR, Blocks.JUNGLE_DOOR,
            // The blocks a snowy or taiga town centre is built of, so it can still be found when the
            // bell has been taken: none of them grow in a village's own biomes by themselves.
            // Not mossy cobblestone: a taiga's own boulders are made of it, and they dragged a
            // village's box out across the woods and down into a dungeon in testing.
            Blocks.LANTERN, Blocks.PACKED_ICE, Blocks.STRIPPED_SPRUCE_WOOD, Blocks.SPRUCE_TRAPDOOR,
        ),
        // Villages stand on the surface, and only there: without a floor, the mossy cobblestone of a
        // dungeon far below joined one in testing and dragged its box down with it.
        minY = 45,
        tags = listOf(BlockTags.BEDS), biomes = setOf("plains", "meadow", "desert", "savanna", "snowy_plains", "taiga"), merge = 32,
        biomeAsWhole = true,
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
            // Not dark oak logs: with the biome asked of the whole group, a dark forest's trees beside
            // the plains would join it. The tower's own logs still count when it is checked.
            Blocks.BIRCH_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_FENCE, Blocks.DARK_OAK_SLAB,
            Blocks.DARK_OAK_STAIRS, Blocks.WALL_BANNER.white(),
        ),
        minY = 55, biomes = setOf("desert", "plains", "savanna", "snowy_plains", "taiga", "grove") + Biomes.MOUNTAIN, merge = 24,
        biomeAsWhole = true,
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
        // A mansion is its mix at scale: red carpet down every corridor and several of these at once
        // (the test one had 943 red carpet among 12485 blocks), where a player's wooden house has
        // planks and stairs but little carpet.
        // Set once one of its rooms matches (see [MansionFit]).
        recognise = { d -> d.box },
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
        // No biome rule: the game puts strongholds on a ring wherever it falls, and one under an
        // ocean was thrown away block by block in testing. Its end portal frames are proof enough.
        maxY = 60, merge = 24,
        recognise = { d -> boundsIf(d, has(d, Blocks.END_PORTAL_FRAME)) },
    )

    /**
     * Spruce planks and stairs on oak stilts, with the cauldron: nothing else in a swamp is spruce.
     * Proved by its furniture and size ([witchHutByItsRule]) or, looted or damaged, by its shape
     * ([CodeLayout.WITCH_HUT]).
     */
    private val WITCH_HUT = Spec(
        setOf(Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS, Blocks.OAK_FENCE, Blocks.CAULDRON, Blocks.POTTED_RED_MUSHROOM, Blocks.CRAFTING_TABLE),
        biomes = setOf("swamp"), merge = 6,
        recognise = { d -> boundsIf(d, d.count >= 25) },
        reach = null, waypointAtTop = true,
    )

    /**
     * A witch hut's box, when what was seen is one by the rule: the game builds it one size, 7 by 9
     * and a few blocks high (a spruce hut of another size is a player's), and furnishes every one
     * the same, with the cauldron, the crafting table and the potted red mushroom; two of the
     * three, so one taken does not lose the hut.
     */
    fun witchHutByItsRule(d: Detection): Box? {
        val b = d.bounds ?: return null
        val size = listOf(b.sizeX, b.sizeZ).sorted()
        val furniture = listOf(Blocks.CAULDRON, Blocks.CRAFTING_TABLE, Blocks.POTTED_RED_MUSHROOM).count { has(d, it) }
        return if (d.count >= 25 && furniture >= 2 && size == listOf(7, 9) && b.sizeY <= 6) Recognise.witchHutBox(b) else null
    }

    /**
     * An igloo's furnishings: its carpet, bed, furnace, crafting table, redstone torch and trapdoor;
     * and its dome's snow blocks that have air under them, so one emptied of its furnishings is
     * found too. Not the rest of its snow, which is the ground of every snowy slope; the match
     * checks that. Set once its dome matches ([IglooFit]).
     */
    private val IGLOO = Spec(
        IglooFit.votingKinds + Blocks.SNOW_BLOCK, overAir = setOf(Blocks.SNOW_BLOCK),
        biomes = setOf("snowy_taiga", "snowy_plains", "snowy_slopes"), biomeNearby = true, merge = 6,
        recognise = { d -> d.box },
        reach = null, waypointAtTop = true,
    )

    /**
     * A camp's tent (white wool stairs and straw beds) and its campfire. Set once the tent matches
     * ([CampFit]); its box is then what is seen round the tent, as far as a camp reaches.
     */
    private val ABANDONED_CAMP = Spec(
        CampFit.votingKinds + Blocks.CAMPFIRE, merge = 16,
        recognise = { d -> d.box },
        reach = Spec.Reach(2, 2, 4), waypointAtTop = true,
    )

    /**
     * The highest an ocean ruin's blocks are looked for: the game lays one on the floor where it
     * starts however high that is, and three in a survival server's world stood on land at 62 to 71, the
     * biggest 16 high. Out of the sea, only where a ruin starts counts ([OceanRuinFit.startsHere]):
     * looked for this high without that, small warm ruins were fitted on dry land, one in a desert village.
     */
    private const val OCEAN_RUIN_TOP = 90

    /**
     * An ocean ruin's stonework, and the suspicious gravel and sand the game hides its finds in, on
     * the sea floor. Set once one of its designs matches ([OceanRuinFit]); its box is then what is
     * seen round it, which takes in the small ruins round a big one.
     *
     * The game asks the biome only where a ruin starts, on the floor of an ocean: of the ruins a
     * survival server's world missed, most stood on a coast, their blocks in the beach, stony shore, river or
     * jungle beside the ocean. So its blocks are kept whatever the biome, and a group is matched once
     * one of them is in an ocean (or by one, [Spec.biomeNearby]), or an ocean is within 16 blocks of
     * it, where the chunk it starts in has its middle: one inland, a desert village's or a
     * stronghold's, is not. One small warm ruin there stood all in jungle and beach, its start in the
     * ocean next to it.
     */
    private val OCEAN_RUIN = Spec(
        OceanRuinFit.votingKinds + setOf(Blocks.SUSPICIOUS_GRAVEL, Blocks.SUSPICIOUS_SAND),
        maxY = OCEAN_RUIN_TOP, biomes = Biomes.OCEAN, biomeNearby = true, biomeAsWhole = true, biomeBlocks = 1, biomeReach = 16, merge = 16,
        recognise = { d -> d.box },
        reach = Spec.Reach(2, 2, 4),
    )

    /**
     * A chest the game buries on a beach: always in the same column of its chunk (9, 9, its
     * `locate_offset`), sat on the stone or sandstone it dug down to. A player's chest is that
     * nowhere near as often; proved by [BuriedTreasure].
     */
    private val BURIED_TREASURE = Spec(
        setOf(Blocks.CHEST), biomes = Biomes.BEACH, biomeNearby = true, merge = 1,
        recognise = { d -> d.bounds?.takeIf { it.sizeX == 1 && it.sizeZ == 1 && it.sizeY == 1 && Math.floorMod(it.minX, 16) == 9 && Math.floorMod(it.minZ, 16) == 9 } },
        reach = null,
    )

    /**
     * Cobblestone and mossy cobblestone above ground in a jungle (dungeons are below). Its chiselled
     * stone bricks may be counted with another kind, and are hidden underground where a server runs
     * anti-xray, so they are not asked for here: it is proved by its trap or its shape ([Tracker]).
     */
    private val JUNGLE_TEMPLE = Spec(
        setOf(
            Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE_STAIRS, Blocks.CHISELED_STONE_BRICKS,
            Blocks.TRIPWIRE_HOOK, Blocks.DISPENSER, Blocks.STICKY_PISTON, Blocks.LEVER,
        ),
        minY = 50, biomes = setOf("jungle", "bamboo_jungle"), merge = 8,
        // Its footprint once proved ([TemplePieces.jungleBox]).
        recognise = { d -> if (d.count >= 150) TemplePieces.jungleBox(d) else null },
        reach = null, waypointAtTop = true,
    )

    /**
     * Cut and chiselled sandstone with the orange and blue terracotta floor, which desert villages
     * never have. Proved by the floor's cross or, where that was dug up, by its shape ([Tracker]).
     */
    private val DESERT_TEMPLE = Spec(
        setOf(
            Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE, Blocks.SANDSTONE_STAIRS, Blocks.SANDSTONE_SLAB,
            Blocks.DYED_TERRACOTTA.orange(), Blocks.DYED_TERRACOTTA.blue(), Blocks.TNT, Blocks.STONE_PRESSURE_PLATE,
        ),
        biomes = setOf("desert"), merge = 8,
        // Until its floor cross is found; then the box is measured from that ([TemplePieces.desertBox]).
        recognise = { d -> boundsIf(d, d.count >= 40) },
        reach = null, waypointAtTop = true,
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

    /**
     * The obsidian of its broken frame, with the netherrack and gold the game lays round it. Set once
     * one of the game's designs matches ([RuinedPortalFit]): obsidian is everywhere lava meets water.
     */
    private val RUINED_PORTAL = Spec(
        setOf(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.NETHERRACK, Blocks.GOLD_BLOCK), merge = 8,
        recognise = { d -> d.box },
        reach = null, waypointAtTop = true,
    )

    /**
     * The fences its corridors are held up with (oak, or dark oak in the badlands). Proved by their
     * supports, laid the way only the game lays them ([MineshaftPieces]); its box is everything seen.
     */
    private val MINESHAFT = Spec(
        // Not in the deep dark: an ancient city's dark oak frames stand like supports.
        // Oak ones never above y 52: the highest piece of 9,389 in the test worlds. Fences above
        // that are villages' and players', and chained mineshafts kilometres apart into one group.
        // Badlands mineshafts (dark oak) reach the surface, up to y 135.
        setOf(Blocks.OAK_FENCE, Blocks.DARK_OAK_FENCE), notBiomes = listOf("deep_dark"), highest = mapOf(Blocks.OAK_FENCE to 52), merge = 24,
        recognise = { d -> d.bounds },
        reach = Spec.Reach(3, 1, 4),
    )

    /**
     * The spawner: players cannot get one. Proved by the mossy cobblestone floor round it, which a
     * mineshaft's or stronghold's spawner never has; its box is then the room's ([Dungeons]).
     */
    private val DUNGEON = Spec(
        setOf(Blocks.SPAWNER), merge = 2,
        recognise = { d -> d.bounds },
        reach = null,
    )

    private val ANCIENT_CITY = Spec(
        setOf(
            Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS, Blocks.DEEPSLATE_BRICK_STAIRS, Blocks.DEEPSLATE_BRICK_SLAB,
            Blocks.DEEPSLATE_BRICK_WALL, Blocks.DEEPSLATE_TILES, Blocks.CRACKED_DEEPSLATE_TILES, Blocks.DEEPSLATE_TILE_STAIRS,
            Blocks.DEEPSLATE_TILE_SLAB, Blocks.DEEPSLATE_TILE_WALL, Blocks.CHISELED_DEEPSLATE, Blocks.POLISHED_DEEPSLATE,
            Blocks.POLISHED_DEEPSLATE_WALL, Blocks.WOOL.gray(), Blocks.REINFORCED_DEEPSLATE,
        ),
        maxY = 0, biomes = setOf("deep_dark"), merge = 32,
        // Set once its middle, a path or one of its buildings matches (see [AncientCityFit]).
        recognise = { d -> d.box },
        reach = Spec.Reach(2, 1, 5),
    )

    private val END_CITY = Spec(
        setOf(
            Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR, Blocks.PURPUR_STAIRS, Blocks.PURPUR_SLAB,
            Blocks.END_STONE_BRICKS, Blocks.END_ROD, Blocks.STAINED_GLASS.magenta(),
        ),
        biomes = setOf("end_highlands", "end_midlands"), merge = 24,
        // Set once one of its pieces matches (see [EndCityFit]).
        recognise = { d -> d.box },
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
        // Only the spawners and vaults decide it: players cannot place them, while tuff bricks are a
        // common build. A chamber reaches no more than 20 blocks past its outermost spawner or vault
        // (75 measured in the test world), so tuff further off is someone's build: a player's tuff
        // beside one stretched its box 149 blocks.
        recognise = { d -> d.anchors?.let { a -> d.bounds?.clampedTo(a.grow(CHAMBER_REACH)) } },
        reach = Spec.Reach(2, 1, 5),
        anchors = setOf(Blocks.TRIAL_SPAWNER, Blocks.VAULT),
    )

    /** How far past its spawners and vaults a trial chamber's box may reach. */
    private const val CHAMBER_REACH = 32

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
        StructureType.IGLOO, StructureType.ABANDONED_CAMP, StructureType.OCEAN_RUIN -> 9
        StructureType.MANSION -> 21
        StructureType.STRONGHOLD -> 32
        StructureType.TRIAL_CHAMBERS -> 13
        StructureType.MONUMENT -> 6
        StructureType.SHIPWRECK, StructureType.FORTRESS, StructureType.BASTION -> 5
        StructureType.RUINED_PORTAL -> 16
        // One can start in any chunk.
        StructureType.MINESHAFT, StructureType.DUNGEON, StructureType.BURIED_TREASURE -> 1
        StructureType.END_CITY -> 12
        StructureType.END_GATEWAY -> 4
    }

    /**
     * How wide a structure of each kind can be, from measuring every one the game had built in the
     * test world (its saved structure starts, widest of x and z, rounded up for room): two sightings
     * that would still fit inside this are the same structure, not two.
     */
    fun spanOf(type: StructureType): Int = span(type)

    private fun span(type: StructureType): Int = when (type) {
        StructureType.FORTRESS -> 256          // widest measured 219
        StructureType.ANCIENT_CITY -> 256      // 225
        StructureType.VILLAGE -> 200           // 161
        StructureType.TRIAL_CHAMBERS -> 176    // 139
        StructureType.STRONGHOLD -> 160        // 129
        StructureType.END_CITY -> 160          // only two in the test world; a city with its ship reaches further
        StructureType.BASTION -> 96            // 67
        StructureType.MANSION -> 96            // 79
        StructureType.TRAIL_RUINS -> 64        // 52
        StructureType.MONUMENT -> 58           // always exactly this
        StructureType.OUTPOST -> 48            // always exactly this
        StructureType.SHIPWRECK -> 32          // 28
        // Never by width: one can start in any chunk, so two side by side fit in one's width, and
        // saved ones were joined into one that way. Only touching ones are the same mineshaft.
        StructureType.MINESHAFT -> 0
        StructureType.RUINED_PORTAL -> 24      // the giant ones are 16 across
        StructureType.DUNGEON -> 10            // 9 at most
        StructureType.DESERT_TEMPLE -> 24      // 21
        StructureType.JUNGLE_TEMPLE -> 20      // 15
        StructureType.WITCH_HUT -> 12          // 9
        StructureType.IGLOO -> 10              // its dome is 8
        // A big ruin with the small ones the game lays round it, up to 16 blocks off each side.
        StructureType.OCEAN_RUIN -> 56
        // Its tent, campsite and trees, two pieces from the tent (the game's jigsaw size 2).
        StructureType.ABANDONED_CAMP -> 48
        StructureType.BURIED_TREASURE -> 1
        StructureType.END_GATEWAY -> 8
    }

    /**
     * Whether two boxes of [type] are the same structure: close together (within its grouping
     * distance, as blocks seen in one visit are), both inside one structure's width (see [span]),
     * or with middles less than half the least distance two of them can be apart.
     *
     * The width is what joins a big structure seen in pieces. A fortress reaches over 200 blocks and
     * is only recognised where its pieces can be told, so two visits can see two ends that never
     * touch; going by the spacing alone, they were saved as two fortresses (and a structure marked
     * as completed came back unmarked as the other copy).
     */
    fun sameStructure(type: StructureType, a: Box, b: Box): Boolean {
        // A dungeon's box is its room, found round its spawner: two rooms apart are two dungeons,
        // however near. The game often lays them side by side or one over another: in Bobby's
        // cache, two a block apart and two 44 apart, one above the other, were joined as one.
        if (type == StructureType.DUNGEON) return a.overlaps(b)
        if (a.grow(of(type).merge).overlaps(b)) return true
        val both = a.union(b)
        if (maxOf(both.sizeX, both.sizeZ) <= span(type)) return true
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
        StructureType.IGLOO -> IGLOO
        StructureType.ABANDONED_CAMP -> ABANDONED_CAMP
        StructureType.OCEAN_RUIN -> OCEAN_RUIN
        StructureType.BURIED_TREASURE -> BURIED_TREASURE
        StructureType.JUNGLE_TEMPLE -> JUNGLE_TEMPLE
        StructureType.DESERT_TEMPLE -> DESERT_TEMPLE
        StructureType.TRAIL_RUINS -> TRAIL_RUINS
        StructureType.ANCIENT_CITY -> ANCIENT_CITY
        StructureType.TRIAL_CHAMBERS -> TRIAL_CHAMBERS
        StructureType.MONUMENT -> MONUMENT
        StructureType.SHIPWRECK -> SHIPWRECK
        StructureType.RUINED_PORTAL -> RUINED_PORTAL
        StructureType.MINESHAFT -> MINESHAFT
        StructureType.DUNGEON -> DUNGEON
        StructureType.FORTRESS -> FORTRESS
        StructureType.BASTION -> BASTION
        StructureType.END_CITY -> END_CITY
        StructureType.END_GATEWAY -> END_GATEWAY
    }
}
