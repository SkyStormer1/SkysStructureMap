package com.skystormer.skysstructuremap

import net.minecraft.world.phys.AABB

/**
 * A box of whole blocks, both corners included, the way Minecraft's own structure bounding boxes
 * are: a block at `maxX` is inside it.
 */
data class Box(val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int) {

    val centreX: Int get() = Math.floorDiv(minX + maxX, 2)
    val centreY: Int get() = Math.floorDiv(minY + maxY, 2)
    val centreZ: Int get() = Math.floorDiv(minZ + maxZ, 2)
    val sizeX: Int get() = maxX - minX + 1
    val sizeY: Int get() = maxY - minY + 1
    val sizeZ: Int get() = maxZ - minZ + 1

    fun union(other: Box) = Box(
        minOf(minX, other.minX), minOf(minY, other.minY), minOf(minZ, other.minZ),
        maxOf(maxX, other.maxX), maxOf(maxY, other.maxY), maxOf(maxZ, other.maxZ),
    )

    fun including(x: Int, y: Int, z: Int) = Box(
        minOf(minX, x), minOf(minY, y), minOf(minZ, z),
        maxOf(maxX, x), maxOf(maxY, y), maxOf(maxZ, z),
    )

    fun grow(sideways: Int, down: Int = sideways, up: Int = sideways) =
        Box(minX - sideways, minY - down, minZ - sideways, maxX + sideways, maxY + up, maxZ + sideways)

    fun overlaps(other: Box): Boolean =
        minX <= other.maxX && maxX >= other.minX &&
            minY <= other.maxY && maxY >= other.minY &&
            minZ <= other.maxZ && maxZ >= other.minZ

    /** Whether an entity's hitbox reaches into any of these blocks. */
    fun touches(hitbox: AABB): Boolean =
        hitbox.minX < maxX + 1 && hitbox.maxX > minX &&
            hitbox.minY < maxY + 1 && hitbox.maxY > minY &&
            hitbox.minZ < maxZ + 1 && hitbox.maxZ > minZ

    /** How far ([x], [z]) is from this box sideways, ignoring height: 0 inside it. */
    fun horizontalDistance(x: Double, z: Double): Double {
        val dx = maxOf(minX - x, 0.0, x - (maxX + 1))
        val dz = maxOf(minZ - z, 0.0, z - (maxZ + 1))
        return Math.sqrt(dx * dx + dz * dz)
    }

    fun describeSize(): String = "$sizeX × $sizeZ blocks, $sizeY high"

    companion object {
        fun of(x: Int, y: Int, z: Int) = Box(x, y, z, x, y, z)
    }
}
