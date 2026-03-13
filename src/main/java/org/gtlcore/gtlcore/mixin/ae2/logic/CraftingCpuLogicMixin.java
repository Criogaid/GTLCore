package org.gtlcore.gtlcore.mixin.ae2.logic;

import org.gtlcore.gtlcore.api.machine.trait.AECraft.IMECraftIOPart;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternPartMachine;
import org.gtlcore.gtlcore.integration.ae2.AEUtils;
import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingPlanIgnoreMissingMode;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.core.AELog;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CraftingCpuLogic.class, priority = 1100)
public abstract class CraftingCpuLogicMixin {

    @Shadow(remap = false)
    private ExecutingCraftingJob job;
    @Shadow(remap = false)
    private boolean cantStoreItems = false;

    @Shadow(remap = false)
    @Final
    CraftingCPUCluster cluster;

    @Shadow(remap = false)
    @Final
    private ListCraftingInventory inventory;

    @Shadow(remap = false)
    @Final
    private int[] usedOps;

    @Shadow(remap = false)
    public abstract void storeItems();

    @Shadow(remap = false)
    public abstract void cancel();

    @Shadow(remap = false)
    public @Nullable abstract GenericStack getFinalJobOutput();

    @Shadow(remap = false)
    protected abstract void finishJob(boolean success);

    // ========== ignoreMissing 状态字段 ==========

    /** 记录初始提取时缺失的物品及其数量 */
    @Unique
    private KeyCounter gtlcore$waitingForMissing;

    /** 周期性拉取计数器 */
    @Unique
    private int gtlcore$missingPullTicks;

    /** 保存提交任务时的 IActionSource（用于后续网络提取权限） */
    @Unique
    private IActionSource gtlcore$jobActionSource;

    /** 保存提交任务时的 IGrid（用于后续访问网络存储） */
    @Unique
    private IGrid gtlcore$jobGrid;

    /** 当前运行任务是否处于 ignoreMissing 模式 */
    @Unique
    private boolean gtlcore$ignoreMissingMode;

    /** 周期性拉取间隔（tick） */
    @Unique
    private static final int GTLCORE_MISSING_PULL_INTERVAL = 20;

    /** 单次拉取最大处理条目数 */
    @Unique
    private static final int GTLCORE_MAX_PULL_PER_CYCLE = 8;

    /** 调试埋点开关：定位 ignoreMissing 持久化链路问题时开启。 */
    @Unique
    private static final boolean GTLCORE_DEBUG_IGNORE_MISSING = true;

    @Unique
    private int gtlcore$pullNoExtractCycles;

    // ========== 原有辅助方法 ==========

    @Unique
    private boolean core$matchOutput(GenericStack g) {
        return g != null && g.what() instanceof AEItemKey i && i.getItem() == Items.WRITTEN_BOOK &&
                (i.hasTag() && i.getTag().contains("display"));
    }

    @Unique
    private static int gtlcore$countEntries(@Nullable KeyCounter counter) {
        if (counter == null || counter.isEmpty()) return 0;
        int entries = 0;
        for (var ignored : counter) {
            entries++;
        }
        return entries;
    }

    @Unique
    private static long gtlcore$countTotal(@Nullable KeyCounter counter) {
        if (counter == null || counter.isEmpty()) return 0L;
        long total = 0L;
        for (var entry : counter) {
            total += entry.getLongValue();
        }
        return total;
    }

    @Unique
    private String gtlcore$cpuId() {
        return Integer.toHexString(System.identityHashCode(this.cluster));
    }

    @Unique
    private void gtlcore$debug(String format, Object... args) {
        if (!GTLCORE_DEBUG_IGNORE_MISSING) return;
        String message = String.format(format, args);
        AELog.info("[GTLCore][IgnoreMissing][CPU %s] %s", gtlcore$cpuId(), message);
    }

    // ========== ignoreMissing: NBT 持久化 ==========

