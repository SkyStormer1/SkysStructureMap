package com.skystormer.skysstructuremap.gui

import com.skystormer.skysstructuremap.Config
import com.skystormer.skysstructuremap.Icons
import com.skystormer.skysstructuremap.Markers
import com.skystormer.skysstructuremap.StructureStore
import com.skystormer.skysstructuremap.StructureType
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import java.lang.ref.WeakReference

/**
 * The legend on Xaero's world map: a small see-through panel with one line per kind of structure
 * that can exist in the dimension the map is showing, with its icon and how many you have
 * discovered. Clicking a line shows or hides that kind.
 *
 * The header's switches hide every kind at once (and bring back just those), turn box outlines and
 * not-yet-visited structures on and off, and open the settings; with more kinds than lines, the list scrolls with the wheel. It is as wide as its text
 * needs, so nothing overlaps at any GUI scale. Moving, folding, docking under another panel, the
 * grips and the size are [DockPanel]'s.
 */
object Legend {

    private const val ROW = DockPanel.ROW

    private const val BACKGROUND = 0x70000000
    private const val HOVER = 0x30FFFFFF
    private const val OFF = 0xFF9A9A9A.toInt()

    private const val TITLE = "Structures"
    private const val HIDE = "Hide"
    private const val BOX = "Box"
    private const val NEAR = "Near"
    private const val SET = "Set"

    /** How wide a tooltip may be before it wraps onto another line. */
    private const val TOOLTIP_WIDTH = 170

    /** The panel on the map screen open now, for the scroll hook in `GuiMapMixin`. */
    private var current = WeakReference<Panel>(null)

    fun addTo(screen: Screen) {
        val panel = Panel(screen)
        Screens.getWidgets(screen).add(panel)
        current = WeakReference(panel)
    }

    /** Called before Xaero zooms the map: true when the wheel was over the legend and scrolled it. */
    @JvmStatic
    fun scrolled(mouseX: Double, mouseY: Double, amount: Double): Boolean {
        val panel = current.get() ?: return false
        if (!panel.visible || !panel.isMouseOver(mouseX, mouseY)) return false
        panel.scroll(amount)
        return true
    }

    class Panel(screen: Screen) : DockPanel(screen, "skysstructuremap:legend", TITLE) {

        private var offset = 0
        override var scrollOffset: Int
            get() = offset
            set(value) { offset = value }
        private var dimension: String? = null

        override fun savedLeft(width: Int) = screen.width - Config.legendRight - width
        override fun saveLeft(left: Int, width: Int) { Config.legendRight = screen.width - left - width }
        override var savedWidth: Int
            get() = Config.legendWidth
            set(value) { Config.legendWidth = value }
        override var savedTop: Int
            get() = Config.legendTop
            set(value) { Config.legendTop = value }
        override var savedRows: Int
            get() = Config.legendRows
            set(value) { Config.legendRows = value }
        override var savedOpen: Boolean
            get() = Config.legendOpen
            set(value) { Config.legendOpen = value }
        override var savedScale: Float
            get() = Config.legendScale
            set(value) { Config.legendScale = value }
        override var savedExtra: Int
            get() = Config.legendExtra
            set(value) { Config.legendExtra = value }
        override var savedUnder: String
            get() = Config.legendUnder
            set(value) { Config.legendUnder = value }
        override val maxScale get() = Config.panelMaxScale
        override fun save() = Config.save()

        override val background = BACKGROUND
        override val listTop = ROW + 1

        /** The kinds that can generate in the dimension the map shows; all of them if it is not a vanilla one. */
        private val types: List<StructureType>
            get() {
                val shown = Markers.mapDimension()
                if (shown != dimension) {
                    dimension = shown
                    offset = 0
                }
                return StructureType.entries.filter { it.dimension == shown }.ifEmpty { StructureType.entries }
            }

        override fun itemCount() = types.size

        private fun switchWidth(label: String) = font().width(label) + 4

        private fun switchesWidth() = switchWidth(HIDE) + switchWidth(BOX) + switchWidth(NEAR) + switchWidth(SET) + 8

        /** Wide enough for the header and for every line, with its count. */
        override fun naturalWidth(): Int {
            val font = font()
            val header = 3 + font.width("- $TITLE") + 6 + switchesWidth()
            val lines = types.maxOfOrNull { 13 + font.width(it.plural) + 8 + font.width("999") + 8 } ?: 0
            return maxOf(header, lines)
        }

        override fun afterLayout() {
            offset = offset.coerceIn(0, maxOf(0, types.size - shownRows))
        }

        fun scroll(amount: Double) {
            if (!open) return
            offset = (offset - Math.signum(amount).toInt()).coerceIn(0, maxOf(0, types.size - shownRows))
        }

        // The switches at the right end of the header, right to left.
        private val setLeft get() = baseWidth - 2 - switchWidth(SET)
        private val nearLeft get() = setLeft - 2 - switchWidth(NEAR)
        private val boxLeft get() = nearLeft - 2 - switchWidth(BOX)
        private val hideLeft get() = boxLeft - 2 - switchWidth(HIDE)

        private fun over(lx: Double, left: Int, label: String) = lx >= left && lx < left + switchWidth(label)

