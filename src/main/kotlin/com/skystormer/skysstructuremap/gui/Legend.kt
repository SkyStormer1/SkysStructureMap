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
 * The legend in the top-right corner of Xaero's world map: a small see-through panel with one
 * line per kind of structure that can exist in the dimension the map is showing, with its icon
 * and how many you have discovered there. Clicking a line shows or hides that kind. The header
 * folds the legend away, and its switches turn box outlines and not-yet-visited structures on and
 * off and open the settings. When there are more lines than fit, the list scrolls with the wheel.
 */
object Legend {

    private const val WIDTH = 140
    private const val ROW = 11
    private const val MARGIN = 4

    /** Room left at the right edge for Xaero's compass. */
    private const val COMPASS = 30

    /** At most this many lines before the list scrolls. */
    private const val MAX_ROWS = 6

    private const val BACKGROUND = 0x70000000
    private const val HOVER = 0x30FFFFFF
    private const val OFF = 0xFF9A9A9A.toInt()

    private const val BOX = "Box"
    private const val NEAR = "Near"
    private const val SET = "Set"

    /** The panel on the map screen open now, for the scroll hook in `GuiMapMixin`. */
    private var current = WeakReference<Panel>(null)

    fun addTo(screen: Screen) {
        val panel = Panel(screen, screen.width - WIDTH - COMPASS, MARGIN, screen.height)
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

    class Panel(private val screen: Screen, x: Int, y: Int, private val screenHeight: Int) :
        AbstractWidget(x, y, WIDTH, ROW, Component.literal("Structures")) {

        private var offset = 0
        private var dimension: String? = null

        /** The kinds that can generate in the dimension the map shows; all of them if it is not a vanilla one. */
        private val types: List<StructureType>
            get() {
                val shown = Markers.mapDimension()
                if (shown != dimension) {
                    dimension = shown
                    offset = 0
                }
                val here = StructureType.entries.filter { it.dimension == shown }
                return here.ifEmpty { StructureType.entries }
            }

        /** How many lines show at once: all of them, up to [MAX_ROWS] and what the screen has room for. */
        private fun shownRows(types: List<StructureType>): Int =
            minOf(types.size, MAX_ROWS, maxOf(2, (screenHeight - y - ROW - 40) / ROW))

        private fun layout(types: List<StructureType>) {
            val rows = shownRows(types)
            height = if (Config.legendOpen) ROW * (1 + rows) + 2 else ROW
            offset = offset.coerceIn(0, maxOf(0, types.size - rows))
        }

        fun scroll(amount: Double) {
            if (!Config.legendOpen) return
            offset -= Math.signum(amount).toInt()
            layout(types)
        }

        // The switches at the right end of the header, right to left.
        private fun switchWidth(label: String) = font().width(label) + 4
        private val setLeft get() = x + width - 2 - switchWidth(SET)
        private val nearLeft get() = setLeft - 2 - switchWidth(NEAR)
        private val boxLeft get() = nearLeft - 2 - switchWidth(BOX)

        private fun over(mouseX: Double, left: Int, label: String) = mouseX >= left && mouseX < left + switchWidth(label)

        override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
            val types = types
            layout(types)
            val font = font()
            graphics.fill(x, y, x + width, y + height, BACKGROUND)

            // Header
            val overHeader = mouseY >= y && mouseY < y + ROW && mouseX >= x && mouseX < x + width
            val mx = mouseX.toDouble()
            val overBox = overHeader && over(mx, boxLeft, BOX)
            val overNear = overHeader && over(mx, nearLeft, NEAR)
            val overSet = overHeader && over(mx, setLeft, SET)
            if (overHeader && mouseX < boxLeft - 1) graphics.fill(x, y, boxLeft - 1, y + ROW, HOVER)
            graphics.text(font, if (Config.legendOpen) "- Structures" else "+ Structures", x + 3, y + 2, 0xFFFFFFFF.toInt(), false)
            switch(graphics, BOX, boxLeft, Config.outlines, overBox)
            switch(graphics, NEAR, nearLeft, Config.showUndiscovered, overNear)
            switch(graphics, SET, setLeft, false, overSet)
            when {
                overBox -> graphics.setTooltipForNextFrame(Component.literal("Box outlines for every structure: ${onOff(Config.outlines)}"), mouseX, mouseY)
                overNear -> graphics.setTooltipForNextFrame(Component.literal(
                    "Structures seen nearby that you have not discovered yet, faded: ${onOff(Config.showUndiscovered)}"
                ), mouseX, mouseY)
                overSet -> graphics.setTooltipForNextFrame(Component.literal("Settings: icon sizes, how close counts as discovering"), mouseX, mouseY)
            }
            if (!Config.legendOpen) return

            // One line per kind, scrolled.
            val rows = shownRows(types)
            val listTop = y + ROW + 1
            for (i in 0 until rows) {
                val type = types.getOrNull(i + offset) ?: break
                val top = listTop + i * ROW
                val shown = Config.isShown(type)
                val over = mouseX >= x && mouseX < x + width && mouseY >= top && mouseY < top + ROW
                if (over) graphics.fill(x, top, x + width, top + ROW, HOVER)
                // The same picture as on the map, faded when that kind is hidden.
                graphics.blit(RenderPipelines.GUI_TEXTURED, Icons.id(type), x + 2, top + 1, 0f, 0f, 9, 9, 16, 16, 16, 16,
                    if (shown) -1 else 0x60FFFFFF)
                graphics.text(font, type.plural, x + 13, top + 2, if (shown) 0xFFFFFFFF.toInt() else OFF, false)
                val count = StructureStore.all.count { it.type == type }.toString()
                graphics.text(font, count, x + width - 5 - font.width(count), top + 2, if (shown) 0xFFD0D0D0.toInt() else OFF, false)
                if (over) {
                    graphics.setTooltipForNextFrame(Component.literal(
                        "${if (shown) "Hide" else "Show"} ${type.plural.lowercase()} on the map"
                    ), mouseX, mouseY)
                }
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
            if (my < y + ROW) {
                when {
                    over(mx, setLeft, SET) -> {
                        Minecraft.getInstance().gui.setScreen(SettingsScreen(screen))
                        return true
                    }
                    over(mx, nearLeft, NEAR) -> Config.showUndiscovered = !Config.showUndiscovered
                    over(mx, boxLeft, BOX) -> Config.outlines = !Config.outlines
                    else -> Config.legendOpen = !Config.legendOpen
                }
                Config.save()
            } else {
                val row = ((my - (y + ROW + 1)) / ROW).toInt()
                types.getOrNull(row + offset)?.takeIf { row in 0 until shownRows(types) }?.let { type ->
                    Config.setShown(type, !Config.isShown(type))
                }
            }
            layout(types)
            // Taken here, so the map underneath does not also get the click.
            return true
        }

        override fun updateWidgetNarration(output: NarrationElementOutput) {
            defaultButtonNarrationText(output)
        }

        private fun font() = Minecraft.getInstance().font

        private fun onOff(on: Boolean) = if (on) "on" else "off"
    }
}
