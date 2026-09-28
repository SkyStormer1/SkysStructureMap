// Which of the game's own designs each kind of structure is matched against, and how; the matching
// itself is in TemplateFit. The designs are read from the game's own files, so they are always the
// ones your copy of Minecraft builds with.

package com.skystormer.skysstructuremap

import net.minecraft.world.level.block.Blocks

/** The game's 20 shipwreck designs, each in eight woods. */
object ShipwreckFit : TemplateFit(
    "shipwreck",
    listOf(
        "with_mast", "with_mast_degraded",
        "rightsideup_full", "rightsideup_full_degraded", "rightsideup_fronthalf", "rightsideup_fronthalf_degraded",
        "rightsideup_backhalf", "rightsideup_backhalf_degraded",
        "sideways_full", "sideways_full_degraded", "sideways_fronthalf", "sideways_fronthalf_degraded",
        "sideways_backhalf", "sideways_backhalf_degraded",
        "upsidedown_full", "upsidedown_full_degraded", "upsidedown_fronthalf", "upsidedown_fronthalf_degraded",
        "upsidedown_backhalf", "upsidedown_backhalf_degraded",
    ),
    anyWood = true,
)

/**
 * A pillager outpost's watchtower, plain or overgrown. Its birch and dark oak planks, fences and
 * white banners are what a player might build with too (a house was taken for an outpost), but not
 * the tower itself.
 */
object WatchtowerFit : TemplateFit(
    "pillager_outpost", listOf("watchtower", "watchtower_overgrown"), anyWood = false,
    // Many voters, few needing to agree: a group can take in a player's build or a griefed tower's
    // leftovers beside it, and 25 voters spread over all that never agreed on the tower in testing.
    agreement = 0.2, samples = 400, minVotes = 25,
)

/**
 * A village's town centre, the meeting point or fountain with its bell that every village grows
 * from, for each kind of village. A player's base can have paths, beds, a bell and job blocks, but
 * not one of these laid out as the game lays them.
 */
object TownCentreFit : TemplateFit(
    "village",
    listOf(
        "plains/town_centers/plains_fountain_01", "plains/town_centers/plains_meeting_point_1",
        "plains/town_centers/plains_meeting_point_2", "plains/town_centers/plains_meeting_point_3",
        "desert/town_centers/desert_meeting_point_1", "desert/town_centers/desert_meeting_point_2",
        "desert/town_centers/desert_meeting_point_3",
        "savanna/town_centers/savanna_meeting_point_1", "savanna/town_centers/savanna_meeting_point_2",
        "savanna/town_centers/savanna_meeting_point_3", "savanna/town_centers/savanna_meeting_point_4",
        "snowy/town_centers/snowy_meeting_point_1", "snowy/town_centers/snowy_meeting_point_2",
        "snowy/town_centers/snowy_meeting_point_3",
        "taiga/town_centers/taiga_meeting_point_1", "taiga/town_centers/taiga_meeting_point_2",
    ),
    anyWood = false,
    agreement = 0.2,
    samples = 400,
)

/**
 * The tower every trail ruins grows from, in its five designs. They are mostly gravel, coloured and
 * glazed terracotta and bricks, and buried; the houses and roads around them vote too, hence the
 * low agreement, with every placement checked block for block.
 */
object TrailRuinsFit : TemplateFit(
    "trail_ruins",
    (1..5).map { "tower/tower_$it" },
    anyWood = false,
    agreement = 0.2,
    samples = 400,
    aliases = mapOf("dirt" to "gravel", "coarse_dirt" to "gravel", "suspicious_gravel" to "gravel", "packed_mud" to "mud_bricks"),
    loose = setOf("gravel"),
    minVotes = 25,
)

/**
 * An end city's pieces: every city starts from its base floor, and the game places each piece as
 * designed. Only the rooms, towers and ship: roofs and bridges are plain purpur a player could lay.
 */
object EndCityFit : TemplateFit(
    "end_city",
    listOf(
        "base_floor", "second_floor_1", "second_floor_2", "third_floor_1", "third_floor_2", "tower_base", "tower_top",
        "fat_tower_base", "fat_tower_middle", "fat_tower_top", "ship",
    ),
    anyWood = false,
    agreement = 0.2,
    samples = 400,
    minVotes = 25,
)

