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
    /** Boxes inside it that matter on their own (a fortress's pieces). */
    val pieces: List<Piece> get() = structure?.pieces ?: detection?.pieces ?: emptyList()
    /** This one's box is drawn even while outlines are off for everything. */
    val outlined: Boolean get() = structure?.outlined ?: (detection!!.id in Markers.outlinedNearby)
    /** Marked as completed; only a discovered one can be. */
    val completed: Boolean get() = structure?.completed ?: false
    val name: String get() = type.displayName
    val y: Int get() = structure?.waypointY ?: waypointY(type, box)

    private var spawnBoxes: List<SpawnBoxes.Drawn>? = null

    /**
     * [SpawnBoxes.boxesOf] this, worked out once for a saved structure (replaced whole when it
     * changes, and its marker with it) rather than every frame; a nearby one's pieces can still grow.
     */
    val boxes: List<SpawnBoxes.Drawn>
        get() = if (structure == null) SpawnBoxes.boxesOf(this) else spawnBoxes ?: SpawnBoxes.boxesOf(this).also { spawnBoxes = it }
}

/** A part of the map, in blocks, inclusive: what is on screen, with a margin. */
data class Area(val minX: Int, val minZ: Int, val maxX: Int, val maxZ: Int) {
    fun holds(b: Box): Boolean = b.maxX >= minX && b.minX <= maxX && b.maxZ >= minZ && b.minZ <= maxZ

    companion object {
        /**
         * The area [halfWidth] by [halfHeight] blocks either side of [x], [z], its edges moved out
         * to a whole [STEP] so a map moved a little is still the same area ([OnScreen]).
         */
        fun around(x: Double, z: Double, halfWidth: Double, halfHeight: Double): Area = Area(
            Math.floorDiv((x - halfWidth).toInt(), STEP) * STEP, Math.floorDiv((z - halfHeight).toInt(), STEP) * STEP,
            (Math.floorDiv((x + halfWidth).toInt(), STEP) + 1) * STEP, (Math.floorDiv((z + halfHeight).toInt(), STEP) + 1) * STEP,
        )

        private const val STEP = 64
    }
}

/**
 * The markers of a list inside an [Area], so Xaero (and the outlines) are handed the few on screen
 * rather than all 25,000 saved, each placed and checked by Xaero every frame. Remembered until
 * the list or the area changes, so a map held still costs nothing.
 */
class OnScreen {
    private var from: List<Marker>? = null
    private var area: Area? = null
    private var kept: List<Marker> = emptyList()

    /** [markers] inside [area]; all of them when there is no area to go by. */
    fun of(markers: List<Marker>, area: Area?): List<Marker> {
        if (area == null) return markers
        if (markers !== from || area != this.area) {
            kept = markers.filter { area.holds(it.box) }
            from = markers
            this.area = area
        }
        return kept
    }
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
        val saved = savedIn(dimension)
        if (!Config.showUndiscovered) return saved
        val nearby = Tracker.undiscovered(dimension).filter { Config.isShown(it.type) }
        if (nearby.isEmpty()) return saved
        // The same list while nothing changed, so what is kept from it ([OnScreen], [SpawnBoxes]) stays.
        val boxes = nearby.map { it.box }
        withNearby?.takeIf { it.saved === saved && it.nearby == nearby && it.boxes == boxes }?.let { return it.markers }
        val markers = saved + nearby.map { Marker(null, it) }
        withNearby = WithNearby(saved, nearby, boxes, markers)
        return markers
    }

    /** [visibleIn] with nearby ones, for the saved ones, nearby ones and their boxes (a box is grown as more is seen) it was made from. */
    private class WithNearby(val saved: List<Marker>, val nearby: List<Detection>, val boxes: List<Box?>, val markers: List<Marker>)

    private var withNearby: WithNearby? = null

    /**
     * What of the world map is on screen, in blocks of the dimension it shows, as [Outlines] last
     * drew it, and when ([mapAreaAt]); null before it has.
     */
    @Volatile
    var mapArea: Pair<String, Area>? = null
    @Volatile
    var mapAreaAt = 0L

    /** [mapArea], when it is this frame's (or one of the last few) and of [dimension]. */
    fun mapAreaOf(dimension: String): Area? =
        mapArea?.takeIf { it.first == dimension && System.nanoTime() - mapAreaAt < AREA_NANOS }?.second

    /** The saved part of [visibleIn], remade only when what is saved or which kinds are shown changes. */
    private class Saved(val dimension: String, val from: List<Structure>, val shown: Set<StructureType>, val hideCompleted: Boolean, val markers: List<Marker>)

    private val saved = HashMap<String, Saved>()

    private fun savedIn(dimension: String): List<Marker> {
        val from = StructureStore.inDimension(dimension)
        val shown = Config.shown
        val hideCompleted = Config.hideCompleted
        saved[dimension]?.takeIf { it.from === from && it.shown === shown && it.hideCompleted == hideCompleted }?.let { return it.markers }
        val markers = from.filter { it.type in shown && !(hideCompleted && it.completed) }.map { Marker(it, null) }
        saved[dimension] = Saved(dimension, from, shown, hideCompleted, markers)
        return markers
    }


    fun mapDimension(): String? =
        WorldMapSession.getCurrentSession()?.mapProcessor?.mapWorld?.currentDimension?.dimId?.let { dimensionId(it) }

    class Context {
        var markers: List<Marker> = emptyList()
        var next = 0
        /** Batches the icons for this frame; set in preRender, drawn in postRender. */
        var icons: MultiTextureRenderTypeRenderer? = null
    }

    class Provider : ElementRenderProvider<Marker, Context>() {
        private val onScreen = OnScreen()

        override fun begin(location: ElementRenderLocation, context: Context) {
            val dimension = if (location == ElementRenderLocation.WORLD_MAP) mapDimension() else null
            context.markers = dimension?.let { onScreen.of(visibleIn(it), mapAreaOf(it)) } ?: emptyList()
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
    /** How long the world map's [mapArea] holds: a few frames, so one from a map since closed is not used. */
    private const val AREA_NANOS = 250_000_000L
    /**
     * How big an icon is drawn, in Xaero's element units (which its own marker size setting also
     * scales): 26 (asked for as 2px bigger than 24) times the icon size setting.
     */
    fun size(): Float = ICON_SIZE * Config.iconScale
    private fun half(): Int = (size() / 2).toInt()
    private const val ICON_SIZE = 26f
}
