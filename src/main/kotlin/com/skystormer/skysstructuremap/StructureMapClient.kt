package com.skystormer.skysstructuremap

import com.skystormer.skysstructuremap.gui.Legend
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
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

    /** Shows or hides the spawn boxes in the world. Unbound until you pick a key in Controls. */
    private val spawnBoxesKey = KeyMapping(
        "key.skysstructuremap.spawn_boxes",
        InputConstants.Type.KEYSYM,
        InputConstants.UNKNOWN.value,
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("skysstructuremap", "structures")),
    )

    override fun onInitializeClient() {
        Config.load()
        KeyMappingHelper.registerKeyMapping(spawnBoxesKey)
        watchWorlds()
        watchChat()
        ClientChunkEvents.CHUNK_LOAD.register { level, chunk ->
            ChunkScanner.scan(level, chunk)
            BobbyCoverage.chunkLoaded(level.dimension().identifier().toString(), chunk.pos.x, chunk.pos.z)
        }
        addLegendToTheMap()
        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
    }

    /** Opens the file of discovered structures for the world you join, and lets go of it after. */
    private fun watchWorlds() {
        ClientPlayConnectionEvents.JOIN.register { _, _, client -> client.execute { StructureStore.open(client) } }
        ClientPlayConnectionEvents.DISCONNECT.register { _, client ->
            client.execute {
                BobbyScan.stop(quietly = true)
                BobbyCoverage.clear()
                FortressLabelling.clear()
                StructureStore.close()
                StructureShare.clear()
                Tracker.clear()
            }
        }
    }

    /** A structure shared in chat becomes a message with an add button; everything else is untouched. */
    private fun watchChat() {
        ClientReceiveMessageEvents.ALLOW_CHAT.register { message, _, _, _, _ -> StructureShare.onChat(message.string) }
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ -> StructureShare.onChat(message.string) }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal(StructureShare.COMMAND)
                    // Named first: a word that is one of these is never read as a structure code.
                    .then(ClientCommands.literal("bobby")
                        .executes {
                            BobbyScan.start()
                            1
                        }
                        .then(ClientCommands.literal("stop").executes {
                            BobbyScan.stop()
                            1
                        })
                        .apply {
                            // Only with Bobby installed: it shows Bobby's saved chunks as you play.
                            if (BobbyCoverage.available) then(ClientCommands.literal("overlay").executes {
                                BobbyCoverage.toggle()
                                1
                            })
                        })
                    .then(ClientCommands.argument("code", StringArgumentType.word()).executes { context ->
                        StructureShare.accept(StringArgumentType.getString(context, "code"))
                        1
                    })
            )
        }
    }

    private fun addLegendToTheMap() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen.javaClass.name == "xaero.map.gui.GuiMap") {
                try {
                    Legend.addTo(screen)
                    BobbyCoverage.addTo(screen)
                } catch (e: Throwable) {
                    Log.error("Could not add the legend to Xaero's world map", e)
                }
            }
        }
    }

    private fun tick(client: Minecraft) {
        addToXaero()
        StructureShare.tick()
        SpawnBoxes.tick(client)
        BobbyCoverage.tick(client)
        while (spawnBoxesKey.consumeClick()) {
            Config.spawnBoxesInWorld = !Config.spawnBoxesInWorld
            Config.save()
            Menus.say(if (Config.spawnBoxesInWorld) "Spawn boxes shown in the world" else "Spawn boxes hidden in the world")
        }
        try {
            Tracker.tick(client)
        } catch (e: Throwable) {
            Log.error("Structure tracking failed this tick", e)
        }
    }

    /**
     * Xaero's map and minimap are only there to be added to once they have started, so this tries
     * each tick until it takes. A failure is logged once and never tried again.
     */
    private fun addToXaero() {
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
    }
}
