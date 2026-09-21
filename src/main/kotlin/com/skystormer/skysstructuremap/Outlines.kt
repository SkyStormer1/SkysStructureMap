package com.skystormer.skysstructuremap

import com.mojang.blaze3d.vertex.VertexConsumer
import org.joml.Matrix4f
import org.joml.Vector3f
import xaero.lib.XaeroLib
import xaero.map.MapProcessor
import xaero.map.graphics.CustomRenderTypes

/**
 * Each structure's box drawn on Xaero's world map as an outline of constant screen thickness, in
 * its kind's colour; fainter for one not visited yet.
 */
object Outlines {

    private var hookRan = false
    private var failed = false

    @JvmStatic
    fun drawWorldMap(mapProcessor: MapProcessor, matrix: Matrix4f, originX: Int, originZ: Int) {
        if (!hookRan) {
            hookRan = true
            Log.info("World map outline hook working")
        }
        try {
            val dimension = mapProcessor.mapWorld?.currentDimension?.dimId?.identifier()?.toString() ?: return
            val markers = Markers.visibleIn(dimension).filter { Config.outlines || it.outlined }
            if (markers.isEmpty()) return
            val blocksPerUnit = Matrix4f(matrix).invert().transformDirection(Vector3f(1f, 0f, 0f)).length().coerceAtLeast(1e-4f)
            val half = (LINE_WIDTH * blocksPerUnit / 2).toDouble()
            val buffer = XaeroLib.INSTANCE.client.bufferProvider.getBuffer(CustomRenderTypes.MAP_COLOR_OVERLAY)
            for (marker in markers) {
                val b = marker.box
                val alpha = if (marker.discovered) 1f else 0.45f
                rectangle(buffer, matrix, b.minX.toDouble(), b.minZ.toDouble(), b.maxX + 1.0, b.maxZ + 1.0, half, originX, originZ, marker.type.colour, alpha)
            }
        } catch (e: Throwable) {
            if (!failed) {
                failed = true
                Log.error("Could not draw structure outlines on the world map (logged once)", e)
            }
        }
    }

    private fun rectangle(
        buffer: VertexConsumer, matrix: Matrix4f, x1: Double, z1: Double, x2: Double, z2: Double, half: Double,
        originX: Int, originZ: Int, colour: Int, alpha: Float,
    ) {
        // Four bars, each widened by [half] either side of the edge; the long ones cover the corners.
        bar(buffer, matrix, x1 - half, z1 - half, x2 + half, z1 + half, originX, originZ, colour, alpha)
        bar(buffer, matrix, x1 - half, z2 - half, x2 + half, z2 + half, originX, originZ, colour, alpha)
        bar(buffer, matrix, x1 - half, z1 + half, x1 + half, z2 - half, originX, originZ, colour, alpha)
        bar(buffer, matrix, x2 - half, z1 + half, x2 + half, z2 - half, originX, originZ, colour, alpha)
    }

    private fun bar(
        buffer: VertexConsumer, matrix: Matrix4f, x1: Double, z1: Double, x2: Double, z2: Double,
        originX: Int, originZ: Int, colour: Int, alpha: Float,
    ) {
        val red = ((colour shr 16) and 0xFF) / 255f
        val green = ((colour shr 8) and 0xFF) / 255f
        val blue = (colour and 0xFF) / 255f
        val ax = (x1 - originX).toFloat()
        val az = (z1 - originZ).toFloat()
        val bx = (x2 - originX).toFloat()
        val bz = (z2 - originZ).toFloat()
        buffer.addVertex(matrix, ax, az, 0f).setColor(red, green, blue, alpha)
        buffer.addVertex(matrix, ax, bz, 0f).setColor(red, green, blue, alpha)
        buffer.addVertex(matrix, bx, bz, 0f).setColor(red, green, blue, alpha)
        buffer.addVertex(matrix, bx, az, 0f).setColor(red, green, blue, alpha)
    }

    /** In screen units. */
    private const val LINE_WIDTH = 2f
}
