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

@Mixin(value = CraftingBlockEntity.class, priority = 1100)
public abstract class CraftingBlockEntityPersistenceMixin {

    @Unique
    private static final boolean GTLCORE_DEBUG_IGNORE_MISSING = true;

    @Shadow(remap = false)
    private boolean isCoreBlock;

    @Shadow(remap = false)
    private CraftingCPUCluster cluster;

    /**
     * 缓存最近一次“核心块且含 CPU 状态数据”的快照。
     * 用于兜底关服/卸载阶段出现的 core=true 但 cluster 数据缺失场景。
     */
    @Unique
    private CompoundTag gtlcore$lastCpuState;

    @Unique
    private String gtlcore$beId() {
        return Integer.toHexString(System.identityHashCode(this));
    }

    @Unique
    private void gtlcore$debug(String format, Object... args) {
        if (!GTLCORE_DEBUG_IGNORE_MISSING) return;
        String message = String.format(format, args);
        AELog.info("[GTLCore][IgnoreMissing][CraftingBE %s] %s", gtlcore$beId(), message);
    }

    @Unique
    private static boolean gtlcore$hasCpuData(CompoundTag data) {
        return data.contains("inventory", Tag.TAG_LIST) || data.contains("job", Tag.TAG_COMPOUND) || data.contains("gtlcore$waitingForMissing", Tag.TAG_LIST) || data.getBoolean("gtlcore$ignoreMissingMode");
    }

    @Unique
    private static CompoundTag gtlcore$extractCpuState(CompoundTag data) {
        var out = new CompoundTag();
        if (data.contains("inventory", Tag.TAG_LIST)) {
            out.put("inventory", data.getList("inventory", Tag.TAG_COMPOUND).copy());
        }
        if (data.contains("job", Tag.TAG_COMPOUND)) {
            out.put("job", data.getCompound("job").copy());
        }
        if (data.contains("gtlcore$waitingForMissing", Tag.TAG_LIST)) {
            out.put("gtlcore$waitingForMissing", data.getList("gtlcore$waitingForMissing", Tag.TAG_COMPOUND).copy());
        }
        if (data.contains("gtlcore$ignoreMissingMode", Tag.TAG_BYTE)) {
            out.putBoolean("gtlcore$ignoreMissingMode", data.getBoolean("gtlcore$ignoreMissingMode"));
        }
        return out;
    }

    @Unique
    private static void gtlcore$restoreCpuState(CompoundTag target, CompoundTag cache) {
        if (cache.contains("inventory", Tag.TAG_LIST)) {
            target.put("inventory", cache.getList("inventory", Tag.TAG_COMPOUND).copy());
        }
        if (cache.contains("job", Tag.TAG_COMPOUND)) {
            target.put("job", cache.getCompound("job").copy());
        }
        if (cache.contains("gtlcore$waitingForMissing", Tag.TAG_LIST)) {
            target.put("gtlcore$waitingForMissing", cache.getList("gtlcore$waitingForMissing", Tag.TAG_COMPOUND).copy());
        }
        if (cache.contains("gtlcore$ignoreMissingMode", Tag.TAG_BYTE)) {
            target.putBoolean("gtlcore$ignoreMissingMode", cache.getBoolean("gtlcore$ignoreMissingMode"));
        }
    }

    @Inject(method = { "saveAdditional", "m_183515_" }, at = @At("HEAD"), remap = false, require = 0)
    private void gtlcore$debugSaveHead(CompoundTag data, CallbackInfo ci) {
        gtlcore$debug(
                "save(HEAD) core=%s cluster=%s hasJobTag=%s hasInventoryTag=%s hasWaitingTag=%s ignoreMode=%s",
                this.isCoreBlock,
                this.cluster != null,
                data.contains("job", Tag.TAG_COMPOUND),
                data.contains("inventory", Tag.TAG_LIST),
                data.contains("gtlcore$waitingForMissing", Tag.TAG_LIST),
                data.getBoolean("gtlcore$ignoreMissingMode"));
    }

    @Inject(method = { "saveAdditional", "m_183515_" }, at = @At("TAIL"), remap = false, require = 0)
    private void gtlcore$saveFallback(CompoundTag data, CallbackInfo ci) {
        if (!this.isCoreBlock) {
            return;
        }

        boolean hasCpuData = gtlcore$hasCpuData(data);
        if (hasCpuData) {
            this.gtlcore$lastCpuState = gtlcore$extractCpuState(data);
            gtlcore$debug(
                    "save(TAIL) capture core=true cluster=%s hasJobTag=%s hasInventoryTag=%s hasWaitingTag=%s ignoreMode=%s",
                    this.cluster != null,
                    data.contains("job", Tag.TAG_COMPOUND),
                    data.contains("inventory", Tag.TAG_LIST),
                    data.contains("gtlcore$waitingForMissing", Tag.TAG_LIST),
                    data.getBoolean("gtlcore$ignoreMissingMode"));
            return;
        }

        if (this.gtlcore$lastCpuState != null && !this.gtlcore$lastCpuState.isEmpty()) {
            gtlcore$restoreCpuState(data, this.gtlcore$lastCpuState);
            gtlcore$debug(
                    "save(TAIL) fallback restore applied cluster=%s hasJobTag=%s hasInventoryTag=%s hasWaitingTag=%s ignoreMode=%s",
                    this.cluster != null,
                    data.contains("job", Tag.TAG_COMPOUND),
                    data.contains("inventory", Tag.TAG_LIST),
                    data.contains("gtlcore$waitingForMissing", Tag.TAG_LIST),
                    data.getBoolean("gtlcore$ignoreMissingMode"));
        } else {
            gtlcore$debug("save(TAIL) no cpu data and no fallback cache cluster=%s", this.cluster != null);
        }
    }

    @Inject(method = "loadTag", at = @At("HEAD"), remap = false, require = 0)
    private void gtlcore$debugLoadHead(CompoundTag data, CallbackInfo ci) {
        gtlcore$debug(
                "load(HEAD) dataCore=%s hasJobTag=%s hasInventoryTag=%s hasWaitingTag=%s ignoreMode=%s",
                data.getBoolean("core"),
                data.contains("job", Tag.TAG_COMPOUND),
                data.contains("inventory", Tag.TAG_LIST),
                data.contains("gtlcore$waitingForMissing", Tag.TAG_LIST),
                data.getBoolean("gtlcore$ignoreMissingMode"));
    }

    @Inject(method = "loadTag", at = @At("TAIL"), remap = false, require = 0)
    private void gtlcore$captureAfterLoad(CompoundTag data, CallbackInfo ci) {
        if (this.isCoreBlock && gtlcore$hasCpuData(data)) {
            this.gtlcore$lastCpuState = gtlcore$extractCpuState(data);
        }
        gtlcore$debug(
                "load(TAIL) core=%s cluster=%s hasJobTag=%s hasInventoryTag=%s hasWaitingTag=%s ignoreMode=%s",
                this.isCoreBlock,
                this.cluster != null,
                data.contains("job", Tag.TAG_COMPOUND),
                data.contains("inventory", Tag.TAG_LIST),
                data.contains("gtlcore$waitingForMissing", Tag.TAG_LIST),
                data.getBoolean("gtlcore$ignoreMissingMode"));
    }
}
