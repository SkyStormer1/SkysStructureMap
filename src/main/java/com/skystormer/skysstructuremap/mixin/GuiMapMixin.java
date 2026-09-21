package com.skystormer.skysstructuremap.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.skystormer.skysstructuremap.Outlines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.skystormer.skysstructuremap.gui.Legend;
import xaero.map.MapProcessor;

/**
 * Draws structure outlines on Xaero's world map, straight after Xaero has drawn its terrain (the
 * second flush of its terrain renderers), into Xaero's colour overlay buffer. Sky's Map Shapes and
 * Sky's Map Exposer hook the same spot; all three only add to the frame.
 *
 * Locals are taken by name, which Xaero's jar keeps. Not required, so an unsupported Xaero version
 * costs the outlines, not the game.
 */
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class GuiMapMixin {

    @Shadow private MapProcessor mapProcessor;

    @Inject(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;draw(Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRenderer;)V",
                    ordinal = 1,
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void skysstructuremap$drawOutlines(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci,
            @Local(name = "matrix") Matrix4f matrix,
            @Local(name = "flooredCameraX") int flooredCameraX,
            @Local(name = "flooredCameraZ") int flooredCameraZ
    ) {
        Outlines.drawWorldMap(mapProcessor, matrix, flooredCameraX, flooredCameraZ);
    }

    /**
     * Xaero zooms the map on the mouse wheel before any widget hears of it, so a wheel over the
     * legend is handed to the legend first, to scroll it instead.
     */
    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true, require = 0)
    private void skysstructuremap$scrollLegend(double mouseX, double mouseY, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
        if (Legend.scrolled(mouseX, mouseY, scrollY)) cir.setReturnValue(true);
    }
}
