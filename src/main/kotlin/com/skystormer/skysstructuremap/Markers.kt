package com.skystormer.skysstructuremap

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import xaero.lib.client.graphics.XaeroBufferProvider
import xaero.lib.client.gui.widget.Tooltip
import xaero.map.WorldMapSession
import xaero.map.element.MapElementGraphics
import xaero.map.element.render.ElementReader
import xaero.map.element.render.ElementRenderInfo
import xaero.map.element.render.ElementRenderLocation
import xaero.map.element.render.ElementRenderProvider
import xaero.map.element.render.ElementRenderer
import xaero.map.graphics.CustomRenderTypes
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider
import xaero.map.gui.IRightClickableElement
import xaero.map.gui.dropdown.rightclick.RightClickOption
import java.text.DateFormat
import java.util.Date

/** Something on the map: a discovered [structure], or a [detection] recognised nearby but not visited. */
class Marker(val structure: Structure?, val detection: Detection?) {
    val type: StructureType = structure?.type ?: detection!!.type
    val dimension: String = structure?.dimension ?: detection!!.dimension
    val box: Box = structure?.box ?: detection!!.box!!
    val discovered: Boolean get() = structure != null
    /** Boxes inside it that matter on their own (a fortress's crossroads). */
    val pieces: List<Piece> get() = structure?.pieces ?: detection?.pieces ?: emptyList()
    /** This one's box is drawn even while outlines are off for everything. */
    val outlined: Boolean get() = structure?.outlined ?: (detection!!.id in Markers.outlinedNearby)
    /** Marked as completed; only a discovered one can be. */
    val completed: Boolean get() = structure?.completed ?: false
    val name: String get() = type.displayName
    val y: Int get() = structure?.waypointY ?: waypointY(type, box)
}

/**
 * Every structure's icon on Xaero's world map, in the middle of its box, through Xaero's own
 * element system (the one its waypoints use) so it can be hovered for details and right-clicked.
 */
object Markers {

    /** Nearby, not-yet-visited structures whose outline was turned on: this session only, like them. */
    val outlinedNearby = HashSet<Int>()

    /** What the map shows for [dimension]: the kinds ticked in the legend, discovered ones and, if turned on, nearby ones. */
    fun visibleIn(dimension: String): List<Marker> {
        if (!Config.show) return emptyList()
        val saved = StructureStore.inDimension(dimension)
            .filter { Config.isShown(it.type) && !(Config.hideCompleted && it.completed) }.map { Marker(it, null) }
        if (!Config.showUndiscovered) return saved
        return saved + Tracker.undiscovered(dimension).filter { Config.isShown(it.type) }.map { Marker(null, it) }
    }

    fun mapDimension(): String? =
        WorldMapSession.getCurrentSession()?.mapProcessor?.mapWorld?.currentDimension?.dimId?.identifier()?.toString()

    class Context {
        var markers: List<Marker> = emptyList()
        var next = 0
        /** Batches the icons for this frame; set in preRender, drawn in postRender. */
        var icons: MultiTextureRenderTypeRenderer? = null
    }

    class Provider : ElementRenderProvider<Marker, Context>() {
        override fun begin(location: ElementRenderLocation, context: Context) {
            val dimension = if (location == ElementRenderLocation.WORLD_MAP) mapDimension() else null
            context.markers = dimension?.let(::visibleIn) ?: emptyList()
            context.next = 0
        }

        override fun hasNext(location: ElementRenderLocation, context: Context): Boolean = context.next < context.markers.size

        override fun getNext(location: ElementRenderLocation, context: Context): Marker = context.markers[context.next++]

        override fun end(location: ElementRenderLocation, context: Context) {
            context.markers = emptyList()
        }
    }

    class Reader : ElementReader<Marker, Context, Renderer>() {
        override fun isHidden(marker: Marker, context: Context): Boolean = false
        override fun getRenderX(marker: Marker, context: Context, partialTicks: Float): Double = (marker.box.minX + marker.box.maxX + 1) / 2.0
        override fun getRenderZ(marker: Marker, context: Context, partialTicks: Float): Double = (marker.box.minZ + marker.box.maxZ + 1) / 2.0
        override fun getRenderY(marker: Marker, context: Context, partialTicks: Float): Double = marker.y.toDouble()
        override fun hasYCoordinate(): Boolean = false
        // Xaero only hovers and right-clicks elements that say they can be; the default is no.
        override fun isInteractable(location: ElementRenderLocation, marker: Marker): Boolean = location == ElementRenderLocation.WORLD_MAP
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
        override fun isRightClickValid(marker: Marker): Boolean = true

        override fun getTooltip(marker: Marker, context: Context, overMenu: Boolean): Tooltip = Tooltip(describe(marker))

