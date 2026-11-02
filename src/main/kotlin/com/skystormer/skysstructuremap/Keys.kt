package com.skystormer.skysstructuremap

import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.concurrent.ConcurrentHashMap

/** Two whole numbers as one long, for chunks, regions and grid cells as map keys: [a] high, [b] low. */
fun cellKey(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)

private val dimensionIds = ConcurrentHashMap<ResourceKey<Level>, String>()

/**
 * A dimension's id as text (`minecraft:overworld`), as everything here keys its dimensions. Kept
 * per dimension: asked about ten times a tick, and each asking otherwise built the text again.
 */
fun dimensionId(key: ResourceKey<Level>): String = dimensionIds.getOrPut(key) { key.identifier().toString() }

/** The id of the dimension [level] is ([dimensionId]). */
fun dimensionId(level: Level): String = dimensionId(level.dimension())
