package com.skystormer.skysstructuremap

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import xaero.common.minimap.waypoints.Waypoint
import xaero.hud.minimap.BuiltInHudModules
import xaero.hud.minimap.waypoint.WaypointColor

/**
 * Turns a structure into an ordinary, permanent Xaero's Minimap waypoint — the same as one made
 * by hand, in the waypoint set currently selected — and saves it. Answers on the action bar.
 */
object Waypoints {

    fun available(): Boolean = FabricLoader.getInstance().isModLoaded("xaerominimap")

    fun save(marker: Marker) {
        Menus.say(
            try {
                add(marker)
            } catch (e: Throwable) {
                Log.error("Could not save ${marker.name} as a waypoint", e)
                "Could not save the waypoint: ${e.message ?: e.javaClass.simpleName}"
            }
        )
    }

    private fun add(marker: Marker): String {
        if (!available()) return "Making waypoints needs Xaero's Minimap"
        val minecraft = Minecraft.getInstance()
        if (minecraft.level?.dimension()?.identifier()?.toString() != marker.dimension) {
            return "Go to the ${dimensionName(marker.dimension)} first: waypoints are saved to the dimension you are in"
        }
        val session = BuiltInHudModules.MINIMAP.currentSession ?: return "Xaero's Minimap is not running"
        val world = session.worldManager.currentWorld ?: return "Xaero's Minimap has no waypoint world open"
        val set = world.currentWaypointSet ?: return "Xaero's Minimap has no waypoint set selected"
        val x = marker.box.centreX
        val y = marker.y
        val z = marker.box.centreZ
        if (set.waypoints.any { it.x == x && it.z == z && it.name == marker.name }) return "${marker.name} is already a waypoint"
        set.add(Waypoint(x, y, z, marker.name, marker.type.initials, colourOf(marker.type)))
        session.worldManagerIO.saveWorld(world)
        Log.info("Saved {} at {} {} {} as a waypoint", marker.name, x, y, z)
        return "Saved ${marker.name} as a waypoint"
    }

    private fun colourOf(type: StructureType): WaypointColor = when (type) {
        StructureType.VILLAGE -> WaypointColor.GREEN
        StructureType.OUTPOST -> WaypointColor.GRAY
        StructureType.MANSION -> WaypointColor.BROWN
        StructureType.STRONGHOLD -> WaypointColor.DARK_GREEN
        StructureType.WITCH_HUT -> WaypointColor.DARK_PURPLE
        StructureType.JUNGLE_TEMPLE -> WaypointColor.DARK_GREEN
        StructureType.DESERT_TEMPLE -> WaypointColor.YELLOW
        StructureType.TRAIL_RUINS -> WaypointColor.GOLD
        StructureType.ANCIENT_CITY -> WaypointColor.DARK_AQUA
        StructureType.TRIAL_CHAMBERS -> WaypointColor.GOLD
        StructureType.MONUMENT -> WaypointColor.AQUA
        StructureType.SHIPWRECK -> WaypointColor.GOLD
        StructureType.FORTRESS -> WaypointColor.RED
        StructureType.BASTION -> WaypointColor.YELLOW
        StructureType.END_CITY -> WaypointColor.MAGENTA
        StructureType.END_GATEWAY -> WaypointColor.LIGHT_BLUE
    }
}
