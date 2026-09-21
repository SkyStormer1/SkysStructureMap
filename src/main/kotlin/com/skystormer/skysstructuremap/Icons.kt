package com.skystormer.skysstructuremap

import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.BufferBuilder
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import org.joml.Matrix4f

/**
 * Each kind of structure's 16×16 picture, in `assets/skysstructuremap/textures/structure/`. They are
 * this mod's own, drawn from the pixel grids in `tools/icons.ps1`.
 */
object Icons {

    fun id(type: StructureType): Identifier =
        Identifier.fromNamespaceAndPath("skysstructuremap", "textures/structure/${type.id}.png")

    private var failed = false

    /** The loaded texture, or null (logged once) when it cannot be. */
    fun view(type: StructureType): GpuTextureView? = try {
        Minecraft.getInstance().textureManager.getTexture(id(type)).textureView
    } catch (e: Throwable) {
        if (!failed) {
            failed = true
            Log.error("Could not load the icon for ${type.id} (logged once)", e)
        }
        null
    }

    /** The whole picture as a square [size] units across, centred on the pose's origin. */
    fun quad(buffer: BufferBuilder, matrix: Matrix4f, size: Float, alpha: Float) {
        val half = size / 2f
        buffer.addVertex(matrix, -half, half, 0f).setColor(1f, 1f, 1f, alpha).setUv(0f, 1f)
        buffer.addVertex(matrix, half, half, 0f).setColor(1f, 1f, 1f, alpha).setUv(1f, 1f)
        buffer.addVertex(matrix, half, -half, 0f).setColor(1f, 1f, 1f, alpha).setUv(1f, 0f)
        buffer.addVertex(matrix, -half, -half, 0f).setColor(1f, 1f, 1f, alpha).setUv(0f, 0f)
    }
}
