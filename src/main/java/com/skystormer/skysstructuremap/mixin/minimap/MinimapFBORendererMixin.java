package com.skystormer.skysstructuremap.mixin.minimap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.skystormer.skysstructuremap.BobbyCoverage;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.common.graphics.CustomRenderTypes;
import xaero.common.minimap.MinimapProcessor;
import xaero.hud.minimap.module.MinimapSession;
import xaero.lib.client.graphics.XaeroBufferProvider;

/**
 * Draws Bobby's saved chunks on Xaero's minimap however it drew its terrain: from the World Map's
 * data ({@code SupportXaeroWorldmapMixin}) or from its own, as it does underground in cave mode.
 * This sits where the two meet, just before Xaero draws its chunk grid, and draws only when the
 * other did not this frame. The same spot Sky's Map Shapes uses.
 */
@Mixin(targets = "xaero.common.minimap.render.MinimapFBORenderer", remap = false)
public abstract class MinimapFBORendererMixin {

    @Inject(
            method = "renderChunksToFBO",
            at = @At(
                    value = "FIELD",
                    target = "Lxaero/hud/minimap/common/config/option/MinimapProfiledConfigOptions;CHUNK_GRID:Lxaero/lib/common/config/option/RangeConfigOption;",
                    opcode = org.objectweb.asm.Opcodes.GETSTATIC
            ),
            require = 0
    )
    private void skysstructuremap$drawBobbyCoverage(
            MinimapSession session, PoseStack matrixStack, MinimapProcessor minimap, Vec3 renderPos,
            ResourceKey<Level> mapDimension, double zoom, int size, float sunBrightness, int level,
            boolean usingWorldMap, boolean rotate, int caveLayer, double playerX, double playerZ,
            boolean cave, XaeroBufferProvider buffers, CallbackInfo ci
    ) {
        if (!BobbyCoverage.minimapStillToDraw()) return;
        BobbyCoverage.drawMinimap(
                mapDimension == null ? null : mapDimension.identifier().toString(), matrixStack.last().pose(),
                (int) Math.floor(renderPos.x), (int) Math.floor(renderPos.z),
                buffers.getBuffer(CustomRenderTypes.MAP_CHUNK_OVERLAY), false
        );
    }
}
