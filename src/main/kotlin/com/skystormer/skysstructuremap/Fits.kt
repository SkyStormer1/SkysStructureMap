// Which of the game's own designs each kind of structure is matched against, and how; the matching
// itself is in TemplateFit. The designs are read from the game's own files, so they are always the
// ones your copy of Minecraft builds with.

package com.skystormer.skysstructuremap

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.material.Fluids

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
) {
    /**
     * Why a fit cannot be a wreck from where it lies in its chunk, or null when it can. The game
     * lays every wreck from its chunk's corner, turned about one block of the design (4, 15), so
     * each way round its corner lands on one spot; all 16 wrecks tried, of every shape and turn,
     * were there. A player's wooden build that fits a wreck is on that spot one time in 256.
     */
    fun refusal(match: Match): String? {
        val (x, z) = OFFSETS.getValue(match.rotation)
        val spot = Math.floorMod(x, 16) to Math.floorMod(z, 16)
        return if (match.inChunk == spot) null else "a wreck at ${match.inChunk.first},${match.inChunk.second} in its chunk, not $spot"
    }

    /** The chunk a wreck that fits starts in: the one whose corner it was laid from. */
    fun startChunk(match: Match): Pair<Int, Int> {
        val (x, z) = OFFSETS.getValue(match.rotation)
        return Math.floorDiv(BlockPos.getX(match.origin) - x, 16) to Math.floorDiv(BlockPos.getZ(match.origin) - z, 16)
    }

    /** How far the design's corner lies from its chunk's corner, each way round: (4, 15) less itself turned. */
    private val OFFSETS = mapOf(
        net.minecraft.world.level.block.Rotation.NONE to (0 to 0),
        net.minecraft.world.level.block.Rotation.CLOCKWISE_90 to (19 to 11),
        net.minecraft.world.level.block.Rotation.CLOCKWISE_180 to (8 to 30),
        net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90 to (-11 to 19),
    )
}

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
) {
    /** A tower is where the ruins start, so the game lays its corner on its chunk's corner, as all 166 in the test worlds were. */
    fun refusal(match: Match): String? = if (match.inChunk == (0 to 0)) null else "a tower off its chunk's corner"
}

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
 * A village's houses: homes, workplaces, temples and stables, for every kind of village; every
 * design with a door or a job block to find it by (the animal pens, the plain temples and the
 * two open-air meeting points have neither). A door or a job block only says where a design would
 * have to sit; the design decides, block for block, so a player's house never passes. Doors are
 * what players leave, where they take bells and workstations. The farms are [VillageFarmFit].
 *
 * Tried by door alone on real villages of all five kinds and three on a survival server, with
 * bells and job blocks left out: every one was found, its houses matching 89 to 100%; around four
 * player bases and a player's farm nothing fitted at all.
 */