    /**
     * 将 waitingForMissing 序列化到 NBT，对齐 AE2 ListCraftingInventory 的标准格式：
     * ListTag 中每个 CompoundTag 包含 key.toTagGeneric() 的字段 + "#" 表示数量。
     */
    @Inject(method = "writeToNBT", at = @At("TAIL"), remap = false)
    private void gtlcore$writeWaitingForMissing(CompoundTag data, CallbackInfo ci) {
        data.putBoolean("gtlcore$ignoreMissingMode", this.gtlcore$ignoreMissingMode);
        if (this.gtlcore$waitingForMissing != null && !this.gtlcore$waitingForMissing.isEmpty()) {
            var list = new ListTag();
            for (var entry : this.gtlcore$waitingForMissing) {
                var tag = entry.getKey().toTagGeneric().copy();
                tag.putLong("#", entry.getLongValue());
                list.add(tag);
            }
            data.put("gtlcore$waitingForMissing", list);
        }
        this.gtlcore$debug(
                "writeToNBT hasJob=%s hasJobTag=%s ignoreMode=%s waiting=%d/%d inventory=%d/%d",
                this.job != null,
                data.contains("job", Tag.TAG_COMPOUND),
                this.gtlcore$ignoreMissingMode,
                gtlcore$countEntries(this.gtlcore$waitingForMissing),
                gtlcore$countTotal(this.gtlcore$waitingForMissing),
                gtlcore$countEntries(this.inventory.list),
                gtlcore$countTotal(this.inventory.list));
    }

    /**
     * 从 NBT 恢复 waitingForMissing。
     * jobGrid 和 jobActionSource 为运行时引用，不序列化，
     * 由 gtlcore$tryPullMissing 中的 fallback 逻辑懒初始化。
     */
    @Inject(method = "readFromNBT", at = @At("TAIL"), remap = false)
    private void gtlcore$readWaitingForMissing(CompoundTag data, CallbackInfo ci) {
        this.gtlcore$waitingForMissing = null;
        this.gtlcore$ignoreMissingMode = data.getBoolean("gtlcore$ignoreMissingMode");
        if (data.contains("gtlcore$waitingForMissing", Tag.TAG_LIST)) {
            var list = data.getList("gtlcore$waitingForMissing", Tag.TAG_COMPOUND);
            if (!list.isEmpty()) {
                var missing = new KeyCounter();
                boolean hasInvalidEntry = false;
                for (int i = 0; i < list.size(); i++) {
                    var tag = list.getCompound(i);
                    // '#' 是数量字段，部分实现对带 '#' 的 tag 解析更严格，先移除再反序列化 key。
                    var keyTag = tag.copy();
                    keyTag.remove("#");
                    var key = AEKey.fromTagGeneric(keyTag);
                    var amount = tag.getLong("#");
                    if (key != null && amount > 0) {
                        missing.add(key, amount);
                    } else {
                        hasInvalidEntry = true;
                    }
                }
                this.gtlcore$waitingForMissing = missing.isEmpty() ? null : missing;
                if (hasInvalidEntry) {
                    AELog.warn("Ignored invalid entries while reading gtlcore$waitingForMissing.");
                }
            }
        }
        if (!this.gtlcore$ignoreMissingMode && this.gtlcore$waitingForMissing != null && !this.gtlcore$waitingForMissing.isEmpty()) {
            // 兼容旧档：旧版本未持久化模式位，但仍可能存在 waitingForMissing。
            this.gtlcore$ignoreMissingMode = true;
        }
        this.gtlcore$debug(
                "readFromNBT hasJobTag=%s loadedJob=%s ignoreMode=%s waiting=%d/%d inventory=%d/%d",
                data.contains("job", Tag.TAG_COMPOUND),
                this.job != null,
                this.gtlcore$ignoreMissingMode,
                gtlcore$countEntries(this.gtlcore$waitingForMissing),
                gtlcore$countTotal(this.gtlcore$waitingForMissing),
                gtlcore$countEntries(this.inventory.list),
                gtlcore$countTotal(this.inventory.list));
    }

    // ========== ignoreMissing: 拦截 tryExtractInitialItems ==========

