package com.skystormer.skysstructuremap

/**
 * The kinds of structure this mod can recognise, in the order the legend lists them (grouped by
 * dimension), with how each is shown. How each is found is in [Specs]. [dimension] is where they
 * generate, so chunks elsewhere are never searched for them.
 */
enum class StructureType(
    val id: String,
    val displayName: String,
    val plural: String,
    val colour: Int,
    val dimension: String,
) {
    VILLAGE("village", "Village", "Villages", 0xFFB0503A.toInt(), OVERWORLD),
    OUTPOST("outpost", "Pillager Outpost", "Pillager Outposts", 0xFF8A7A5A.toInt(), OVERWORLD),
    MANSION("mansion", "Woodland Mansion", "Woodland Mansions", 0xFF6A4A2A.toInt(), OVERWORLD),
    STRONGHOLD("stronghold", "Stronghold", "Strongholds", 0xFF2E8B57.toInt(), OVERWORLD),
    WITCH_HUT("witch_hut", "Witch Hut", "Witch Huts", 0xFF7A3AA0.toInt(), OVERWORLD),
    JUNGLE_TEMPLE("jungle_temple", "Jungle Temple", "Jungle Temples", 0xFF5E7A4A.toInt(), OVERWORLD),
    DESERT_TEMPLE("desert_temple", "Desert Temple", "Desert Temples", 0xFFE0C080.toInt(), OVERWORLD),
    TRAIL_RUINS("trail_ruins", "Trail Ruins", "Trail Ruins", 0xFFA0603A.toInt(), OVERWORLD),
    ANCIENT_CITY("ancient_city", "Ancient City", "Ancient Cities", 0xFF1FA0A8.toInt(), OVERWORLD),
    MONUMENT("monument", "Ocean Monument", "Ocean Monuments", 0xFF3FD0C0.toInt(), OVERWORLD),
    SHIPWRECK("shipwreck", "Shipwreck", "Shipwrecks", 0xFFC8904A.toInt(), OVERWORLD),
    FORTRESS("fortress", "Nether Fortress", "Nether Fortresses", 0xFFD04040.toInt(), NETHER),
    BASTION("bastion", "Bastion Remnant", "Bastion Remnants", 0xFFE0B020.toInt(), NETHER),
    END_CITY("end_city", "End City", "End Cities", 0xFFB070C0.toInt(), END),
    END_GATEWAY("end_gateway", "End Gateway", "End Gateways", 0xFF40D0E0.toInt(), END);

    /** Two letters for a waypoint made from one. */
    val initials: String
        get() = displayName.split(' ').let { words ->
            if (words.size >= 2) "${words[0][0]}${words[1][0]}" else words[0].take(2)
        }.uppercase()

    companion object {
        fun byId(id: String): StructureType? = entries.firstOrNull { it.id == id }
    }
}

const val OVERWORLD = "minecraft:overworld"
const val NETHER = "minecraft:the_nether"
const val END = "minecraft:the_end"

/** A dimension id as a person would write it. */
fun dimensionName(dimension: String?): String? = when (dimension) {
    null -> null
    OVERWORLD -> "Overworld"
    NETHER -> "Nether"
    END -> "End"
    else -> dimension.removePrefix("minecraft:")
}