object VillageHouseFit : TemplateFit(
    "village",
    listOf(
        "desert/houses/desert_armorer_1",
        "desert/houses/desert_butcher_shop_1",
        "desert/houses/desert_cartographer_house_1",
        "desert/houses/desert_fisher_1",
        "desert/houses/desert_fletcher_house_1",
        "desert/houses/desert_library_1",
        "desert/houses/desert_mason_1",
        "desert/houses/desert_medium_house_1",
        "desert/houses/desert_medium_house_2",
        "desert/houses/desert_shepherd_house_1",
        "desert/houses/desert_small_house_1",
        "desert/houses/desert_small_house_2",
        "desert/houses/desert_small_house_3",
        "desert/houses/desert_small_house_4",
        "desert/houses/desert_small_house_5",
        "desert/houses/desert_small_house_6",
        "desert/houses/desert_small_house_7",
        "desert/houses/desert_small_house_8",
        "desert/houses/desert_tannery_1",
        "desert/houses/desert_tool_smith_1",
        "desert/houses/desert_weaponsmith_1",
        "plains/houses/plains_armorer_house_1",
        "plains/houses/plains_big_house_1",
        "plains/houses/plains_butcher_shop_1",
        "plains/houses/plains_butcher_shop_2",
        "plains/houses/plains_cartographer_1",
        "plains/houses/plains_fisher_cottage_1",
        "plains/houses/plains_fletcher_house_1",
        "plains/houses/plains_library_1",
        "plains/houses/plains_library_2",
        "plains/houses/plains_masons_house_1",
        "plains/houses/plains_medium_house_1",
        "plains/houses/plains_medium_house_2",
        "plains/houses/plains_shepherds_house_1",
        "plains/houses/plains_small_house_1",
        "plains/houses/plains_small_house_2",
        "plains/houses/plains_small_house_3",
        "plains/houses/plains_small_house_4",
        "plains/houses/plains_small_house_5",
        "plains/houses/plains_small_house_6",
        "plains/houses/plains_small_house_7",
        "plains/houses/plains_small_house_8",
        "plains/houses/plains_stable_1",
        "plains/houses/plains_stable_2",
        "plains/houses/plains_tannery_1",
        "plains/houses/plains_temple_3",
        "plains/houses/plains_temple_4",
        "plains/houses/plains_tool_smith_1",
        "plains/houses/plains_weaponsmith_1",
        "savanna/houses/savanna_armorer_1",
        "savanna/houses/savanna_butchers_shop_1",
        "savanna/houses/savanna_butchers_shop_2",
        "savanna/houses/savanna_cartographer_1",
        "savanna/houses/savanna_fisher_cottage_1",
        "savanna/houses/savanna_fletcher_house_1",
        "savanna/houses/savanna_library_1",
        "savanna/houses/savanna_mason_1",
        "savanna/houses/savanna_medium_house_1",
        "savanna/houses/savanna_medium_house_2",
        "savanna/houses/savanna_shepherd_1",
        "savanna/houses/savanna_small_house_1",
        "savanna/houses/savanna_small_house_2",
        "savanna/houses/savanna_small_house_3",
        "savanna/houses/savanna_small_house_4",
        "savanna/houses/savanna_small_house_5",
        "savanna/houses/savanna_small_house_6",
        "savanna/houses/savanna_small_house_7",
        "savanna/houses/savanna_small_house_8",
        "savanna/houses/savanna_tannery_1",
        "savanna/houses/savanna_temple_1",
        "savanna/houses/savanna_temple_2",
        "savanna/houses/savanna_tool_smith_1",
        "savanna/houses/savanna_weaponsmith_1",
        "savanna/houses/savanna_weaponsmith_2",
        "snowy/houses/snowy_armorer_house_1",
        "snowy/houses/snowy_armorer_house_2",
        "snowy/houses/snowy_butchers_shop_1",
        "snowy/houses/snowy_butchers_shop_2",
        "snowy/houses/snowy_cartographer_house_1",
        "snowy/houses/snowy_fisher_cottage",
        "snowy/houses/snowy_fletcher_house_1",
        "snowy/houses/snowy_library_1",
        "snowy/houses/snowy_masons_house_1",
        "snowy/houses/snowy_masons_house_2",
        "snowy/houses/snowy_medium_house_1",
        "snowy/houses/snowy_medium_house_2",
        "snowy/houses/snowy_medium_house_3",
        "snowy/houses/snowy_shepherds_house_1",
        "snowy/houses/snowy_small_house_1",
        "snowy/houses/snowy_small_house_2",
        "snowy/houses/snowy_small_house_3",
        "snowy/houses/snowy_small_house_4",
        "snowy/houses/snowy_small_house_5",
        "snowy/houses/snowy_small_house_6",
        "snowy/houses/snowy_small_house_7",
        "snowy/houses/snowy_small_house_8",
        "snowy/houses/snowy_tannery_1",
        "snowy/houses/snowy_temple_1",
        "snowy/houses/snowy_tool_smith_1",
        "snowy/houses/snowy_weapon_smith_1",
        "taiga/houses/taiga_armorer_2",
        "taiga/houses/taiga_armorer_house_1",
        "taiga/houses/taiga_butcher_shop_1",
        "taiga/houses/taiga_cartographer_house_1",
        "taiga/houses/taiga_fisher_cottage_1",
        "taiga/houses/taiga_fletcher_house_1",
        "taiga/houses/taiga_library_1",
        "taiga/houses/taiga_masons_house_1",
        "taiga/houses/taiga_medium_house_1",
        "taiga/houses/taiga_medium_house_2",
        "taiga/houses/taiga_medium_house_3",
        "taiga/houses/taiga_medium_house_4",
        "taiga/houses/taiga_shepherds_house_1",
        "taiga/houses/taiga_small_house_1",
        "taiga/houses/taiga_small_house_2",
        "taiga/houses/taiga_small_house_3",
        "taiga/houses/taiga_small_house_4",
        "taiga/houses/taiga_small_house_5",
        "taiga/houses/taiga_tannery_1",
        "taiga/houses/taiga_temple_1",
        "taiga/houses/taiga_tool_smith_1",
        "taiga/houses/taiga_weaponsmith_1",
        "taiga/houses/taiga_weaponsmith_2",
    ),
    anyWood = false,
)