    /**
     * 拦截 trySubmitJob 中对 CraftingCpuHelper.tryExtractInitialItems 的调用。
     * <p>
     * ignoreMissing 模式下：跳过原始方法（其失败时会回滚所有已提取物品到网络再清空 CPU，
     * 产生竞态损耗窗口），改为直接逐项原子提取，记录缺失物品。
     * <p>
     * 正常模式下：直接调用原始方法，行为不变。
     */
    @Redirect(
              method = "trySubmitJob",
              at = @At(value = "INVOKE",
                       target = "Lappeng/crafting/execution/CraftingCpuHelper;tryExtractInitialItems(Lappeng/api/networking/crafting/ICraftingPlan;Lappeng/api/networking/IGrid;Lappeng/crafting/inv/ListCraftingInventory;Lappeng/api/networking/security/IActionSource;)Lappeng/api/stacks/GenericStack;",
                       remap = false),
              remap = false)
    private GenericStack gtlcore$redirectTryExtractInitialItems(
                                                                ICraftingPlan plan, IGrid grid, ListCraftingInventory cpuInventory, IActionSource src) {
        // ignoreMissing 前置判断：不调用原始方法，避免回滚→重提取的竞态窗口
        if (plan instanceof ICraftingPlanIgnoreMissingMode mode && mode.gtlcore$isIgnoreMissing()) {
            var storage = grid.getStorageService().getInventory();
            var required = new KeyCounter();
            var missing = new KeyCounter();

            // GTNH 对齐思路：将“可提取需求 + 计算阶段缺失需求”合并为总需求，
            // 统一在提交时做一次原子提取，剩余部分进入 waitingForMissing 进行后续补拉。
            for (var entry : plan.usedItems()) {
                var amount = entry.getLongValue();
                if (amount > 0) {
                    required.add(entry.getKey(), amount);
                }
            }
            for (var entry : plan.missingItems()) {
                var amount = entry.getLongValue();
                if (amount > 0) {
                    required.add(entry.getKey(), amount);
                }
            }

            for (var entry : required) {
                var what = entry.getKey();
                var toExtract = entry.getLongValue();
                if (toExtract <= 0) continue;

                // 原子操作：直接从网络提取到 CPU，无中间回滚步骤
                var extracted = storage.extract(what, toExtract, Actionable.MODULATE, src);
                if (extracted > 0) {
                    cpuInventory.insert(what, extracted, Actionable.MODULATE);
                }
                if (extracted < toExtract) {
                    missing.add(what, toExtract - extracted);
                }
            }

            // 保存缺失状态用于周期性拉取
            this.gtlcore$waitingForMissing = missing.isEmpty() ? null : missing;
            this.gtlcore$jobGrid = grid;
            this.gtlcore$jobActionSource = src;
            this.gtlcore$missingPullTicks = 0;
            this.gtlcore$ignoreMissingMode = true;
            this.gtlcore$pullNoExtractCycles = 0;

            this.gtlcore$debug(
                    "submit(ignore) planSimulation=%s used=%d/%d calcMissing=%d/%d required=%d/%d initialMissing=%d/%d",
                    plan.simulation(),
                    gtlcore$countEntries(plan.usedItems()),
                    gtlcore$countTotal(plan.usedItems()),
                    gtlcore$countEntries(plan.missingItems()),
                    gtlcore$countTotal(plan.missingItems()),
                    gtlcore$countEntries(required),
                    gtlcore$countTotal(required),
                    gtlcore$countEntries(this.gtlcore$waitingForMissing),
                    gtlcore$countTotal(this.gtlcore$waitingForMissing));

            return null; // 返回 null 表示提取成功，允许 trySubmitJob 继续创建工作
        }

        if (this.gtlcore$ignoreMissingMode || (this.gtlcore$waitingForMissing != null && !this.gtlcore$waitingForMissing.isEmpty())) {
            this.gtlcore$debug(
                    "submit(normal) clear previous ignore state waiting=%d/%d",
                    gtlcore$countEntries(this.gtlcore$waitingForMissing),
                    gtlcore$countTotal(this.gtlcore$waitingForMissing));
        }
        this.gtlcore$waitingForMissing = null;
        this.gtlcore$jobGrid = null;
        this.gtlcore$jobActionSource = null;
        this.gtlcore$missingPullTicks = 0;
        this.gtlcore$ignoreMissingMode = false;
        this.gtlcore$pullNoExtractCycles = 0;

        // 正常路径：调用原始方法
        return CraftingCpuHelper.tryExtractInitialItems(plan, grid, cpuInventory, src);
    }

