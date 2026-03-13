package org.gtlcore.gtlcore.mixin.ae2;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.helpers.externalstorage.GenericStackInv;
import com.google.common.base.Preconditions;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

@Mixin(GenericStackInv.class)
public abstract class MixinGenericStackInv {

    @Shadow(remap = false)
    public abstract long getCapacity(AEKeyType space);

    @Shadow(remap = false)
    public abstract boolean canInsert();

    @Shadow(remap = false)
    public abstract boolean isAllowed(AEKey what);

    @Shadow(remap = false)
    public abstract AEKey getKey(int slot);

    @Shadow(remap = false)
    public abstract long getAmount(int slot);

    @Shadow(remap = false)
    public abstract void setStack(int slot, GenericStack stack);

    @Shadow(remap = false)
    public abstract long getMaxAmount(AEKey key);

    @Inject(method = "getMaxAmount", at = @At("HEAD"), cancellable = true, remap = false)
    private void getMaxAmountInj(AEKey key, CallbackInfoReturnable<Long> cir) {
        cir.setReturnValue(gtceu$getMaxAmount(key, (GenericStackInv) (Object) this));
    }

    @Inject(
            method = "insert(ILappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void gtlcore$insertSafe(int slot, AEKey what, long amount, Actionable mode, CallbackInfoReturnable<Long> cir) {
        Objects.requireNonNull(what, "what");
        Preconditions.checkArgument(amount >= 0, "amount >= 0");

        if (!this.canInsert() || !this.isAllowed(what)) {
            cir.setReturnValue(0L);
            return;
        }

        var key = this.getKey(slot);
        var currentAmount = this.getAmount(slot);
        if (key != null && !key.equals(what)) {
            cir.setReturnValue(0L);
            return;
        }

        var merged = gtlcore$saturatedAdd(currentAmount, amount);
        var insertedAmount = Math.min(merged, this.getMaxAmount(what));
        if (insertedAmount <= currentAmount) {
            cir.setReturnValue(0L);
            return;
        }

        if (mode == Actionable.MODULATE) {
            this.setStack(slot, new GenericStack(what, insertedAmount));
            insertedAmount = this.getAmount(slot);
        }
        cir.setReturnValue(Math.max(0L, insertedAmount - currentAmount));
    }

    @Unique
    private long gtceu$getMaxAmount(AEKey key, @NotNull GenericStackInv inv) {
        if (key instanceof AEItemKey) {
            return Long.MAX_VALUE;
        } else {
            return inv.getCapacity(key.getType());
        }
    }

    @Unique
    private static long gtlcore$saturatedAdd(long left, long right) {
        if (right <= 0) return left;
        if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
