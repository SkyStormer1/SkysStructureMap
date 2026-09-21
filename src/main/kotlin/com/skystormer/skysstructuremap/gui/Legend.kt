package com.skystormer.skysstructuremap.gui

import com.skystormer.skysstructuremap.Config
import com.skystormer.skysstructuremap.Icons
import com.skystormer.skysstructuremap.Markers
import com.skystormer.skysstructuremap.StructureStore
import com.skystormer.skysstructuremap.StructureType
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import java.lang.ref.WeakReference

/**
 * The legend on Xaero's world map: a small see-through panel with one line per kind of structure
 * that can exist in the dimension the map is showing, with its icon and how many you have
 * discovered. Clicking a line shows or hides that kind.
 *
 * The header folds the legend away when clicked and moves it when dragged; its switches turn box
 * outlines and not-yet-visited structures on and off and open the settings. The grip on the bottom
 * edge drags to show more or fewer lines; with more kinds than lines, the list scrolls with the
 * wheel. It is as wide as its text needs, so nothing overlaps at any GUI scale.
 */
object Legend {

    private const val ROW = 11
    private const val GRIP = 4

    private const val BACKGROUND = 0x70000000
    private const val HOVER = 0x30FFFFFF
    private const val OFF = 0xFF9A9A9A.toInt()

    private const val TITLE = "Structures"
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

    class Panel(private val screen: Screen) : AbstractWidget(0, 0, 0, ROW, Component.literal(TITLE)) {

        private var offset = 0
        private var dimension: String? = null

        private enum class Drag { NONE, MOVE, RESIZE }
        private var drag = Drag.NONE
        private var grabX = 0.0
        private var grabY = 0.0
        private var moved = false

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

        /** How many lines show at once: the number chosen, but no more than there are or than fit below. */
        private fun shownRows(types: List<StructureType>): Int =
            minOf(types.size, Config.legendRows, maxOf(1, (screen.height - y - ROW - GRIP - 4) / ROW))

        private fun switchWidth(label: String) = font().width(label) + 4

        private fun switchesWidth() = switchWidth(BOX) + switchWidth(NEAR) + switchWidth(SET) + 6

        /** Wide enough for the header and for every line, with its count. */
        private fun neededWidth(types: List<StructureType>): Int {
            val font = font()
            val header = 3 + font.width("- $TITLE") + 6 + switchesWidth()
            val lines = types.maxOfOrNull { 13 + font.width(it.plural) + 8 + font.width("999") + 5 } ?: 0
            return maxOf(header, lines)
        }

        /** Size and place from the settings, kept on screen. */
        private fun layout(types: List<StructureType>) {
            width = neededWidth(types)
            val rows = shownRows(types)
            height = if (Config.legendOpen) ROW * (1 + rows) + 2 + GRIP else ROW
            x = (screen.width - Config.legendRight - width).coerceIn(0, maxOf(0, screen.width - width))
            y = Config.legendTop.coerceIn(0, maxOf(0, screen.height - height))
            offset = offset.coerceIn(0, maxOf(0, types.size - rows))
        }

        fun scroll(amount: Double) {
            if (!Config.legendOpen) return
            offset -= Math.signum(amount).toInt()
            layout(types)
        }

        // The switches at the right end of the header, right to left.
        private val setLeft get() = x + width - 2 - switchWidth(SET)
        private val nearLeft get() = setLeft - 2 - switchWidth(NEAR)
        private val boxLeft get() = nearLeft - 2 - switchWidth(BOX)

        private fun over(mouseX: Double, left: Int, label: String) = mouseX >= left && mouseX < left + switchWidth(label)

        private fun onGrip(mouseY: Double) = Config.legendOpen && mouseY >= y + height - GRIP

        override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
            val types = types
            layout(types)
            val font = font()
            graphics.fill(x, y, x + width, y + height, BACKGROUND)

            // Header
            val inside = mouseX >= x && mouseX < x + width
            val overHeader = inside && mouseY >= y && mouseY < y + ROW
            val mx = mouseX.toDouble()
            val overBox = overHeader && over(mx, boxLeft, BOX)
            val overNear = overHeader && over(mx, nearLeft, NEAR)
            val overSet = overHeader && over(mx, setLeft, SET)
            if ((overHeader && mouseX < boxLeft - 1) || drag == Drag.MOVE) graphics.fill(x, y, boxLeft - 1, y + ROW, HOVER)
            graphics.text(font, if (Config.legendOpen) "- $TITLE" else "+ $TITLE", x + 3, y + 2, 0xFFFFFFFF.toInt(), false)
            switch(graphics, BOX, boxLeft, Config.outlines, overBox)
            switch(graphics, NEAR, nearLeft, Config.showUndiscovered, overNear)
            switch(graphics, SET, setLeft, false, overSet)
            if (drag == Drag.NONE) {
                when {
                    overBox -> tooltip(graphics, "Box outlines for every structure: ${onOff(Config.outlines)}", mouseX, mouseY)
                    overNear -> tooltip(graphics, "Structures seen nearby that you have not discovered yet, faded: ${onOff(Config.showUndiscovered)}", mouseX, mouseY)
                    overSet -> tooltip(graphics, "Settings: icon sizes, how close counts as discovering, sharing", mouseX, mouseY)
                    overHeader -> tooltip(graphics, "Click to fold the legend away or open it. Drag to move it.", mouseX, mouseY)
                }
            }
            if (!Config.legendOpen) return