        override fun drawLocal(graphics: GuiGraphicsExtractor, lx: Int, ly: Int, mouseX: Int, mouseY: Int, idle: Boolean, partialTick: Float) {
            val types = types
            val font = font()

            // Header
            val inside = lx in 0 until baseWidth
            val overHeader = inside && ly in 0 until ROW
            val mx = lx.toDouble()
            val overHide = overHeader && over(mx, hideLeft, HIDE)
            val overBox = overHeader && over(mx, boxLeft, BOX)
            val overNear = overHeader && over(mx, nearLeft, NEAR)
            val overSet = overHeader && over(mx, setLeft, SET)
            if (overHeader && lx < hideLeft - 1) graphics.fill(0, 0, hideLeft - 1, ROW, HOVER)
            graphics.text(font, if (open) "- $TITLE" else "+ $TITLE", 3, 2, 0xFFFFFFFF.toInt(), false)
            // Lit while every kind here is hidden.
            switch(graphics, HIDE, hideLeft, types.none { Config.isShown(it) }, overHide)
            switch(graphics, BOX, boxLeft, Config.outlines, overBox)
            switch(graphics, NEAR, nearLeft, Config.showUndiscovered, overNear)
            switch(graphics, SET, setLeft, false, overSet)
            if (idle) {
                when {
                    overHide -> tooltip(graphics, hideTip(types), mouseX, mouseY)
                    overBox -> tooltip(graphics, "Box outlines for every structure: ${onOff(Config.outlines)}", mouseX, mouseY)
                    overNear -> tooltip(graphics, "Structures seen nearby that you have not discovered yet, faded: ${onOff(Config.showUndiscovered)}", mouseX, mouseY)
                    overSet -> tooltip(graphics, "Settings: icon sizes, how close counts as discovering, sharing", mouseX, mouseY)
                    overHeader -> tooltip(graphics, "Click to fold the legend away or open it. Drag to move it, or under another panel to dock it there.", mouseX, mouseY)
                }
            }
            if (!open) return

            // One line per kind, scrolled.
            val rows = shownRows
            for (i in 0 until rows) {
                val type = types.getOrNull(i + offset) ?: break
                val top = listTop + i * ROW
                val shown = Config.isShown(type)
                val over = idle && inside && ly >= top && ly < top + ROW
                if (over) graphics.fill(0, top, baseWidth, top + ROW, HOVER)
                // The same picture as on the map, faded when that kind is hidden.
                graphics.blit(RenderPipelines.GUI_TEXTURED, Icons.id(type), 2, top + 1, 0f, 0f, 9, 9, 16, 16, 16, 16,
                    if (shown) -1 else 0x60FFFFFF)
                graphics.text(font, type.plural, 13, top + 2, if (shown) 0xFFFFFFFF.toInt() else OFF, false)
                val count = StructureStore.all.count { it.type == type }.toString()
                graphics.text(font, count, baseWidth - 3 - DockPanel.BAR - 3 - font.width(count), top + 2, if (shown) 0xFFD0D0D0.toInt() else OFF, false)
                if (over) tooltip(graphics, "${if (shown) "Hide" else "Show"} ${type.plural.lowercase()} on the map", mouseX, mouseY)
            }

        }

        private fun hideTip(types: List<StructureType>): String {
            val remembered = types.count { it in Config.hiddenByHide }
            return when {
                types.any { Config.isShown(it) } -> "Hide every kind of structure showing here, and remember which, so clicking again brings back only those. Kinds you hid yourself stay hidden."
                remembered > 0 -> "Bring back the $remembered kind${if (remembered == 1) "" else "s"} Hide hid here, and no others."
                else -> "Show every kind of structure here."
            }
        }

        /**
         * A tooltip wrapped onto several lines, and never above the top of the screen: the game
         * puts tooltips a little above the mouse, which near the top edge ran them off screen.
         */
        private fun tooltip(graphics: GuiGraphicsExtractor, text: String, mouseX: Int, mouseY: Int) {
            DockPanel.tooltip(graphics, text, mouseX, mouseY, TOOLTIP_WIDTH)
        }

        private fun switch(graphics: GuiGraphicsExtractor, label: String, left: Int, on: Boolean, hovered: Boolean) {
            val font = font()
            graphics.fill(left, 1, left + switchWidth(label), ROW - 1, if (on) 0x60FFFFFF else if (hovered) 0x30FFFFFF else 0x20FFFFFF)
            graphics.text(font, label, left + 2, 2, if (on || label == SET) 0xFFFFFFFF.toInt() else OFF, false)
        }

        override fun clickLocal(lx: Double, ly: Double): Click {
            if (ly < ROW) {
                when {
                    over(lx, setLeft, SET) -> Minecraft.getInstance().gui.setScreen(SettingsScreen(screen))
                    over(lx, nearLeft, NEAR) -> { Config.showUndiscovered = !Config.showUndiscovered; Config.save() }
                    over(lx, boxLeft, BOX) -> { Config.outlines = !Config.outlines; Config.save() }
                    over(lx, hideLeft, HIDE) -> Config.toggleHide(types)
                    // The title: a drag moves the legend, a click without one folds it (on release).
                    else -> return Click.MOVE
                }
                return Click.DONE
            }
            val types = types
            val row = ((ly - listTop) / ROW).toInt()
            types.getOrNull(row + offset)?.takeIf { row in 0 until shownRows }?.let { type ->
                Config.setShown(type, !Config.isShown(type))
            }
            return Click.DONE
        }

        private fun font() = Minecraft.getInstance().font

        private fun onOff(on: Boolean) = if (on) "on" else "off"
    }
}
