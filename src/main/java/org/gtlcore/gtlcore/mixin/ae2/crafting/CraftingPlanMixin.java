package org.gtlcore.gtlcore.mixin.ae2.crafting;

import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingPlanIgnoreMissingMode;

import appeng.crafting.CraftingPlan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 为 CraftingPlan (record) 添加 ignoreMissing 标记。
 * 当标记为 true 时，{@code simulation()} 返回 false，
 * 从而绕过 CraftConfirmMenu.startJob() 和 CraftingService.submitJob() 中的检查。
 */
@Mixin(CraftingPlan.class)
public class CraftingPlanMixin implements ICraftingPlanIgnoreMissingMode {

    @Unique
    private boolean gtlcore$ignoreMissing = false;

    @Override
    public boolean gtlcore$isIgnoreMissing() {
        return gtlcore$ignoreMissing;
    }

    @Override
    public void gtlcore$setIgnoreMissing(boolean value) {
        gtlcore$ignoreMissing = value;
    }

    @Inject(method = "simulation", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtlcore$overrideSimulation(CallbackInfoReturnable<Boolean> cir) {
        if (gtlcore$ignoreMissing && cir.getReturnValueZ()) {
            cir.setReturnValue(false);
        }
    }
}