/**
 * A village's farms, every kind. A farm is farmland, water, crops, logs and a composter, which is
 * what a player's farm is too (one was taken for a village at 58%, barely ahead of a desert farm),
 * so the design fitting is not enough: the farm must also stand where the game puts one, with a
 * street running from its entrance.
 *
 * The game joins every village house to a street at the house's `building_entrance` jigsaw, face
 * to face with the street's own; under the street's jigsaw and on into the street lies its path
 * (dirt path, or smooth sandstone in the desert), in all 182 such joins in the game's street
 * designs. So of the 3 × 3 columns just in front of the entrance, a real farm has 7 to 9 of path;
 * the game turns up to a fifth of a street's path back to grass, and 3 is still enough for all
 * but about one farm in a thousand. A farm on its own in a field has none.
 *
 * The street follows the ground while the farm is laid flat, so on a slope the path can be several
 * blocks above or below the entrance (6 in a savanna village tested); and where a street crosses
 * water the game builds it of planks. Tested on 19 farms in villages of all five kinds, each with
 * 6 to 9 of the 9, and on the player's farm that was taken for a village, with none.
 *
 * Even on a street a farm is never all a village is known by: it only confirms a house that fitted
 * too little to count by itself (see `Tracker`).
 */
object VillageFarmFit : TemplateFit(
    "village",
    listOf(
        "desert/houses/desert_farm_1",
        "desert/houses/desert_farm_2",
        "desert/houses/desert_large_farm_1",
        "plains/houses/plains_large_farm_1",
        "plains/houses/plains_small_farm_1",
        "savanna/houses/savanna_large_farm_1",
        "savanna/houses/savanna_large_farm_2",
        "savanna/houses/savanna_small_farm",
        "snowy/houses/snowy_farm_1",
        "snowy/houses/snowy_farm_2",
        "taiga/houses/taiga_large_farm_1",
        "taiga/houses/taiga_large_farm_2",
        "taiga/houses/taiga_small_farm_1",
    ),
    anyWood = false,
)

/**
 * The street a village house opens onto, which the game always lays at its `building_entrance`
 * jigsaw ([VillageFarmFit] explains the check and what it was tested on). Asked of every farm, and
 * of a house that fits too little to count by itself.
 */
object VillageStreets {