    // ========== ignoreMissing: 周期性从网络拉取缺失物品 ==========

    /**
     * 周期性从 ME 网络拉取缺失物品到 CPU 库存。
     * 每次最多处理 GTLCORE_MAX_PULL_PER_CYCLE 个条目，避免单 tick 开销过大。
     */
    @Unique
    private void gtlcore$tryPullMissing() {
        if (this.gtlcore$waitingForMissing == null || this.gtlcore$waitingForMissing.isEmpty()) return;

        long beforeTotal = gtlcore$countTotal(this.gtlcore$waitingForMissing);
        var grid = this.gtlcore$jobGrid;
        if (grid == null) {
            grid = this.cluster.getGrid();
        }
        if (grid == null) return;

        // 运行期补拉统一使用 CPU 机器源，避免玩家源在权限/重连场景下导致持续提取失败。
        var src = this.cluster.getSrc();

        var storage = grid.getStorageService().getInventory();
        var remaining = new KeyCounter();
        int processed = 0;
        long extractedTotal = 0L;

        for (var entry : this.gtlcore$waitingForMissing) {
            if (processed >= GTLCORE_MAX_PULL_PER_CYCLE) {
                remaining.add(entry.getKey(), entry.getLongValue());
                continue;
            }

            var what = entry.getKey();
            var needed = entry.getLongValue();
            var extracted = storage.extract(what, needed, Actionable.MODULATE, src);
            extractedTotal += extracted;

            if (extracted > 0) {
                this.inventory.insert(what, extracted, Actionable.MODULATE);
                this.cluster.markDirty();
            }

            long stillNeeded = needed - extracted;
            if (stillNeeded > 0) {
                remaining.add(what, stillNeeded);
            }

            processed++;
        }

        this.gtlcore$waitingForMissing = remaining.isEmpty() ? null : remaining;
        long afterTotal = gtlcore$countTotal(this.gtlcore$waitingForMissing);
        if (extractedTotal > 0 || afterTotal != beforeTotal) {
            this.gtlcore$pullNoExtractCycles = 0;
            this.gtlcore$debug(
                    "pullMissing extracted=%d before=%d after=%d remaining=%d/%d inventory=%d/%d",
                    extractedTotal,
                    beforeTotal,
                    afterTotal,
                    gtlcore$countEntries(this.gtlcore$waitingForMissing),
                    afterTotal,
                    gtlcore$countEntries(this.inventory.list),
                    gtlcore$countTotal(this.inventory.list));
        } else if (afterTotal > 0) {
            this.gtlcore$pullNoExtractCycles++;
            if (this.gtlcore$pullNoExtractCycles >= 20) {
                this.gtlcore$pullNoExtractCycles = 0;
                this.gtlcore$debug(
                        "pullMissing no progress for 20 cycles, remaining=%d/%d",
                        gtlcore$countEntries(this.gtlcore$waitingForMissing),
                        afterTotal);
            }
        }
    }

    // ========== tickCraftingLogic (overwrite) ==========

