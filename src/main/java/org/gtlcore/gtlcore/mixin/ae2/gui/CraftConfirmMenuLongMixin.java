package org.gtlcore.gtlcore.mixin.ae2.gui;

import org.gtlcore.gtlcore.integration.ae2.common.ILongCraftAmountMenu;
import org.gtlcore.gtlcore.integration.ae2.common.ILongCraftConfirmMenu;
import org.gtlcore.gtlcore.integration.ae2.crafting.ICraftingPlanIgnoreMissingMode;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.storage.ISubMenuHost;
import appeng.helpers.IMenuCraftingPacket;
import appeng.menu.AEBaseMenu;
import appeng.menu.ISubMenu;
import appeng.menu.locator.MenuLocator;
import appeng.menu.me.crafting.CraftAmountMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.Future;

@Mixin(CraftConfirmMenu.class)
public abstract class CraftConfirmMenuLongMixin extends AEBaseMenu implements ILongCraftConfirmMenu {

    @Shadow(remap = false)
    private Future<ICraftingPlan> job;

    @Shadow(remap = false)
    private ICraftingPlan result;

    @Shadow(remap = false)
    private AEKey whatToCraft;

    @Shadow(remap = false)
    private int amount;

    @Shadow(remap = false)
    public boolean autoStart;

    @Shadow(remap = false)
    @Final
    private ISubMenuHost host;

    @Shadow(remap = false)
    private List<IMenuCraftingPacket.AutoCraftEntry> autoCraftingQueue;

    @Shadow(remap = false)
    private static void openWithCraftingList(appeng.api.networking.security.IActionHost actionHost,
                                             ServerPlayer serverPlayer,
                                             MenuLocator locator,
                                             List<IMenuCraftingPacket.AutoCraftEntry> autoCraftEntries) {
        throw new AssertionError();
    }

    @Shadow(remap = false)
    private IGrid getGrid() {
        throw new AssertionError();
    }

    @Shadow(remap = false)
    public abstract void clearError();

    @Shadow(remap = false)
    public abstract void goBack();

    protected CraftConfirmMenuLongMixin(MenuType<?> menuType, int id, Inventory playerInventory, Object host) {
        super(menuType, id, playerInventory, host);
    }

    @Unique
    private long gtlcore$plannedAmountLong = -1L;

    @Inject(method = "planJob", at = @At("HEAD"), remap = false)
    private void gtlcore$captureIntPlannedAmount(AEKey whatToCraft,
                                                 int amount,
                                                 CalculationStrategy strategy,
                                                 CallbackInfoReturnable<Boolean> cir) {
        this.gtlcore$plannedAmountLong = Math.max(0L, amount);
    }

    @Inject(method = "replan", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$replanLong(CallbackInfo ci) {
        if (this.gtlcore$plannedAmountLong <= Integer.MAX_VALUE) {
            return;
        }

        this.clearError();
        if (this.isClientSide()) {
            this.sendClientAction("replan");
            ci.cancel();
            return;
        }

        if (this.whatToCraft != null) {
            if (!this.gtlcore$planJobLong(this.whatToCraft, this.gtlcore$plannedAmountLong, CalculationStrategy.CRAFT_LESS)) {
                this.goBack();
            }
        } else {
            this.goBack();
        }
        ci.cancel();
    }

    @Inject(method = "goBack", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$goBackWithLongAmount(CallbackInfo ci) {
        if (this.gtlcore$plannedAmountLong <= Integer.MAX_VALUE) {
            return;
        }

        this.clearError();
        Player player = this.getPlayerInventory().player;
        if (player instanceof ServerPlayer serverPlayer) {
            if (this.autoCraftingQueue != null && !this.autoCraftingQueue.isEmpty()) {
                openWithCraftingList(this.getActionHost(), (ServerPlayer) this.getPlayer(), this.getLocator(), this.autoCraftingQueue);
            } else if (this.whatToCraft != null) {
                gtlcore$openCraftAmountLong(serverPlayer, this.getLocator(), this.whatToCraft, this.gtlcore$plannedAmountLong);
            } else {
                this.host.returnToMainMenu(this.getPlayer(), (ISubMenu) (Object) this);
            }
        } else {
            this.sendClientAction("back");
        }
        ci.cancel();
    }

    @Override
    public boolean gtlcore$planJobLong(AEKey whatToCraft, long amount, CalculationStrategy strategy) {
        if (amount <= 0L) {
            return false;
        }

        if (this.job != null) {
            this.job.cancel(true);
        }

        this.result = null;
        this.clearError();
        this.whatToCraft = whatToCraft;
        this.gtlcore$plannedAmountLong = amount;
        this.amount = (int) Math.min(amount, Integer.MAX_VALUE);

        var grid = this.getGrid();
        if (grid == null) {
            return false;
        }

        var craftingService = grid.getCraftingService();
        this.job = craftingService.beginCraftingCalculation(
                this.getPlayer().level(),
                this::getActionSource,
                whatToCraft,
                amount,
                strategy);
        return true;
    }

    @Override
    public long gtlcore$getPlannedAmountLong() {
        return this.gtlcore$plannedAmountLong;
    }

    @Unique
    private static void gtlcore$openCraftAmountLong(ServerPlayer player, MenuLocator locator, AEKey whatToCraft, long amount) {
        if (locator == null) {
            return;
        }

        CraftAmountMenu.open(player, locator, whatToCraft, Integer.MAX_VALUE);
        if (player.containerMenu instanceof ILongCraftAmountMenu longCraftAmountMenu) {
            longCraftAmountMenu.gtlcore$setWhatToCraftLong(whatToCraft, amount);
            player.containerMenu.broadcastChanges();
        }
    }

    /**
     * 拦截 broadcastChanges 中的 simulation() 调用。
     * 当 autoStart=true（Shift+确认）且计划包含缺失物品时，
     * 标记计划为 ignoreMissing 并返回 false，使得 startJob() 正常触发。
     * <p>
     * 该注入只负责入口触发，后续运行态与重启恢复由 CPU 侧状态接管。
     */
    @Redirect(
              method = "broadcastChanges",
              at = @At(value = "INVOKE",
                       target = "Lappeng/api/networking/crafting/ICraftingPlan;simulation()Z",
                       remap = false))
    private boolean gtlcore$bypassSimulationForAutoStart(ICraftingPlan plan) {
        boolean isSimulation = plan.simulation();
        if (isSimulation && this.autoStart && plan instanceof ICraftingPlanIgnoreMissingMode mode) {
            mode.gtlcore$setIgnoreMissing(true);
            return false;
        }
        return isSimulation;
    }
}
