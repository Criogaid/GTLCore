package org.gtlcore.gtlcore.mixin.ae2.gui;

import org.gtlcore.gtlcore.integration.ae2.common.ILongCraftAmountMenu;
import org.gtlcore.gtlcore.integration.ae2.common.ILongCraftConfirmMenu;
import org.gtlcore.gtlcore.integration.ae2.common.LongConfirmRequest;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.ISubMenuHost;
import appeng.menu.AEBaseMenu;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.me.crafting.CraftAmountMenu;
import appeng.menu.me.crafting.CraftConfirmMenu;
import appeng.menu.slot.AppEngSlot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

@Mixin(CraftAmountMenu.class)
public abstract class CraftAmountMenuMixin extends AEBaseMenu implements ILongCraftAmountMenu {

    @Shadow(remap = false)
    private AEKey whatToCraft;

    @Shadow(remap = false)
    @Final
    private AppEngSlot craftingItem;

    @Shadow(remap = false)
    @Final
    private ISubMenuHost host;

    protected CraftAmountMenuMixin(MenuType<?> menuType, int id, Inventory playerInventory, Object host) {
        super(menuType, id, playerInventory, host);
    }

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void gtlcore$registerLongConfirmAction(int id, Inventory playerInventory, ISubMenuHost host, CallbackInfo ci) {
        this.registerClientAction("gtlcoreConfirmLong", LongConfirmRequest.class, this::gtlcore$handleLongConfirm);
    }

    @Override
    public void gtlcore$confirmLong(long amount, boolean craftMissingAmount, boolean autoStart) {
        if (this.isClientSide()) {
            this.sendClientAction("gtlcoreConfirmLong", new LongConfirmRequest(amount, craftMissingAmount, autoStart));
            return;
        }
        this.gtlcore$confirmLongInternal(amount, craftMissingAmount, autoStart);
    }

    @Override
    public void gtlcore$setWhatToCraftLong(AEKey whatToCraft, long amount) {
        this.whatToCraft = Objects.requireNonNull(whatToCraft, "whatToCraft");
        this.craftingItem.set(GenericStack.wrapInItemStack(whatToCraft, Math.max(1L, amount)));
    }

    @Unique
    private void gtlcore$handleLongConfirm(LongConfirmRequest request) {
        this.gtlcore$confirmLongInternal(request.amount, request.craftMissingAmount, request.autoStart);
    }

    @Unique
    private void gtlcore$confirmLongInternal(long amount, boolean craftMissingAmount, boolean autoStart) {
        if (this.whatToCraft == null || amount <= 0L) {
            return;
        }

        long remainingAmount = amount;
        if (craftMissingAmount) {
            var actionHost = this.getActionHost();
            if (actionHost != null) {
                var node = actionHost.getActionableNode();
                if (node != null && node.getGrid() != null) {
                    long available = Math.max(0L, node.getGrid().getStorageService().getCachedInventory().get(this.whatToCraft));
                    if (available >= remainingAmount) {
                        remainingAmount = 0L;
                    } else {
                        remainingAmount -= available;
                    }
                }
            }
        }

        var locator = this.getLocator();
        if (locator == null) {
            return;
        }

        Player player = this.getPlayer();
        if (remainingAmount > 0L) {
            MenuOpener.open(CraftConfirmMenu.TYPE, player, locator);
            if (player.containerMenu instanceof CraftConfirmMenu craftConfirmMenu) {
                craftConfirmMenu.setAutoStart(autoStart);
                if (craftConfirmMenu instanceof ILongCraftConfirmMenu longCraftConfirmMenu) {
                    longCraftConfirmMenu.gtlcore$planJobLong(this.whatToCraft, remainingAmount,
                            CalculationStrategy.REPORT_MISSING_ITEMS);
                } else {
                    craftConfirmMenu.planJob(this.whatToCraft, (int) Math.min(remainingAmount, Integer.MAX_VALUE),
                            CalculationStrategy.REPORT_MISSING_ITEMS);
                }
                this.broadcastChanges();
            }
        } else {
            this.host.returnToMainMenu(player, (ISubMenu) (Object) this);
        }
    }
}