        override fun getRightClickOptions(marker: Marker, target: IRightClickableElement): ArrayList<RightClickOption> {
            val options = ArrayList<RightClickOption>()
            options.add(object : RightClickOption(marker.name, 0, target) {
                override fun onAction(screen: Screen) {}
            })
            Menus.addMarkerOptions(options, target, marker)
            return options
        }
    }

    class Renderer(context: Context, provider: Provider, reader: Reader) :
        ElementRenderer<Marker, Context, Renderer>(context, provider, reader) {

        // Xaero draws element layers in ascending order, later ones on top; its claims are 150 and
        // waypoints 200. Below those, so an icon never covers a waypoint.
        override fun getOrder(): Int = ORDER

        // Xaero renders elements in two passes, shadows first ("pre"); both go through here.
        override fun shouldRender(location: ElementRenderLocation, pre: Boolean): Boolean = location == ElementRenderLocation.WORLD_MAP

        /**
         * Xaero divides an element's position by the dimension scale, because its own waypoints are
         * kept in the coordinates of the dimension you are standing in and have to be converted to
         * the one the map is showing. These are read from the dimension being shown already, so
         * there is nothing to convert: letting Xaero convert them anyway threw every icon eight
         * times too far out (or in) whenever the map was switched between the nether and overworld.
         */
        override fun shouldBeDimScaled(): Boolean = false

        override fun preRender(info: ElementRenderInfo, buffers: XaeroBufferProvider, renderers: MultiTextureRenderTypeRendererProvider, pre: Boolean) {
            context.icons = renderers.getRenderer(CustomRenderTypes.GUI_NEAREST)
        }

        override fun postRender(info: ElementRenderInfo, buffers: XaeroBufferProvider, renderers: MultiTextureRenderTypeRendererProvider, pre: Boolean) {
            context.icons?.let(renderers::draw)
            context.icons = null
            buffers.endBatch()
        }

        override fun renderElementShadow(
            marker: Marker, hovered: Boolean, scale: Float, partialX: Double, partialY: Double,
            info: ElementRenderInfo, graphics: MapElementGraphics, buffers: XaeroBufferProvider, renderers: MultiTextureRenderTypeRendererProvider,
        ) {
        }

        override fun renderElement(
            marker: Marker, hovered: Boolean, depth: Double, scale: Float, partialX: Double, partialY: Double,
            info: ElementRenderInfo, graphics: MapElementGraphics, buffers: XaeroBufferProvider, renderers: MultiTextureRenderTypeRendererProvider,
        ): Boolean {
            val pose = graphics.pose()
            pose.pushPose()
            pose.translate(partialX, partialY, 0.0)
            pose.scale(scale, scale, 1f)
            // A soft light square behind the icon while it is hovered.
            if (hovered) graphics.fill(-half(), -half(), half(), half(), 0x70FFFFFF)
            val renderer = context.icons
            val view = Icons.view(marker.type)
            if (renderer != null && view != null) {
                Icons.quad(renderer.begin(view), pose.last().pose(), size(), if (marker.discovered) 1f else 0.5f)
                if (marker.completed) Icons.tickView()?.let { Icons.tick(renderer.begin(it), pose.last().pose(), size()) }
            } else {
                // The picture could not be loaded: the kind's colour, so the marker is still there.
                graphics.fill(-half() + 2, -half() + 2, half() - 2, half() - 2, 0xFF000000.toInt())
                graphics.fill(-half() + 3, -half() + 3, half() - 3, half() - 3, 0xFF000000.toInt() or (marker.type.colour and 0xFFFFFF))
            }
            pose.popPose()
            return true
        }
    }

    /** Registers with Xaero's world map, once it has started. */
    fun register(): Boolean {
        val handler = xaero.map.WorldMap.mapElementRenderHandler ?: return false
        handler.add(Renderer(Context(), Provider(), Reader()))
        return true
    }

    fun describe(marker: Marker): Component {
        val box = marker.box
        val details = buildString {
            append("\n${box.centreX} ${marker.y} ${box.centreZ}")
            append("\n${box.describeSize()}")
            val structure = marker.structure
            if (structure != null) {
                append("\nDiscovered ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(structure.discovered))}")
            } else {
                append("\nSeen nearby, not visited yet")
            }
            append("\nRight-click for options")
        }
        // Xaero's tooltips split into words at spaces and start a new line only at a word that is
        // exactly "\n", so each line break needs a space either side.
        return Component.literal(marker.name).withStyle { it.withColor(marker.type.colour and 0xFFFFFF) }
            .append(Component.literal(details.replace("\n", " \n ")).withStyle { it.withColor(0xDDDDDD) })
    }

    private const val ORDER = -90
    /**
     * How big an icon is drawn, in Xaero's element units (which its own marker size setting also
     * scales): 26 (asked for as 2px bigger than 24) times the icon size setting.
     */
    private fun size(): Float = ICON_SIZE * Config.iconScale
    private fun half(): Int = (size() / 2).toInt()
    private const val ICON_SIZE = 26f
}