    /**
     * @author .
     * @reason .
     */
    @Overwrite(remap = false)
    public void tickCraftingLogic(IEnergyService eg, CraftingService cc) {
        // Don't tick if we're not active.
        if (!cluster.isActive())
            return;
        cantStoreItems = false;
        // If we don't have a job, just try to dump our items.
        if (this.job == null) {
            if (this.gtlcore$ignoreMissingMode || (this.gtlcore$waitingForMissing != null && !this.gtlcore$waitingForMissing.isEmpty())) {
                this.gtlcore$debug(
                        "tick(job==null) clearing ignore state waiting=%d/%d inventory=%d/%d",
                        gtlcore$countEntries(this.gtlcore$waitingForMissing),
                        gtlcore$countTotal(this.gtlcore$waitingForMissing),
                        gtlcore$countEntries(this.inventory.list),
                        gtlcore$countTotal(this.inventory.list));
            }
            // 清理 ignoreMissing 状态
            this.gtlcore$waitingForMissing = null;
            this.gtlcore$jobGrid = null;
            this.gtlcore$jobActionSource = null;
            this.gtlcore$missingPullTicks = 0;
            this.gtlcore$ignoreMissingMode = false;
            this.gtlcore$pullNoExtractCycles = 0;

            this.storeItems();
            if (!this.inventory.list.isEmpty()) {
                cantStoreItems = true;
            }
            return;
        }
        // Check if the job was cancelled.
        if (((ExecutingCraftingJobAccessor) job).getLink().isCanceled()) {
            this.gtlcore$debug(
                    "tick(link canceled) waiting=%d/%d inventory=%d/%d",
                    gtlcore$countEntries(this.gtlcore$waitingForMissing),
                    gtlcore$countTotal(this.gtlcore$waitingForMissing),
                    gtlcore$countEntries(this.inventory.list),
                    gtlcore$countTotal(this.inventory.list));
            cancel();
            return;
        }

        // ignoreMissing: 周期性从网络拉取缺失物品到 CPU 库存
        if (this.gtlcore$ignoreMissingMode &&
                this.gtlcore$waitingForMissing != null && !this.gtlcore$waitingForMissing.isEmpty()) {
            this.gtlcore$missingPullTicks++;
            if (this.gtlcore$missingPullTicks >= GTLCORE_MISSING_PULL_INTERVAL) {
                this.gtlcore$missingPullTicks = 0;
                this.gtlcore$tryPullMissing();
            }
        }

        var remainingOperations = cluster.getCoProcessors() + 1 - (this.usedOps[0] + this.usedOps[1] + this.usedOps[2]);
        final var started = remainingOperations;

        if (remainingOperations > 0) {
            do {
                var pushedPatterns = executeCrafting(remainingOperations, cc, eg, cluster.getLevel());

                if (pushedPatterns > 0) {
                    remainingOperations -= pushedPatterns;
                } else {

                    // Automatic Cancellation
                    if (this.job != null && ((ExecutingCraftingJobAccessor) this.job).getTasks().isEmpty() &&
                            core$matchOutput(this.getFinalJobOutput()) &&
                            !this.gtlcore$ignoreMissingMode &&
                            (this.gtlcore$waitingForMissing == null || this.gtlcore$waitingForMissing.isEmpty())) {
                        this.gtlcore$debug("autoFinish(success) by empty tasks and special output");
                        this.finishJob(true);
                    }

                    break;
                }
            } while (remainingOperations > 0);
        }
        this.usedOps[2] = this.usedOps[1];
        this.usedOps[1] = this.usedOps[0];
        this.usedOps[0] = started - remainingOperations;
    }

    @Inject(method = "finishJob", at = @At("HEAD"), remap = false)
    private void gtlcore$debugFinishJob(boolean success, CallbackInfo ci) {
        this.gtlcore$debug(
                "finishJob(success=%s) waiting=%d/%d inventory=%d/%d ignoreMode=%s",
                success,
                gtlcore$countEntries(this.gtlcore$waitingForMissing),
                gtlcore$countTotal(this.gtlcore$waitingForMissing),
                gtlcore$countEntries(this.inventory.list),
                gtlcore$countTotal(this.inventory.list),
                this.gtlcore$ignoreMissingMode);
    }

