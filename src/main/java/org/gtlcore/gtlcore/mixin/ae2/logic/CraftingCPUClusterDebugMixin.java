package org.gtlcore.gtlcore.mixin.ae2.logic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.core.AELog;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftingCPUCluster.class, priority = 1100)
public class CraftingCPUClusterDebugMixin {

    @Unique
    private static final boolean GTLCORE_DEBUG_IGNORE_MISSING = true;

    @Shadow(remap = false)
    private CraftingBlockEntity getCore() {
        throw new AssertionError();
    }

    @Unique
    private String gtlcore$clusterId() {
        return Integer.toHexString(System.identityHashCode(this));
    }

    @Unique
    private void gtlcore$debug(String format, Object... args) {
        if (!GTLCORE_DEBUG_IGNORE_MISSING) return;
        String message = String.format(format, args);
        AELog.info("[GTLCore][IgnoreMissing][Cluster %s] %s", gtlcore$clusterId(), message);
    }

    @Unique
    private static int gtlcore$listSize(CompoundTag data, String key) {
        if (!data.contains(key, Tag.TAG_LIST)) return -1;
        return data.getList(key, Tag.TAG_COMPOUND).size();
    }

    @Inject(method = "writeToNBT", at = @At("TAIL"), remap = false, require = 0)
    private void gtlcore$debugWriteToNBT(CompoundTag data, CallbackInfo ci) {
        var core = this.getCore();
        gtlcore$debug(
                "writeToNBT core=%s hasJobTag=%s inventorySize=%d waitingMissingSize=%d ignoreMode=%s",
                core != null ? core.getBlockPos() : "null",
                data.contains("job", Tag.TAG_COMPOUND),
                gtlcore$listSize(data, "inventory"),
                gtlcore$listSize(data, "gtlcore$waitingForMissing"),
                data.getBoolean("gtlcore$ignoreMissingMode"));
    }

    @Inject(method = "readFromNBT", at = @At("HEAD"), remap = false, require = 0)
    private void gtlcore$debugReadFromNBTHead(CompoundTag data, CallbackInfo ci) {
        var core = this.getCore();
        gtlcore$debug(
                "readFromNBT(HEAD) core=%s hasJobTag=%s inventorySize=%d waitingMissingSize=%d ignoreMode=%s",
                core != null ? core.getBlockPos() : "null",
                data.contains("job", Tag.TAG_COMPOUND),
                gtlcore$listSize(data, "inventory"),
                gtlcore$listSize(data, "gtlcore$waitingForMissing"),
                data.getBoolean("gtlcore$ignoreMissingMode"));
    }

    @Inject(method = "readFromNBT", at = @At("TAIL"), remap = false, require = 0)
    private void gtlcore$debugReadFromNBTTail(CompoundTag data, CallbackInfo ci) {
        var core = this.getCore();
        gtlcore$debug(
                "readFromNBT(TAIL) core=%s hasJobTag=%s inventorySize=%d waitingMissingSize=%d ignoreMode=%s",
                core != null ? core.getBlockPos() : "null",
                data.contains("job", Tag.TAG_COMPOUND),
                gtlcore$listSize(data, "inventory"),
                gtlcore$listSize(data, "gtlcore$waitingForMissing"),
                data.getBoolean("gtlcore$ignoreMissingMode"));
    }

    @Inject(method = "addBlockEntity", at = @At("HEAD"), remap = false, require = 0)
    private void gtlcore$debugAddBlockEntityHead(CraftingBlockEntity te, CallbackInfo ci) {
        gtlcore$debug(
                "addBlockEntity(HEAD) te=%s teCore=%s",
                te.getBlockPos(),
                te.isCoreBlock());
    }

    @Inject(method = "addBlockEntity", at = @At("TAIL"), remap = false, require = 0)
    private void gtlcore$debugAddBlockEntityTail(CraftingBlockEntity te, CallbackInfo ci) {
        var core = this.getCore();
        gtlcore$debug(
                "addBlockEntity(TAIL) te=%s coreNow=%s",
                te.getBlockPos(),
                core != null ? core.getBlockPos() : "null");
    }

    @Inject(method = "done", at = @At("TAIL"), remap = false, require = 0)
    private void gtlcore$debugDoneTail(CallbackInfo ci) {
        var core = this.getCore();
        gtlcore$debug("done(TAIL) core=%s", core != null ? core.getBlockPos() : "null");
    }
}