/**
 * A bastion remnant's ramparts, walls and bridge, for each of the four kinds the game builds
 * (units, hoglin stable, treasure room, bridge). Only their stairs, slabs, walls and chiselled
 * blocks vote: a bastion is thousands of polished blackstone bricks, which say little about where a
 * design sits and would be slow to pair up. Gilded blackstone never votes either, because the game
 * scatters that itself, one blackstone block in a hundred.
 *
 * What the game changes as it builds one is allowed for: it cracks three in ten of the polished
 * bricks, and turns half the gilded blackstone back into plain blackstone (and one blackstone in a
 * hundred into gilded), so those pairs count as the same block.
 */
object BastionFit : TemplateFit(
    "bastion",
    listOf(
        "units/ramparts/ramparts_0", "units/ramparts/ramparts_1", "units/walls/wall_base",
        "hoglin_stable/ramparts/ramparts_1", "hoglin_stable/ramparts/ramparts_2",
        "hoglin_stable/walls/side_wall_0", "hoglin_stable/walls/side_wall_1", "hoglin_stable/walls/wall_base",
        "bridge/starting_pieces/entrance", "bridge/ramparts/rampart_0", "bridge/ramparts/rampart_1",
        "bridge/bridge_pieces/bridge",
        "treasure/ramparts/mid_wall_main", "treasure/ramparts/mid_wall_side", "treasure/ramparts/top_wall",
        "treasure/walls/bottom/wall_1",
    ),
    anyWood = false,
    agreement = 0.2,
    samples = 300,
    aliases = mapOf(
        "cracked_polished_blackstone_bricks" to "polished_blackstone_bricks",
        "gilded_blackstone" to "blackstone",
    ),
    minVotes = 5,
    votingKinds = setOf(
        Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Blocks.POLISHED_BLACKSTONE_BRICK_SLAB,
        Blocks.POLISHED_BLACKSTONE_BRICK_WALL, Blocks.CHISELED_POLISHED_BLACKSTONE,
    ),
)

/**
 * An ancient city's middle, where its frame of reinforced deepslate stands, and the buildings and
 * paths around it. Only the uncommon blocks vote: a city is tens of thousands of deepslate bricks
 * and tiles, which say little about where a design sits.
 */
object AncientCityFit : TemplateFit(
    "ancient_city",
    listOf(
        "city_center/city_center_1", "city_center/city_center_2", "city_center/city_center_3",
        "city/entrance/entrance_path_1", "city/entrance/entrance_path_2", "city/entrance/entrance_path_3",
        "city/entrance/entrance_path_4", "city/entrance/entrance_path_5", "city/entrance/entrance_connector",
        "structures/sauna_1", "structures/barracks", "structures/large_ruin_1", "structures/medium_ruin_1",
        "structures/small_statue", "structures/tall_ruin_1", "structures/ice_box_1",
    ),
    anyWood = false,
    agreement = 0.2,
    samples = 300,
    aliases = mapOf(
        "cracked_deepslate_bricks" to "deepslate_bricks",
        "cracked_deepslate_tiles" to "deepslate_tiles",
    ),
    minVotes = 5,
    votingKinds = setOf(
        Blocks.CHISELED_DEEPSLATE, Blocks.REINFORCED_DEEPSLATE, Blocks.DEEPSLATE_TILE_WALL,
        Blocks.DEEPSLATE_BRICK_WALL, Blocks.POLISHED_DEEPSLATE_WALL, Blocks.DEEPSLATE_BRICK_SLAB,
    ),
)

/**
 * A woodland mansion's rooms: its entrance hall and the big two-by-two rooms it is built from. A
 * player's dark oak house has the same planks, but not a room laid out as the game lays it.
 */
object MansionFit : TemplateFit(
    "woodland_mansion",
    listOf(
        "entrance", "2x2_a1", "2x2_a2", "2x2_a3", "2x2_a4", "2x2_b1", "2x2_b2", "2x2_b3", "2x2_b4", "2x2_b5",
        "2x2_s1", "1x2_c_stairs", "1x2_d_stairs", "1x1_b1", "1x1_b2", "1x1_b3", "1x1_b4", "1x1_b5",
        "1x2_a1", "1x2_b1", "1x2_c1", "1x2_d1", "1x2_se1",
    ),
    anyWood = false,
    agreement = 0.2,
    samples = 400,
    minVotes = 15,
    votingKinds = setOf(
        Blocks.CARPET.red(), Blocks.POLISHED_ANDESITE, Blocks.WOOL.lightGray(), Blocks.WOOL.black(),
        Blocks.BOOKSHELF, Blocks.BIRCH_STAIRS, Blocks.DARK_OAK_STAIRS,
    ),
)

