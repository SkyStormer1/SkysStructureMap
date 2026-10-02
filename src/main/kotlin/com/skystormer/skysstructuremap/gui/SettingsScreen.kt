package com.skystormer.skysstructuremap.gui

import com.skystormer.skysstructuremap.BobbyCoverage
import com.skystormer.skysstructuremap.BobbyScan
import com.skystormer.skysstructuremap.ChunkScanner
import com.skystormer.skysstructuremap.Config
import com.skystormer.skysstructuremap.Menus
import com.skystormer.skysstructuremap.StructureStore
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
        val half = (WIDTH - GAP) / 2
        var y = maxOf(4, (height - 257 - if (BobbyCoverage.available) ROW + GAP else 0) / 2)
        addRenderableWidget(StringWidget(left, y, WIDTH, font.lineHeight, title, font))
        y += font.lineHeight + GAP * 3

        addRenderableWidget(ScaleSlider(left, y, "World map icons", Config.iconScale,
            "How big the structure icons are on Xaero's World Map.") { Config.iconScale = it })
        y += ROW + GAP
        addRenderableWidget(ScaleSlider(left, y, "Minimap icons", Config.minimapIconScale,
            "How big the structure icons are on Xaero's Minimap.") { Config.minimapIconScale = it })
        y += ROW + GAP * 3

        onOff(left, y, half, "Show structures", Config.show,
            "Everything this mod draws, in one switch: icons, outlines and spawn boxes. Your discoveries are kept either way."
        ) { Config.show = it }
        onOff(left + half + GAP, y, WIDTH - half - GAP, "Hide completed", Config.hideCompleted,
            "Leaves the structures you have marked as completed off the maps, so only the ones left show."
        ) { Config.hideCompleted = it }
        y += ROW + GAP * 3

        onOff(left, y, half, "Spawn boxes: map", Config.spawnBoxesOnMap,
            "The boxes mobs spawn in, on the world map: fortresses (with each of their pieces), ocean monuments, pillager outposts and witch huts."
        ) { Config.spawnBoxesOnMap = it }
        onOff(left + half + GAP, y, WIDTH - half - GAP, "World", Config.spawnBoxesInWorld,
            "The same boxes drawn in the world around you, like MiniHUD's. A key for this can be set in Controls."
        ) { Config.spawnBoxesInWorld = it }
        y += ROW + GAP * 3

        addRenderableWidget(DistanceSlider(left, y))
        y += ROW + GAP
        onOff(left, y, WIDTH, "Chat line on discovering one", Config.announce,
            "A line in your chat, which only you see, whenever you discover a structure."
        ) { Config.announce = it }
        y += ROW + GAP
        y = addShareCommand(left, y)

        button(left, y, "Look again around me",
            "Goes through the chunks loaded around you again, as if they had just arrived, and lets anything you " +
                "deleted here be found again. Worth a try after an update, or if something plainly there is missing."
        ) { lookAgain() }
        y += ROW + GAP
        if (BobbyScan.isRunning) {
            button(left, y, "Stop scanning Bobby's cache",
                "Stops looking through Bobby's cache. What it has already saved stays."
            ) { BobbyScan.stop(); onClose() }
        } else {
            button(left, y, "Scan Bobby's cache for structures",
                "Looks through every chunk the Bobby mod saved of this server for structures you passed before " +
                    "installing this mod, and saves them as discovered. Backs up your structures first. " +
                    "Ones you already have are kept, and ones you deleted stay deleted."
            ) { BobbyScan.start(); onClose() }
        }
        y += ROW + GAP
        if (BobbyCoverage.available) {
            onOff(left, y, WIDTH, "Show Bobby's saved chunks on the map", Config.bobbyCoverage,
                "A debug tint on the world map over every chunk Bobby has saved of this server. Anywhere your map " +
                    "shows without it, Bobby has nothing, so a Bobby scan cannot find structures there. Pointing at " +
                    "a chunk says when Bobby saved it."
            ) { if (it != Config.bobbyCoverage) BobbyCoverage.toggle() }
            y += ROW + GAP
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) { onClose() }.bounds(left, y, WIDTH, ROW).build())
    }

    /** An on/off switch with its explanation. */
    private fun onOff(x: Int, y: Int, width: Int, label: String, on: Boolean, explanation: String, onChange: (Boolean) -> Unit) {
        addRenderableWidget(
            CycleButton.onOffBuilder(on)
                .create(x, y, width, ROW, Component.literal(label)) { _, picked -> onChange(picked) }
                .also { it.setTooltip(Tooltip.create(Component.literal(explanation))) }
        )
    }

    /** A button across the width of the screen, with its explanation. */
    private fun button(x: Int, y: Int, label: String, explanation: String, onPress: () -> Unit) {
        addRenderableWidget(
            Button.builder(Component.literal(label)) { onPress() }.bounds(x, y, WIDTH, ROW).build()
                .also { it.setTooltip(Tooltip.create(Component.literal(explanation))) }
        )
    }

    /** The box for the command private shares are sent with; answers the next row's y. */
    private fun addShareCommand(left: Int, y: Int): Int {
        val labelWidth = font.width("Private share command: /") + 4
        addRenderableWidget(StringWidget(left, y + 6, labelWidth, font.lineHeight, Component.literal("Private share command: /"), font))
        command = EditBox(font, left + labelWidth, y, WIDTH - labelWidth, ROW, Component.literal("Private share command"))
        command.setMaxLength(24)
        command.value = Config.privateShareCommand
        command.setTooltip(Tooltip.create(Component.literal("The command Share sends to one player with: tell on most servers, or msg or w where those are used.")))
        addRenderableWidget(command)
        return y + ROW + GAP * 3
    }

    /** Looks through the loaded chunks again, and says what came of it. */
    private fun lookAgain() {
        val minecraft = net.minecraft.client.Minecraft.getInstance()
        val level = minecraft.level
        val player = minecraft.player
        if (level == null || player == null) {
            Menus.say("Join a world first")
            return
        }
        val here = player.blockPosition()
        // Anything deleted around here gets another chance, or looking again could not find it.
        val forgotten = StructureStore.forgetDeletedNear(
            level.dimension().identifier().toString(), here.x, here.z, LOOK_AGAIN_CHUNKS * 16.0,
        )
        val chunks = ChunkScanner.lookAgain(level, here, LOOK_AGAIN_CHUNKS)
        Menus.tell(
            "Looked again through $chunks chunks around you" +
                if (forgotten > 0) ", and $forgotten you had deleted here can be found again" else ""
        )
        onClose()
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

    /**
     * How near counts as discovering, in blocks: from inside its box only, in steps of 4, up to the
     * server's whole range ([Config.discoverRange]); the far end follows the server if it changes.
     */
    private class DistanceSlider(x: Int, y: Int) :
        AbstractSliderButton(x, y, WIDTH, ROW, Component.empty(), 0.0) {

        // Fixed while the screen is open, so the slider does not move under the mouse.
        private val range = Config.discoverRange().coerceAtLeast(STEP)

        init {
            value = when (val d = Config.discoverDistance) {
                Config.DISCOVER_ALL -> 1.0
                else -> (d.toDouble() / range).coerceIn(0.0, 1.0)
            }
            updateMessage()
            setTooltip(Tooltip.create(Component.literal(
                "How close you must come to a structure for it to count as discovered, sideways, at any height. " +
                    "From inside it, all the way to the server's range ($range blocks): " +
                    "structures are only recognised in the chunks the server sends you."
            )))
        }

        private val blocks: Int get() =
            if (value >= 1.0) Config.DISCOVER_ALL else ((value * range / STEP).roundToInt() * STEP).coerceAtMost(range)

        override fun updateMessage() {
            message = Component.literal(when (val b = blocks) {
                0 -> "Discover: only when inside"
                Config.DISCOVER_ALL, range -> "Discover within: server range ($range blocks)"
                else -> "Discover within: $b blocks"
            })
        }

        override fun applyValue() {
            Config.discoverDistance = blocks
        }

        private companion object {
            const val STEP = 4
        }
    }

    private companion object {
        /** How far out, in chunks, "Look again" goes: the whole of most render distances. */
        const val LOOK_AGAIN_CHUNKS = 16

        const val WIDTH = 240
        const val ROW = 20
        const val GAP = 2
    }
}