    /** Why [match] is not standing on a street as the game lays one (see [VillageFarmFit]), or null when it is. */
    fun refuse(match: TemplateFit.Match, level: BlockSource): String? {
        val cursor = BlockPos.MutableBlockPos()
        for ((entrance, facing) in match.entrances()) {
            val side = facing.clockWise
            var street = 0
            for (out in 1..3) for (across in -1..1) {
                val x = entrance.x + facing.stepX * out + side.stepX * across
                val z = entrance.z + facing.stepZ * out + side.stepZ * across
                if (!level.hasChunk(x shr 4, z shr 4)) return "street not loaded"
                if ((entrance.y - REACH_Y..entrance.y + REACH_Y).any { y -> isStreet(level, cursor.set(x, y, z)) }) street++
            }
            if (street >= STREET_NEEDED) return null
        }
        return "no street at its entrance"
    }

    /** A block of street: path, or the planks the game lays in its place over water. */
    fun isStreet(level: BlockSource, pos: BlockPos): Boolean {
        val block = level.getBlockState(pos).block
        if (block in STREET) return true
        if (block !in BRIDGE) return false
        return (listOf(pos.below()) + Direction.Plane.HORIZONTAL.map { pos.relative(it) })
            .any { level.getBlockState(it).block == Blocks.WATER }
    }

    private val STREET = setOf(Blocks.DIRT_PATH, Blocks.SMOOTH_SANDSTONE)
    private val BRIDGE = setOf(Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.ACACIA_PLANKS)
    private const val STREET_NEEDED = 3

    /** How far above or below the entrance its street may lie. */
    private const val REACH_Y = 8
}

/**
 * A ruined portal's 13 designs (ten small, three giant), placed turned and mirrored. The game ages
 * them as it builds: obsidian turns to crying obsidian, stone bricks go mossy or cracked or become
 * stairs and slabs, and some netherrack becomes magma (`RuinedPortalPiece.makeSettings`), so those
 * count as what they replace.
 * It takes out three gold blocks in ten, and turns lava to magma or netherrack, so those are loose.
 */
