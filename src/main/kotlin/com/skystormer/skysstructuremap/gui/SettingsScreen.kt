package com.skystormer.skysstructuremap.gui

import com.skystormer.skysstructuremap.Config
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

/**
 * The settings in `config/skysstructuremap.json` that are not on the legend: icon sizes, how close
 * counts as discovering a structure, and the chat line when you do. Changes show at once; Done
 * saves. Opened from the legend's "Set" switch, or Mod Menu's cog.
 */
class SettingsScreen(private val parent: Screen?) : Screen(Component.literal("Sky's Structure Map")) {

    private lateinit var command: EditBox

    override fun init() {
        val left = width / 2 - WIDTH / 2
        var y = maxOf(4, (height - 205) / 2)
        addRenderableWidget(StringWidget(left, y, WIDTH, font.lineHeight, title, font))
        y += font.lineHeight + GAP * 3

        addRenderableWidget(ScaleSlider(left, y, "World map icons", Config.iconScale,
            "How big the structure icons are on Xaero's World Map.") { Config.iconScale = it })
        y += ROW + GAP
        addRenderableWidget(ScaleSlider(left, y, "Minimap icons", Config.minimapIconScale,
            "How big the structure icons are on Xaero's Minimap.") { Config.minimapIconScale = it })
        y += ROW + GAP * 3

        val half = (WIDTH - GAP) / 2
        addRenderableWidget(
            CycleButton.onOffBuilder(Config.spawnBoxesOnMap)
                .create(left, y, half, ROW, Component.literal("Spawn boxes: map")) { _, on -> Config.spawnBoxesOnMap = on }
                .also { it.setTooltip(Tooltip.create(Component.literal(
                    "The boxes mobs spawn in, on the world map: fortresses (with their crossroads), ocean monuments, pillager outposts and witch huts."
                ))) }
        )
        addRenderableWidget(
            CycleButton.onOffBuilder(Config.spawnBoxesInWorld)
                .create(left + half + GAP, y, WIDTH - half - GAP, ROW, Component.literal("World")) { _, on -> Config.spawnBoxesInWorld = on }
                .also { it.setTooltip(Tooltip.create(Component.literal(
                    "The same boxes drawn in the world around you, like MiniHUD's. A key for this can be set in Controls."
                ))) }
        )
        y += ROW + GAP * 3

        addRenderableWidget(DistanceSlider(left, y))
        y += ROW + GAP
        addRenderableWidget(
            CycleButton.onOffBuilder(Config.announce)
                .create(left, y, WIDTH, ROW, Component.literal("Chat line on discovering one")) { _, on -> Config.announce = on }
                .also { it.setTooltip(Tooltip.create(Component.literal("A line in your chat, which only you see, whenever you discover a structure."))) }
        )
        y += ROW + GAP
        val labelWidth = font.width("Private share command: /") + 4
        addRenderableWidget(StringWidget(left, y + 6, labelWidth, font.lineHeight, Component.literal("Private share command: /"), font))
        command = EditBox(font, left + labelWidth, y, WIDTH - labelWidth, ROW, Component.literal("Private share command"))
        command.setMaxLength(24)
        command.value = Config.privateShareCommand
        command.setTooltip(Tooltip.create(Component.literal("The command Share sends to one player with: tell on most servers, or msg or w where those are used.")))
        addRenderableWidget(command)
        y += ROW + GAP * 3

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) { onClose() }.bounds(left, y, WIDTH, ROW).build())
    }

    override fun onClose() {
        command.value.trim().removePrefix("/").takeIf { it.isNotEmpty() && ' ' !in it }?.let { Config.privateShareCommand = it }
        Config.save()
        minecraft.gui.setScreen(parent)
    }

    /** A size from [Config.MIN_SCALE] to [Config.MAX_SCALE] in steps of a tenth. */
    private class ScaleSlider(x: Int, y: Int, private val name: String, initial: Float, explanation: String, private val onChange: (Float) -> Unit) :
        AbstractSliderButton(x, y, WIDTH, ROW, Component.empty(), toSlider(initial)) {

        init {
            updateMessage()
            setTooltip(Tooltip.create(Component.literal(explanation)))
        }

        private val scale: Float get() = (Config.MIN_SCALE + (value * STEPS).roundToInt() * STEP)

        override fun updateMessage() {
            message = Component.literal("$name: ${(scale * 100).roundToInt()}%")
        }

        override fun applyValue() = onChange(scale)

        companion object {
            private const val STEP = 0.1f
            private val STEPS = ((Config.MAX_SCALE - Config.MIN_SCALE) / STEP).roundToInt()
            fun toSlider(scale: Float): Double = (((scale - Config.MIN_SCALE) / STEP).roundToInt().coerceIn(0, STEPS)).toDouble() / STEPS
        }
    }

    /** How near counts as discovering, 0 (inside only) to [Config.MAX_DISCOVER_DISTANCE] blocks in steps of 4. */
    private class DistanceSlider(x: Int, y: Int) :
        AbstractSliderButton(x, y, WIDTH, ROW, Component.empty(), Config.discoverDistance.toDouble() / Config.MAX_DISCOVER_DISTANCE) {

        init {
            updateMessage()
            setTooltip(Tooltip.create(Component.literal(
                "How close you must come to a structure for it to count as discovered, sideways, at any height. " +
                    "At 0 you have to be inside it."
            )))
        }

        private val blocks: Int get() = ((value * Config.MAX_DISCOVER_DISTANCE / 4).roundToInt() * 4)

        override fun updateMessage() {
            message = Component.literal(if (blocks == 0) "Discover: only when inside" else "Discover within: $blocks blocks")
        }

        override fun applyValue() {
            Config.discoverDistance = blocks
        }
    }

    private companion object {
        const val WIDTH = 240
        const val ROW = 20
        const val GAP = 2
    }
}
