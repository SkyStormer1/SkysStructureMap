package com.skystormer.skysstructuremap

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import xaero.map.gui.IRightClickableElement
import xaero.map.gui.dropdown.rightclick.RightClickOption
import java.util.UUID

/** The right-click menu on a structure's badge on Xaero's world map. */
object Menus {

    fun addMarkerOptions(options: ArrayList<RightClickOption>, target: IRightClickableElement, marker: Marker) {
        try {
            options.add(option("Make waypoint", options.size, target) { Waypoints.save(marker) }.setActive(Waypoints.available()))
            options.add(option("Copy coordinates", options.size, target) { copy(marker) })
            options.add(option("Share in chat…", options.size, target) { share(marker) })
            // While outlines are on for everything, this one's is already showing.
            if (!Config.outlines) {
                options.add(option(if (marker.outlined) "Hide outline" else "Show outline", options.size, target) { toggleOutline(marker) })
            }
            val structure = marker.structure
            if (structure != null) {
                options.add(option("Delete…", options.size, target) { parent -> confirmDelete(parent, structure) })
            } else {
                options.add(option("Mark as discovered", options.size, target) { markDiscovered(marker) })
            }
        } catch (e: Throwable) {
            Log.error("Could not add structure options to Xaero's right-click menu", e)
        }
    }

    /** `x y z (Dimension)`: the numbers first, the way `/tp` and most chat messages want them. */
    fun coordinates(marker: Marker): String {
        val position = "${marker.box.centreX} ${marker.y} ${marker.box.centreZ}"
        return dimensionName(marker.dimension)?.let { "$position ($it)" } ?: position
    }

    fun copy(marker: Marker) {
        val text = coordinates(marker)
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(text)
            say("Copied $text")
        } catch (e: Throwable) {
            Log.error("Could not copy $text to the clipboard", e)
            say("Could not copy $text to the clipboard")
        }
    }

    /**
     * Opens chat with the structure typed in: its name and coordinates for anyone to read, and a
     * code that lets players with this mod add it to their map ([StructureShare]). Nothing is sent
     * until you press Enter, so it can also be put after `/msg <player>` to share privately.
     */
    fun share(marker: Marker) {
        val line = StructureShare.message(marker)
        if (line.length > StructureShare.MAX_CHAT) return say("That structure is too long to share in one line of chat")
        Minecraft.getInstance().gui.openChatAndAddText(ChatComponent.ChatMethod.MESSAGE, line)
    }

    /** Turns this one structure's box outline on or off, whatever the legend's Box switch says. */
    private fun toggleOutline(marker: Marker) {
        val structure = marker.structure
        val on = !marker.outlined
        if (structure != null) {
            StructureStore.byId(structure.id)?.let { StructureStore.put(it.copy(outlined = on)) }
        } else {
            val id = marker.detection?.id ?: return
            if (on) Markers.outlinedNearby.add(id) else Markers.outlinedNearby.remove(id)
        }
        say(if (on) "Showing the outline of ${marker.name}" else "Hid the outline of ${marker.name}")
    }

    private fun markDiscovered(marker: Marker) {
        val detection = marker.detection ?: return
        if (!StructureStore.isOpen) return say("Not in a world")
        val structure = Structure(UUID.randomUUID().toString(), marker.type, marker.dimension, marker.box, System.currentTimeMillis(), detection.variant)
        StructureStore.put(structure)
        detection.storedId = structure.id
        Log.info("Marked {} #{} as discovered by hand", marker.type.id, detection.id)
        say("Marked ${marker.name} as discovered")
    }

    private fun confirmDelete(parent: Screen?, structure: Structure) {
        open(ConfirmScreen(
            { yes ->
                if (yes) {
                    StructureStore.remove(structure.id)
                    // Not discovered again this session, even though it is still there.
                    Tracker.detections.filter { it.storedId == structure.id }.forEach { it.storedId = DELETED }
                    Log.info("Deleted {} {}", structure.type.id, structure.id)
                    say("Deleted ${structure.name}")
                }
                open(parent)
            },
            Component.literal("Delete this ${structure.name}?"),
            Component.literal(
                "At ${structure.box.centreX} ${structure.waypointY} ${structure.box.centreZ}. It will be discovered again " +
                    "if you go inside it in a later session. This cannot be undone."
            ),
        ))
    }

    /** Marks a recognised structure deleted this session, so it is not discovered again straight away. */
    const val DELETED = "deleted"

    fun open(screen: Screen?) {
        Minecraft.getInstance().gui.setScreen(screen)
    }

    /** A message on the action bar, which only this client sees. */
    fun say(message: String) {
        Minecraft.getInstance().player?.sendOverlayMessage(Component.literal(message))
    }

    fun option(name: String, index: Int, target: IRightClickableElement, action: (Screen) -> Unit) =
        object : RightClickOption(name, index, target) {
            override fun onAction(screen: Screen) = action(screen)
        }
}