    @Inject(method = "cancel", at = @At("HEAD"), remap = false)
    private void gtlcore$debugCancel(CallbackInfo ci) {
        this.gtlcore$debug(
                "cancel() waiting=%d/%d inventory=%d/%d ignoreMode=%s",
                gtlcore$countEntries(this.gtlcore$waitingForMissing),
                gtlcore$countTotal(this.gtlcore$waitingForMissing),
                gtlcore$countEntries(this.inventory.list),
                gtlcore$countTotal(this.inventory.list),
                this.gtlcore$ignoreMissingMode);
    }

    // ========== executeCrafting (overwrite) ==========

    /**
     * @author Dragons
     * @reason ME样板总成自动翻倍
     */
    @Overwrite(remap = false)
    public int executeCrafting(int maxPatterns, CraftingService craftingService, IEnergyService energyService,
                               Level level) {
        var job = (ExecutingCraftingJobAccessor) (this.job);
        if (job == null) return 0;

        var pushedPatterns = 0;

        var it = job.getTasks().entrySet().iterator();
        taskLoop:
        while (it.hasNext()) {
            var task = it.next();
            var taskProgress = (ExecutingCraftingJobTaskProgressAccessor) (task.getValue());
            if (taskProgress.getValue() <= 0) {
                it.remove();
                continue;
            }

            var details = task.getKey();
            final boolean isProcessing = details instanceof AEProcessingPattern;

            KeyCounter expectedOutputs = new KeyCounter(), expectedContainerItems = new KeyCounter();
            KeyCounter[] craftingContainer = null;
            boolean needExtract = true;

            for (var provider : craftingService.getProviders(details)) {
                final boolean autoExpand = isProcessing && (provider instanceof IMEPatternPartMachine || provider instanceof IMECraftIOPart);

                if (needExtract) {
                    craftingContainer = isProcessing ? (autoExpand ? AEUtils.extractForProcessingPattern((AEProcessingPattern) details, inventory, expectedOutputs, taskProgress.getValue()) : AEUtils.extractForProcessingPattern((AEProcessingPattern) details, inventory, expectedOutputs)) : AEUtils.extractForCraftPattern(details, inventory, level, expectedOutputs, expectedContainerItems);
                    needExtract = false;
                    if (craftingContainer == null) {
                        break;
                    }
                }

                if (provider.isBusy()) continue;

                var patternPower = CraftingCpuHelper.calculatePatternPower(craftingContainer);
                if (energyService.extractAEPower(patternPower, Actionable.SIMULATE, PowerMultiplier.CONFIG) < patternPower - 0.01) {
                    break;
                }

                if (provider.pushPattern(details, craftingContainer)) {
                    energyService.extractAEPower(patternPower, Actionable.MODULATE, PowerMultiplier.CONFIG);
                    pushedPatterns++;

                    for (var expectedOutput : expectedOutputs) {
                        job.getWaitingFor().insert(expectedOutput.getKey(), expectedOutput.getLongValue(),
                                Actionable.MODULATE);
                    }
                    for (var expectedContainerItem : expectedContainerItems) {
                        job.getWaitingFor().insert(expectedContainerItem.getKey(), expectedContainerItem.getLongValue(),
                                Actionable.MODULATE);
                        ((ElapsedTimeTrackerAccessor) job.getTimeTracker()).invokeAddMaxItems(expectedContainerItem.getLongValue(),
                                expectedContainerItem.getKey().getType());
                    }

                    cluster.markDirty();

                    // 1) AutoExpand
                    if (autoExpand) {
                        taskProgress.setValue(0);
                        it.remove();
                        continue taskLoop;
                    }

                    // 2) Others
                    taskProgress.setValue(taskProgress.getValue() - 1);
                    if (taskProgress.getValue() <= 0) {
                        it.remove();
                        continue taskLoop;
                    }

                    if (pushedPatterns == maxPatterns) {
                        break taskLoop;
                    }

                    expectedOutputs.reset();
                    expectedContainerItems.reset();
                    craftingContainer = null;
                    needExtract = true;
                }
            }

            if (craftingContainer != null) {
                CraftingCpuHelper.reinjectPatternInputs(inventory, craftingContainer);
            }
        }

        return pushedPatterns;
    }
}