object RuinedPortalFit : TemplateFit(
    "ruined_portal",
    (1..10).map { "portal_$it" } + (1..3).map { "giant_portal_$it" },
    anyWood = false,
    agreement = 0.3,
    samples = 200,
    aliases = mapOf(
        "crying_obsidian" to "obsidian",
        "mossy_stone_bricks" to "stone_bricks", "cracked_stone_bricks" to "stone_bricks",
        "stone_brick_stairs" to "stone_bricks", "mossy_stone_brick_stairs" to "stone_bricks",
        "stone_brick_slab" to "stone_bricks", "mossy_stone_brick_slab" to "stone_bricks",
        "stone_brick_wall" to "stone_bricks", "mossy_stone_brick_wall" to "stone_bricks",
        "magma_block" to "netherrack",
    ),
    loose = setOf("gold_block", "lava"),
    minVotes = 20,
    mirrored = true,
    closest = true,
) {
    /**
     * Whether a group is worth matching at all: obsidian alone is where lava met water, and every
     * design has netherrack.
     */
    fun worthTrying(detection: Detection): Boolean =
        (detection.kinds[Blocks.NETHERRACK] ?: 0) >= MIN_NETHERRACK

    private const val MIN_NETHERRACK = 10

    /**
     * Why a fit is not a ruined portal after all, or null when it is. Netherrack is what a player's
     * nether build is made of, and those matched on it alone, so some of the design's obsidian must
     * be there too, or else the magma the game turns its netherrack into, or else the netherrack it
     * splashes round it ([splashed]): players mine the obsidian out of the ones they find, and two
     * in Bobby's cache of SiteB had none left, nor any magma. The game never lights one.
     *
     * Obsidian and magma count only where the design has them (magma in its netherrack's place):
     * deep underground lava meets water, and the magma in a cave there let a player's netherrack
     * build 20 blocks down pass for one with none of its obsidian.
     */
    fun refusal(level: BlockSource): (Match) -> String? = { match ->
        var obsidian = 0
        var magma = 0
        var lit = false
        val box = match.box
        val cursor = BlockPos.MutableBlockPos()
        for (x in box.minX..box.maxX) for (y in box.minY..box.maxY) for (z in box.minZ..box.maxZ) {
            if (level.hasChunk(x shr 4, z shr 4) && level.getBlockState(cursor.set(x, y, z)).block == Blocks.NETHER_PORTAL) lit = true
        }
        val ox = BlockPos.getX(match.origin)
        val oy = BlockPos.getY(match.origin)
        val oz = BlockPos.getZ(match.origin)
        for ((pos, family) in match.template.blocks) {
            if (family != "obsidian" && family != "netherrack") continue
            val turned = pos.rotate(match.rotation)
            cursor.set(ox + turned.x, oy + turned.y, oz + turned.z)
            if (!level.hasChunk(cursor.x shr 4, cursor.z shr 4)) continue
            when (level.getBlockState(cursor).block) {
                Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN -> if (family == "obsidian") obsidian++
                Blocks.MAGMA_BLOCK -> if (family == "netherrack") magma++
                else -> {}
            }
        }
        val frame = match.template.byFamily["obsidian"]?.size ?: 0
        when {
            lit -> "lit, so a player's"
            obsidian * 4 >= frame || magma >= MIN_MAGMA -> null
            !splashed(box, level) -> "$obsidian obsidian of $frame, $magma magma, and no netherrack splashed round it"
            stonework(match, level).let { (seen, of) -> seen * STONEWORK_SHARE >= of } -> null
            splashedRightRound(box, level) < RIGHT_ROUND -> "nothing of it but netherrack, and the ground right round it not all splashed"
            else -> null
        }
    }

    /**
     * How much of [match]'s stonework (all but its netherrack, obsidian, gold and lava: stone bricks,
     * stairs, slabs, iron bars) is where the design has it, of how much could be seen. Players take
     * the obsidian and gold; of the 502 ruined portals in Bobby's cache of SiteB, those that kept
     * under a quarter of their obsidian and under two magma kept a tenth or more of this, or else had
     * most of the ground right round them splashed ([splashedRightRound]).
     */
    fun stonework(match: Match, level: BlockSource): Pair<Int, Int> {
        val ox = BlockPos.getX(match.origin)
        val oy = BlockPos.getY(match.origin)
        val oz = BlockPos.getZ(match.origin)
        val cursor = BlockPos.MutableBlockPos()
        var seen = 0
        var of = 0
        for ((pos, family) in match.template.blocks) {
            if (family in NOT_STONEWORK) continue
            val turned = pos.rotate(match.rotation)
            cursor.set(ox + turned.x, oy + turned.y, oz + turned.z)
            if (!level.hasChunk(cursor.x shr 4, cursor.z shr 4)) continue
            of++
            if (family(level.getBlockState(cursor).block) == family) seen++
        }
        return seen to of
    }

    private val NOT_STONEWORK = setOf("netherrack", "obsidian", "gold_block", "lava")
    private const val STONEWORK_SHARE = 10

    /**
     * How much of the ground just round [box] (the columns within [RIGHT_ROUND_REACH] of it) is
     * netherrack or magma at its base, of the columns with ground there: the game splashes it all
     * (`RuinedPortalPiece.spreadNetherrack`), though some is gone again in most.
     * A player's cube of netherrack round a dungeon in Bobby's cache of SiteB, 20 blocks down, fitted
     * a small design on its netherrack alone, and had the stone just round it as it was.
     */
    fun splashedRightRound(box: Box, level: BlockSource): Double {
        var ground = 0
        var hit = 0
        val cursor = BlockPos.MutableBlockPos()
        for (x in box.minX - RIGHT_ROUND_REACH..box.maxX + RIGHT_ROUND_REACH) for (z in box.minZ - RIGHT_ROUND_REACH..box.maxZ + RIGHT_ROUND_REACH) {
            if ((x in box.minX..box.maxX && z in box.minZ..box.maxZ) || !level.hasChunk(x shr 4, z shr 4)) continue
            var solid = false
            var splash = false
            for (y in box.minY - SPLASH_DEPTH..box.minY + SPLASH_DEPTH) {
                val state = level.getBlockState(cursor.set(x, y, z))
                if (state.block == Blocks.NETHERRACK || state.block == Blocks.MAGMA_BLOCK) splash = true
                if (!state.isAir && state.block != Blocks.WATER) solid = true
            }
            if (solid) ground++
            if (splash) hit++
        }
        return if (ground == 0) 1.0 else hit.toDouble() / ground
    }

    /**
     * How far round its box, and how much of the ground there must be splashed. Of the ruined portals
     * in Bobby's cache of SiteB that had nothing left but netherrack, the least splashed just round
     * them were 0.66 and 0.70; the player's cube round a dungeon, 0.40.
     */
    private const val RIGHT_ROUND_REACH = 2
    private const val RIGHT_ROUND = 0.5

    private const val MIN_MAGMA = 2

    /**
     * Whether netherrack lies round [box] as the game splashes it (`RuinedPortalPiece.spreadNetherrack`):
     * on the ground within 3 of its base, all round its middle and thinning out, and never 17 or
     * more blocks from it, counted along x and z. Round every ruined portal in Bobby's cache of SiteB
     * a third or more of the ground near it was netherrack and none of it that far out, where a
     * player's netherrack build or floor goes on.
     */
    fun splashed(box: Box, level: BlockSource): Boolean {
        val cx = (box.minX + box.maxX) shr 1
        val cz = (box.minZ + box.maxZ) shr 1
        var near = 0
        var nearHit = 0
        var far = 0
        var farHit = 0
        val cursor = BlockPos.MutableBlockPos()
        for (x in cx - SPLASH_FAR_TO..cx + SPLASH_FAR_TO) for (z in cz - SPLASH_FAR_TO..cz + SPLASH_FAR_TO) {
            val d = Math.abs(x - cx) + Math.abs(z - cz)
            val inNear = d <= SPLASH_NEAR && !(x in box.minX..box.maxX && z in box.minZ..box.maxZ)
            val inFar = d in SPLASH_FAR_FROM..SPLASH_FAR_TO
            if (!inNear && !inFar || !level.hasChunk(x shr 4, z shr 4)) continue
            val hit = (box.minY - SPLASH_DEPTH..box.minY + SPLASH_DEPTH).any { y ->
                level.getBlockState(cursor.set(x, y, z)).block.let { it == Blocks.NETHERRACK || it == Blocks.MAGMA_BLOCK }
            }
            if (inNear) { near++; if (hit) nearHit++ } else { far++; if (hit) farHit++ }
        }
        return near >= MIN_SPLASH_COLUMNS && nearHit * 3 >= near && farHit * 50 <= far
    }

    /** How far from its middle the ground is all splashed, near enough, and from where never. */
    private const val SPLASH_NEAR = 8
    private const val SPLASH_FAR_FROM = 18
    private const val SPLASH_FAR_TO = 24
    private const val SPLASH_DEPTH = 3
    private const val MIN_SPLASH_COLUMNS = 8
}

