package com.skystormer.skysstructuremap

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.network.chat.ClickEvent
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import xaero.map.gui.IRightClickableElement
import xaero.map.gui.dropdown.rightclick.RightClickOption
import java.util.UUID

/** The right-click menu on a structure's icon on Xaero's world map. */
object Menus {

    fun addMarkerOptions(options: ArrayList<RightClickOption>, target: IRightClickableElement, marker: Marker) {
        try {
            options.add(option("Make waypoint", options.size, target) { Waypoints.save(marker) }.setActive(Waypoints.available()))
            options.add(option("Copy coordinates", options.size, target) { copy(marker) })
            options.add(option("Share…", options.size, target) { parent -> open(com.skystormer.skysstructuremap.gui.ShareScreen(parent, marker)) })
            // While outlines are on for everything, this one's is already showing.
            if (!Config.outlines) {
                options.add(option(if (marker.outlined) "Hide outline" else "Show outline", options.size, target) { toggleOutline(marker) })
            }
            val structure = marker.structure
            if (structure != null) {
                options.add(option(if (structure.completed) "Mark as not completed" else "Mark as completed", options.size, target) { toggleCompleted(structure) })
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

    /** Marks a structure as completed, with a tick beside its icon, or takes that back. */
    private fun toggleCompleted(structure: Structure) {
        val current = StructureStore.byId(structure.id) ?: return
        val done = !current.completed
        StructureStore.put(current.copy(completed = done))
        say(if (done) "Marked the ${structure.name} as completed" else "Marked the ${structure.name} as not completed")
    }

    private fun markDiscovered(marker: Marker) {
        val detection = marker.detection ?: return
        if (!StructureStore.isOpen) return say("Not in a world")
        val structure = Structure(UUID.randomUUID().toString(), marker.type, marker.dimension, marker.box, System.currentTimeMillis(), detection.variant, pieces = detection.pieces)
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
                    sayWithUndo(structure)
                }
                open(parent)
            },
            Component.literal("Delete this ${structure.name}?"),
            Component.literal(
                "At ${structure.box.centreX} ${structure.waypointY} ${structure.box.centreZ}. It stays deleted: it will not come back " +
                    "when you return, unless someone shares it with you and you add it."
            ),
        ))
    }

    /** Marks a recognised structure deleted this session, so it is not discovered again straight away. */
    const val DELETED = "deleted"

    fun open(screen: Screen?) {
        Minecraft.getInstance().gui.setScreen(screen)
    }

    /** A message on the action bar, which only this client sees. */
    /** Says what was deleted, with a button to put it back, for a delete by accident. */
    private fun sayWithUndo(structure: Structure) {
        Minecraft.getInstance().player?.sendSystemMessage(
            Component.literal("Deleted ${structure.name}  ").withStyle { it.withColor(0xAAAAAA) }.append(
                Component.literal("[Undo]").withStyle {
                    it.withColor(structure.type.colour and 0xFFFFFF).withBold(true)
                        .withClickEvent(ClickEvent.RunCommand("/${StructureShare.COMMAND} ${UNDO}"))
                }
            )
        )
    }

    /** What the undo button runs, and what [undo] answers to. */
    const val UNDO = "undo"

    /** Puts the last deleted structure back, from the button or the command. */
    fun undo() {
        val back = StructureStore.undoDelete()
        if (back == null) {
            say("Nothing has been deleted to bring back")
            return
        }
        // It may still be in view: that sighting is the one that came back, not a new one.
        Tracker.detections.filter { it.storedId == DELETED && it.type == back.type && it.box?.overlaps(back.box) == true }
            .forEach { it.storedId = back.id }
        Log.info("Brought back {} {}", back.type.id, back.id)
        say("Brought back the ${back.name}")
    }

    /** A line above the hotbar: for small confirmations while you are looking at the map. */
    fun say(message: String) {
        Minecraft.getInstance().player?.sendOverlayMessage(Component.literal(message))
    }

    /** A line in the chat, which stays there: for anything worth reading after the screen closes. */
    fun tell(message: String) {
        Minecraft.getInstance().player?.sendSystemMessage(Component.literal(message).withStyle { it.withColor(0xAAAAAA) })
    }

    fun option(name: String, index: Int, target: IRightClickableElement, action: (Screen) -> Unit) =
        object : RightClickOption(name, index, target) {
            override fun onAction(screen: Screen) = action(screen)
        }
}
