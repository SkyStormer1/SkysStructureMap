package com.skystormer.skysstructuremap

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.SpawnerBlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * Where the blocks around a group are read from once it has been gathered, to lay the game's
 * designs over it: the world you are in, or chunks Bobby saved to disk ([BobbyCache]).
 */
interface BlockSource {

    /** Whether the chunk is there to be read at all; a block in a missing one says nothing. */
    fun hasChunk(chunkX: Int, chunkZ: Int): Boolean

    fun getBlockState(pos: BlockPos): BlockState

    /** The mob a spawner at [pos] spawns (`minecraft:zombie`), when that is known. */
    fun spawnerMob(pos: BlockPos): String? = null

    companion object {
        fun of(level: Level): BlockSource = object : BlockSource {
            override fun hasChunk(chunkX: Int, chunkZ: Int) = level.hasChunk(chunkX, chunkZ)
            override fun getBlockState(pos: BlockPos): BlockState = level.getBlockState(pos)
            override fun spawnerMob(pos: BlockPos): String? =
                (level.getBlockEntity(pos) as? SpawnerBlockEntity)?.getUpdateTag(level.registryAccess())?.let(::spawnedBy)
        }

        /** The mob in a spawner's saved data (its `SpawnData`), as the server sends it and Bobby saves it. */
        fun spawnedBy(tag: CompoundTag): String? =
            tag.getCompound("SpawnData").flatMap { it.getCompound("entity") }.flatMap { it.getString("id") }.orElse(null)
    }
}