/**
 * An igloo's dome (`igloo/top`): snow blocks round a bed, a furnace, a crafting table, carpet and a
 * redstone torch, with a trapdoor over the ladder when it has a basement. The game lays it from its
 * chunk's corner, turned about one block of the design (3, 5, 5), so each way round it lands on one
 * spot of its chunk (the game's `IglooPieces`).
 */
object IglooFit : TemplateFit(
    "igloo",
    listOf("top"),
    anyWood = false,
    // Only the furnishings vote: snow blocks are the ground of every snowy slope.
    votingKinds = setOf(
        Blocks.CARPET.white(), Blocks.CARPET.lightGray(), Blocks.REDSTONE_TORCH, Blocks.FURNACE, Blocks.CRAFTING_TABLE,
        Blocks.OAK_TRAPDOOR, Blocks.BED.red(),
    ),
    // Its furnishings are only 18 blocks, and players take the carpet; the dome is checked anyway.
    minBlocks = 8,
    open = { state, _ -> state.isAir },
) {
    /**
     * The same design found by its dome's snow alone, for an igloo emptied of its furnishings: the
     * snow blocks with air under them ([Spec.overAir]) vote, and the dome must be hollow and on the
     * igloo's spot of its chunk ([refusal]) like any other.
     */
    val dome = TemplateFit(
        "igloo", listOf("top"), anyWood = false, votingKinds = setOf(Blocks.SNOW_BLOCK),
        // Snow overhanging a slope beside it votes too, and the dome is checked block for block anyway.
        agreement = 0.6, minBlocks = 10, open = { state, _ -> state.isAir },
    )

    fun refusal(match: Match): String? {
        val (x, z) = OFFSETS.getValue(match.rotation)
        return if (match.inChunk == Math.floorMod(x, 16) to Math.floorMod(z, 16)) null
        else "an igloo at ${match.inChunk.first},${match.inChunk.second} in its chunk, not ${Math.floorMod(x, 16)},${Math.floorMod(z, 16)}"
    }

    /** The chunk an igloo that fits starts in: the one whose corner it was laid from. */
    fun startChunk(match: Match): Pair<Int, Int> {
        val (x, z) = OFFSETS.getValue(match.rotation)
        return Math.floorDiv(BlockPos.getX(match.origin) - x, 16) to Math.floorDiv(BlockPos.getZ(match.origin) - z, 16)
    }

    /** How far the design's corner lies from its chunk's corner, each way round: its pivot (3, 5) less itself turned. */
    private val OFFSETS = mapOf(
        net.minecraft.world.level.block.Rotation.NONE to (0 to 0),
        net.minecraft.world.level.block.Rotation.CLOCKWISE_90 to (8 to 2),
        net.minecraft.world.level.block.Rotation.CLOCKWISE_180 to (6 to 10),
        net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90 to (-2 to 8),
    )
}

