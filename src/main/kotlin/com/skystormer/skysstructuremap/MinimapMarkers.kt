package com.skystormer.skysstructuremap

import net.minecraft.client.Minecraft
import xaero.common.HudMod
import xaero.common.graphics.CustomRenderTypes
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider
import xaero.hud.minimap.element.render.MinimapElementGraphics
import xaero.hud.minimap.element.render.MinimapElementReader
import xaero.hud.minimap.element.render.MinimapElementRenderInfo
import xaero.hud.minimap.element.render.MinimapElementRenderLocation
import xaero.hud.minimap.element.render.MinimapElementRenderProvider
import xaero.hud.minimap.element.render.MinimapElementRenderer
import xaero.lib.client.graphics.XaeroBufferProvider

/**
 * The same structure icons on Xaero's Minimap, through its own element system (as Sky's Map
 * Exposer draws its markers), smaller than on the world map. Only those inside the minimap are
 * drawn: Xaero would otherwise pin every one further away to its edge, as it does waypoints.
 *
 * Only touched when Xaero's Minimap is installed.
 */
object MinimapMarkers {

    class Context {
        var markers: List<Marker> = emptyList()
        var next = 0
        var icons: MultiTextureRenderTypeRenderer? = null
    }

    /**
     * The dimension the minimap is showing: the world map's current one, which is what the
     * minimap's terrain comes from, else the one you are in.
     */
    private fun dimension(): String? =
        Markers.mapDimension() ?: Minecraft.getInstance().level?.dimension()?.identifier()?.toString()

    class Provider : MinimapElementRenderProvider<Marker, Context>() {
        override fun begin(location: MinimapElementRenderLocation, context: Context) {
            context.markers = dimension()?.let(Markers::visibleIn) ?: emptyList()
            context.next = 0
        }

        override fun hasNext(location: MinimapElementRenderLocation, context: Context): Boolean = context.next < context.markers.size

        override fun getNext(location: MinimapElementRenderLocation, context: Context): Marker = context.markers[context.next++]

        override fun end(location: MinimapElementRenderLocation, context: Context) {
            context.markers = emptyList()
        }
    }

    class Reader : MinimapElementReader<Marker, Context>() {
        override fun isHidden(marker: Marker, context: Context): Boolean = false
        override fun getRenderX(marker: Marker, context: Context, partialTicks: Float): Double = (marker.box.minX + marker.box.maxX + 1) / 2.0
        override fun getRenderY(marker: Marker, context: Context, partialTicks: Float): Double = marker.y.toDouble()
        override fun getRenderZ(marker: Marker, context: Context, partialTicks: Float): Double = (marker.box.minZ + marker.box.maxZ + 1) / 2.0
        override fun getInteractionBoxLeft(marker: Marker, context: Context, partialTicks: Float): Int = -half()
        override fun getInteractionBoxRight(marker: Marker, context: Context, partialTicks: Float): Int = half()
        override fun getInteractionBoxTop(marker: Marker, context: Context, partialTicks: Float): Int = -half()
        override fun getInteractionBoxBottom(marker: Marker, context: Context, partialTicks: Float): Int = half()
        override fun getRenderBoxLeft(marker: Marker, context: Context, partialTicks: Float): Int = -half()
        override fun getRenderBoxRight(marker: Marker, context: Context, partialTicks: Float): Int = half()
        override fun getRenderBoxTop(marker: Marker, context: Context, partialTicks: Float): Int = -half()
        override fun getRenderBoxBottom(marker: Marker, context: Context, partialTicks: Float): Int = half()
        override fun getLeftSideLength(marker: Marker, minecraft: Minecraft): Int = minecraft.font.width(marker.name) + 9
        override fun getMenuName(marker: Marker): String = marker.name
        override fun getFilterName(marker: Marker): String = marker.name
        override fun getMenuTextFillLeftPadding(marker: Marker): Int = 0
        override fun getRightClickTitleBackgroundColor(marker: Marker): Int = marker.type.colour
        override fun shouldScaleBoxWithOptionalScale(): Boolean = true
    }

    class Renderer(reader: Reader, provider: Provider, context: Context) :
        MinimapElementRenderer<Marker, Context>(reader, provider, context) {

        override fun shouldRender(location: MinimapElementRenderLocation): Boolean =
            location == MinimapElementRenderLocation.OVER_MINIMAP || location == MinimapElementRenderLocation.IN_MINIMAP

        override fun preRender(info: MinimapElementRenderInfo, buffers: XaeroBufferProvider, renderers: MultiTextureRenderTypeRendererProvider) {
            context.icons = renderers.getRenderer(CustomRenderTypes.GUI_NEAREST)
        }

        override fun postRender(info: MinimapElementRenderInfo, buffers: XaeroBufferProvider, renderers: MultiTextureRenderTypeRendererProvider) {
            context.icons?.let(renderers::draw)
            context.icons = null
            buffers.endBatch()
        }

        override fun renderElement(
            marker: Marker, highlighted: Boolean, outOfBounds: Boolean, depth: Double, scale: Float,
            partialX: Double, partialY: Double, info: MinimapElementRenderInfo, graphics: MinimapElementGraphics, buffers: XaeroBufferProvider,
        ): Boolean {
            // Past the minimap's edge Xaero would pin it there; structures only show when in view.
            if (outOfBounds) return false
            val pose = graphics.pose()
            pose.pushPose()
            pose.translate(partialX, partialY, 0.0)
            pose.scale(scale, scale, 1f)
            val renderer = context.icons
            val view = Icons.view(marker.type)
            if (renderer != null && view != null) {
                Icons.quad(renderer.begin(view), pose.last().pose(), size(), if (marker.discovered) 1f else 0.5f)
            } else {
                graphics.fill(-half() + 1, -half() + 1, half() - 1, half() - 1, 0xFF000000.toInt() or (marker.type.colour and 0xFFFFFF))
            }
            pose.popPose()
            return true
        }
    }

    /** Registers with Xaero's Minimap, once it has started. */
    fun register(): Boolean {
        val handler = HudMod.INSTANCE?.minimap?.overMapRendererHandler ?: return false
        handler.add(Renderer(Reader(), Provider(), Context()))
        return true
    }

    /** The icon's size on the minimap, in its element units: about half the world map's, times its own size setting. */
    private fun size(): Float = SIZE * Config.minimapIconScale
    private fun half(): Int = (size() / 2).toInt()
    private const val SIZE = 14f
}
