package com.skystormer.skysstructuremap.mixin.minimap;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.skystormer.skysstructuremap.BobbyCoverage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.common.minimap.render.MinimapRendererHelper;
import xaero.hud.minimap.module.MinimapSession;
import xaero.map.MapProcessor;

/**
 * Draws Bobby's saved chunks on Xaero's minimap when it draws its terrain from Xaero's World Map
 * (whenever both are installed), into the overlay buffer it draws over the terrain. The same spot
 * Sky's Map Shapes draws its shapes.
 *
 * In its own mixin config, marked not required, so the mod still loads without Xaero's Minimap.
 */
@Mixin(targets = "xaero.common.mods.SupportXaeroWorldmap", remap = false)
public abstract class SupportXaeroWorldmapMixin {

    @Inject(
            method = "drawMinimap",
            at = @At(
                    value = "INVOKE",
                    target = "Lxaero/common/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;draw(Lxaero/common/graphics/renderer/multitexture/MultiTextureRenderTypeRenderer;)V",
                    ordinal = 1,
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void skysstructuremap$drawBobbyCoverage(
            MinimapSession minimapSession, PoseStack matrixStack, MinimapRendererHelper helper,
            int xFloored, int zFloored, int minViewX, int minViewZ, int maxViewX, int maxViewZ,
            boolean zooming, double zoom, double mapDimensionScale,
            VertexConsumer overlayBufferBuilder, MultiTextureRenderTypeRendererProvider multiTextureRenderTypeRenderers,
            CallbackInfo ci,
            @Local(name = "mapProcessor") MapProcessor mapProcessor
    ) {
        String dimension = mapProcessor.getMapWorld() == null || mapProcessor.getMapWorld().getCurrentDimension() == null
                ? null : mapProcessor.getMapWorld().getCurrentDimension().getDimId().identifier().toString();
        BobbyCoverage.drawMinimap(dimension, matrixStack.last().pose(), xFloored, zFloored, overlayBufferBuilder, true);
    }
}