/**
 * An ocean ruin's 48 designs: stone brick ones (plain, cracked or mossy) in cold seas, cut sandstone
 * ones in warm. The game leaves out a block in five of small ones and one in ten of big ones as it
 * places them, and makes some of their gravel and sand suspicious, which count as what they replace.
 * Gravel, sand and sandstone are the sea floor anyway, so the rest of a design must match by itself as well.
 * A big ruin has small ones round it, which [Specs] keeps in its box.
 */
object OceanRuinFit : TemplateFit(
    "underwater_ruin",
    listOf("brick", "cracked", "mossy", "warm").flatMap { kind -> (1..8).map { "${kind}_$it" } } +
        listOf("big_brick", "big_cracked", "big_mossy").flatMap { kind -> listOf(1, 2, 3, 8).map { "${kind}_$it" } } +
        (4..7).map { "big_warm_$it" },
    anyWood = false,
    agreement = 0.3,
    samples = 120,
    // The game mixes plain, cracked and mossy stone bricks through a cold ruin whichever design it
    // is (a cracked one in Bobby's cache of SiteB was 27 mossy, 17 cracked and 4 plain where its
    // design has cracked), so they are one block here; each such ruin matched under half otherwise.
    aliases = mapOf(
        "suspicious_gravel" to "gravel", "suspicious_sand" to "sand",
        "cracked_stone_bricks" to "stone_bricks", "mossy_stone_bricks" to "stone_bricks",
        "mossy_stone_brick_stairs" to "stone_brick_stairs", "mossy_stone_brick_slab" to "stone_brick_slab",
    ),
    // The sandstone under a warm sea's sand is the floor too: in the open space of two big warm ruins
    // in Bobby's cache of SiteB it counted as their own blocks standing there, and both were refused.
    loose = setOf("gravel", "sand", "sandstone"),
    minVotes = 15,
    // Only its stonework votes: the sea floor's gravel and sand are everywhere.
    votingKinds = setOf(
        Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS,
        Blocks.MOSSY_COBBLESTONE, Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE, Blocks.POLISHED_GRANITE,
    ),
    closest = true,
    // The smallest design (warm_7) has only 9 blocks that vote, 6 cut sandstone and 3 polished
    // granite; 27 such ruins in Bobby's cache of SiteB kept 5 to 8 of them, and were never tried.
    minBlocks = 5,
    // It stands on the sea floor, with the sea over it, and the sky where it reaches the surface.
    open = { state, y -> state.fluidState.type.isSame(Fluids.WATER) || y >= SEA_LEVEL && state.isAir }, openTopOnly = true,
    sea = { state -> state.fluidState.type.isSame(Fluids.WATER) },
    outOfSea = { origin, level -> OceanRuinFit.startsHere(origin, level) },
) {
    /**
     * Whether a ruin laid with its corner at [origin] is where the game starts one: on its chunk's
     * corner, the middle of that chunk an ocean. The game lays a ruin there on the floor however high
     * that is, so one can stand on land, out of the sea; three in Bobby's cache of SiteB stood at 62
     * to 71, each so. One that only happens to fit on land is almost never on a chunk's corner as
     * well, nor in an ocean.
     */
    fun startsHere(origin: Long, level: BlockSource): Boolean {
        val x = BlockPos.getX(origin)
        val z = BlockPos.getZ(origin)
        if (Math.floorMod(x, 16) != 0 || Math.floorMod(z, 16) != 0) return false
        return level.biome(BlockPos(x + 8, BlockPos.getY(origin), z + 8)) in Biomes.OCEAN
    }
}