            // One line per kind, scrolled.
            val rows = shownRows(types)
            val listTop = y + ROW + 1
            for (i in 0 until rows) {
                val type = types.getOrNull(i + offset) ?: break
                val top = listTop + i * ROW
                val shown = Config.isShown(type)
                val over = drag == Drag.NONE && inside && mouseY >= top && mouseY < top + ROW
                if (over) graphics.fill(x, top, x + width, top + ROW, HOVER)
                // The same picture as on the map, faded when that kind is hidden.
                graphics.blit(RenderPipelines.GUI_TEXTURED, Icons.id(type), x + 2, top + 1, 0f, 0f, 9, 9, 16, 16, 16, 16,
                    if (shown) -1 else 0x60FFFFFF)
                graphics.text(font, type.plural, x + 13, top + 2, if (shown) 0xFFFFFFFF.toInt() else OFF, false)
                val count = StructureStore.all.count { it.type == type }.toString()
                graphics.text(font, count, x + width - 5 - font.width(count), top + 2, if (shown) 0xFFD0D0D0.toInt() else OFF, false)
                if (over) tooltip(graphics, "${if (shown) "Hide" else "Show"} ${type.plural.lowercase()} on the map", mouseX, mouseY)
            }

            // A thin scroll bar when not everything fits.
            val maxOffset = types.size - rows
            if (maxOffset > 0) {
                val trackHeight = rows * ROW
                val barHeight = maxOf(4, trackHeight * rows / types.size)
                val barTop = listTop + (trackHeight - barHeight) * offset / maxOffset
                graphics.fill(x + width - 2, listTop, x + width, listTop + trackHeight, 0x40FFFFFF)
                graphics.fill(x + width - 2, barTop, x + width, barTop + barHeight, 0xC0FFFFFF.toInt())
            }

            // The grip along the bottom edge, for showing more or fewer lines.
            val gripTop = y + height - GRIP
            val overGrip = inside && mouseY >= gripTop && mouseY < y + height
            val gripColour = if (overGrip || drag == Drag.RESIZE) 0xC0FFFFFF.toInt() else 0x50FFFFFF
            graphics.fill(x + width / 2 - 8, gripTop + 1, x + width / 2 + 8, gripTop + 2, gripColour)
            if (overGrip && drag == Drag.NONE) tooltip(graphics, "Drag to show more or fewer lines", mouseX, mouseY)
        }

        /**
         * A tooltip wrapped onto several lines, and never above the top of the screen: the game
         * puts tooltips a little above the mouse, which near the top edge ran them off screen.
         */
        private fun tooltip(graphics: GuiGraphicsExtractor, text: String, mouseX: Int, mouseY: Int) {
            val font = font()
            val lines = font.split(Component.literal(text), TOOLTIP_WIDTH)
            graphics.setTooltipForNextFrame(font, lines, mouseX, maxOf(mouseY, 16))
        }

        private fun switch(graphics: GuiGraphicsExtractor, label: String, left: Int, on: Boolean, hovered: Boolean) {
            val font = font()
            graphics.fill(left, y + 1, left + switchWidth(label), y + ROW - 1, if (on) 0x60FFFFFF else if (hovered) 0x30FFFFFF else 0x20FFFFFF)
            graphics.text(font, label, left + 2, y + 2, if (on || label == SET) 0xFFFFFFFF.toInt() else OFF, false)
        }

        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
            if (!visible || event.button() != 0 || !isMouseOver(event.x(), event.y())) return false
            val mx = event.x()
            val my = event.y()
            val types = types
            when {
                my < y + ROW -> when {
                    over(mx, setLeft, SET) -> {
                        Minecraft.getInstance().gui.setScreen(SettingsScreen(screen))
                        return true
                    }
                    over(mx, nearLeft, NEAR) -> { Config.showUndiscovered = !Config.showUndiscovered; Config.save() }
                    over(mx, boxLeft, BOX) -> { Config.outlines = !Config.outlines; Config.save() }
                    // The title: a drag moves the legend, a click without one folds it (on release).
                    else -> startDrag(Drag.MOVE, mx - x, my - y)
                }
                onGrip(my) -> startDrag(Drag.RESIZE, 0.0, 0.0)
                else -> {
                    val row = ((my - (y + ROW + 1)) / ROW).toInt()
                    types.getOrNull(row + offset)?.takeIf { row in 0 until shownRows(types) }?.let { type ->
                        Config.setShown(type, !Config.isShown(type))
                    }
                }
            }
            layout(types)
            // Taken here, so the map underneath does not also get the click.
            return true
        }

        private fun startDrag(kind: Drag, offsetX: Double, offsetY: Double) {
            drag = kind
            grabX = offsetX
            grabY = offsetY
            moved = false
        }

        override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
            when (drag) {
                Drag.NONE -> return false
                Drag.MOVE -> {
                    val newX = (event.x() - grabX).toInt().coerceIn(0, maxOf(0, screen.width - width))
                    val newY = (event.y() - grabY).toInt().coerceIn(0, maxOf(0, screen.height - ROW))
                    if (newX != x || newY != y) moved = true
                    Config.legendRight = screen.width - newX - width
                    Config.legendTop = newY
                }
                Drag.RESIZE -> {
                    val rows = ((event.y() - (y + ROW + 1)) / ROW).toInt()
                    Config.legendRows = rows.coerceIn(1, maxOf(1, types.size))
                }
            }
            layout(types)
            return true
        }

        override fun mouseReleased(event: MouseButtonEvent): Boolean {
            if (drag == Drag.NONE) return false
            if (drag == Drag.MOVE && !moved) Config.legendOpen = !Config.legendOpen
            drag = Drag.NONE
            Config.save()
            layout(types)
            return true
        }

        override fun updateWidgetNarration(output: NarrationElementOutput) {
            defaultButtonNarrationText(output)
        }

        private fun font() = Minecraft.getInstance().font

        private fun onOff(on: Boolean) = if (on) "on" else "off"
    }
}