/**
 * A village's working houses: the library, the smithy, the farm and the rest, for every kind of
 * village. Each has its own job block in one or two spots, which says where the house sits, so a
 * village is still known by a house when its town centre has been pulled down.
 */
object VillageHouseFit : TemplateFit(
    "village",
    listOf(
        "desert/houses/desert_armorer_1",
        "desert/houses/desert_butcher_shop_1",
        "desert/houses/desert_cartographer_house_1",
        "desert/houses/desert_farm_1",
        "desert/houses/desert_farm_2",
        "desert/houses/desert_fisher_1",
        "desert/houses/desert_fletcher_house_1",
        "desert/houses/desert_large_farm_1",
        "desert/houses/desert_library_1",
        "desert/houses/desert_mason_1",
        "desert/houses/desert_shepherd_house_1",
        "desert/houses/desert_tannery_1",
        "desert/houses/desert_tool_smith_1",
        "desert/houses/desert_weaponsmith_1",
        "plains/houses/plains_armorer_house_1",
        "plains/houses/plains_butcher_shop_1",
        "plains/houses/plains_butcher_shop_2",
        "plains/houses/plains_cartographer_1",
        "plains/houses/plains_fisher_cottage_1",
        "plains/houses/plains_fletcher_house_1",
        "plains/houses/plains_large_farm_1",
        "plains/houses/plains_library_1",
        "plains/houses/plains_library_2",
        "plains/houses/plains_masons_house_1",
        "plains/houses/plains_shepherds_house_1",
        "plains/houses/plains_small_farm_1",
        "plains/houses/plains_tannery_1",
        "plains/houses/plains_tool_smith_1",
        "plains/houses/plains_weaponsmith_1",
        "savanna/houses/savanna_armorer_1",
        "savanna/houses/savanna_butchers_shop_1",
        "savanna/houses/savanna_butchers_shop_2",
        "savanna/houses/savanna_cartographer_1",
        "savanna/houses/savanna_fisher_cottage_1",
        "savanna/houses/savanna_fletcher_house_1",
        "savanna/houses/savanna_large_farm_1",
        "savanna/houses/savanna_large_farm_2",
        "savanna/houses/savanna_library_1",
        "savanna/houses/savanna_mason_1",
        "savanna/houses/savanna_shepherd_1",
        "savanna/houses/savanna_small_farm",
        "savanna/houses/savanna_tannery_1",
        "savanna/houses/savanna_tool_smith_1",
        "savanna/houses/savanna_weaponsmith_1",
        "savanna/houses/savanna_weaponsmith_2",
        "snowy/houses/snowy_armorer_house_1",
        "snowy/houses/snowy_armorer_house_2",
        "snowy/houses/snowy_butchers_shop_1",
        "snowy/houses/snowy_butchers_shop_2",
        "snowy/houses/snowy_cartographer_house_1",
        "snowy/houses/snowy_farm_1",
        "snowy/houses/snowy_farm_2",
        "snowy/houses/snowy_fisher_cottage",
        "snowy/houses/snowy_fletcher_house_1",
        "snowy/houses/snowy_library_1",
        "snowy/houses/snowy_masons_house_1",
        "snowy/houses/snowy_masons_house_2",
        "snowy/houses/snowy_shepherds_house_1",
        "snowy/houses/snowy_tannery_1",
        "snowy/houses/snowy_tool_smith_1",
        "taiga/houses/taiga_armorer_2",
        "taiga/houses/taiga_armorer_house_1",
        "taiga/houses/taiga_butcher_shop_1",
        "taiga/houses/taiga_cartographer_house_1",
        "taiga/houses/taiga_fisher_cottage_1",
        "taiga/houses/taiga_fletcher_house_1",
        "taiga/houses/taiga_large_farm_1",
        "taiga/houses/taiga_large_farm_2",
        "taiga/houses/taiga_library_1",
        "taiga/houses/taiga_masons_house_1",
        "taiga/houses/taiga_shepherds_house_1",
        "taiga/houses/taiga_small_farm_1",
        "taiga/houses/taiga_tannery_1",
        "taiga/houses/taiga_tool_smith_1",
        "taiga/houses/taiga_weaponsmith_1",
        "taiga/houses/taiga_weaponsmith_2",
    ),
    anyWood = false,
)