/** The sea's surface: the highest water of every ocean is a block under it. */
private const val SEA_LEVEL = 63

/**
 * An abandoned camp's tent, one of 180 designs over 18 biomes: white wool stairs over straw beds,
 * with fences and a dirt path. The tent is where the camp starts, laid from its chunk's corner as
 * trail ruins' tower is; its campsite and trees join it from there.
 */
object CampFit : TemplateFit(
    "abandoned_camp/tent",
    CAMP_BIOMES.flatMap { biome -> poolLocations("abandoned_camp/tent/$biome", "abandoned_camp/tent/") },
    anyWood = false,
    votingKinds = setOf(Blocks.WOOL_STAIRS.white(), Blocks.STRAW_BED),
) {
    fun refusal(match: Match): String? = if (match.inChunk == (0 to 0)) null else "a tent off its chunk's corner"
}

/** The biomes the game has a camp for, each with its own tents (`worldgen/structure/abandoned_camp_<biome>`). */
private val CAMP_BIOMES = listOf(
    "bamboo_jungle", "birch_forest", "cherry_grove", "dappled_forest", "flower_forest", "forest", "meadow",
    "old_growth_birch_forest", "old_growth_pine_taiga", "old_growth_spruce_taiga", "pale_garden", "savanna",
    "snowy_taiga", "sparse_jungle", "swamp", "taiga", "windswept_forest", "wooded_badlands",
)

/**
 * The designs a template pool of the game's draws from (`worldgen/template_pool/<pool>.json`), as
 * paths under [under]: read from the game's own files, so a design added in an update is matched too.
 */
private fun poolLocations(pool: String, under: String): List<String> = try {
    TemplateFit::class.java.getResourceAsStream("/data/minecraft/worldgen/template_pool/$pool.json")?.use { stream ->
        com.google.gson.JsonParser.parseReader(stream.reader()).asJsonObject.getAsJsonArray("elements").mapNotNull { entry ->
            entry.asJsonObject.getAsJsonObject("element")?.get("location")?.asString
                ?.removePrefix("minecraft:")?.takeIf { it.startsWith(under) }?.removePrefix(under)
        }
    } ?: emptyList<String>().also { Log.warn("Template pool {} is not in the game jar", pool) }
} catch (e: Exception) {
    Log.error("Could not read template pool $pool", e)
    emptyList()
}
