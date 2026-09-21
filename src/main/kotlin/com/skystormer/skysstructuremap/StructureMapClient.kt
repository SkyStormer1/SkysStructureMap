package com.skystormer.skysstructuremap

import com.skystormer.skysstructuremap.gui.Legend
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.loader.api.FabricLoader

object StructureMapClient : ClientModInitializer {

    private var markersAdded = false
    private var minimapMarkersAdded = false
    private var hookChecked = false

    override fun onInitializeClient() {
        Config.load()

        ClientPlayConnectionEvents.JOIN.register { _, _, client -> client.execute { StructureStore.open(client) } }
        ClientPlayConnectionEvents.DISCONNECT.register { _, client ->
            client.execute {
                StructureStore.close()
                Tracker.clear()
            }
        }

        // A structure shared in chat becomes a message with an add button; everything else is untouched.
        ClientReceiveMessageEvents.ALLOW_CHAT.register { message, _, _, _, _ -> StructureShare.onChat(message.string) }
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ -> StructureShare.onChat(message.string) }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal(StructureShare.COMMAND).then(
                    ClientCommands.argument("code", StringArgumentType.word()).executes { context ->
                        StructureShare.accept(StringArgumentType.getString(context, "code"))
                        1
                    }
                )
            )
        }

        ClientChunkEvents.CHUNK_LOAD.register { level, chunk -> ChunkScanner.scan(level, chunk) }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen.javaClass.name == "xaero.map.gui.GuiMap") {
                try {
                    Legend.addTo(screen)
                } catch (e: Throwable) {
                    Log.error("Could not add the legend to Xaero's world map", e)
                }
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!hookChecked) {
                hookChecked = true
                val hooked = try {
                    Class.forName("xaero.map.gui.GuiMap").declaredMethods.any { it.name.contains("drawOutlines") }
                } catch (e: Throwable) {
                    false
                }
                if (hooked) Log.info("Xaero hook installed") else Log.warn("This version of Xaero's World Map is not supported; outlines will not be drawn")
            }
            if (!markersAdded) {
                markersAdded = try {
                    Markers.register().also { if (it) Log.info("Markers added to Xaero's world map") }
                } catch (e: Throwable) {
                    Log.error("Could not add structure markers to Xaero's world map", e)
                    true
                }
            }
            if (!minimapMarkersAdded) {
                minimapMarkersAdded = if (!FabricLoader.getInstance().isModLoaded("xaerominimap")) true else try {
                    MinimapMarkers.register().also { if (it) Log.info("Markers added to Xaero's minimap") }
                } catch (e: Throwable) {
                    Log.error("Could not add structure markers to Xaero's minimap", e)
                    true
                }
            }
            try {
                Tracker.tick(client)
            } catch (e: Throwable) {
                Log.error("Structure tracking failed this tick", e)
            }
        }
    }
}
