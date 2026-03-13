package org.gtlcore.gtlcore.mixin.ae2.logic;

import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftingCPUCluster.class, priority = 1100)
public abstract class CraftingCPUClusterStorageClampMixin {

    @Shadow(remap = false)
    private long storage;

    @Inject(method = "addBlockEntity", at = @At("TAIL"), remap = false)
    private void gtlcore$clampStorageOnOverflow(CraftingBlockEntity te, CallbackInfo ci) {
        // 多个大容量单元叠加时，若 long 溢出为负数，钳制到 Long.MAX_VALUE。
        if (this.storage < 0L) {
            this.storage = Long.MAX_VALUE;
        }
    }
}
