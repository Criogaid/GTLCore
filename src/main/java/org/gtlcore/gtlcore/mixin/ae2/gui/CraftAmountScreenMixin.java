package org.gtlcore.gtlcore.mixin.ae2.gui;

import org.gtlcore.gtlcore.integration.ae2.common.ILongCraftAmountMenu;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.crafting.CraftAmountScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.NumberEntryWidget;
import appeng.menu.me.crafting.CraftAmountMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CraftAmountScreen.class)
public abstract class CraftAmountScreenMixin extends AEBaseScreen<CraftAmountMenu> {

    @Shadow(remap = false)
    @Final
    private Button next;

    @Shadow(remap = false)
    @Final
    private NumberEntryWidget amountToCraft;

    protected CraftAmountScreenMixin(CraftAmountMenu menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
    }

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void gtlcore$initLongAmount(CraftAmountMenu menu,
                                        Inventory playerInventory,
                                        Component title,
                                        ScreenStyle style,
                                        CallbackInfo ci) {
        this.gtlcore$syncLongInputLimits();
    }

    @Inject(method = "updateBeforeRender", at = @At("TAIL"), remap = false)
    private void gtlcore$updateLongAmountInput(CallbackInfo ci) {
        this.gtlcore$syncLongInputLimits();
        this.next.active = this.amountToCraft.getLongValue().orElse(0L) > 0L;
    }

    @Inject(method = "confirm", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtlcore$confirmLong(CallbackInfo ci) {
        long amount = this.amountToCraft.getLongValue().orElse(0L);
        if (amount <= 0L) {
            ci.cancel();
            return;
        }

        boolean craftMissingAmount = this.amountToCraft.startsWithEquals();
        boolean autoStart = Screen.hasShiftDown();

        if (this.menu instanceof ILongCraftAmountMenu longCraftAmountMenu) {
            longCraftAmountMenu.gtlcore$confirmLong(amount, craftMissingAmount, autoStart);
        } else {
            this.menu.confirm((int) Math.min(amount, Integer.MAX_VALUE), craftMissingAmount, autoStart);
        }
        ci.cancel();
    }

    @Unique
    private void gtlcore$syncLongInputLimits() {
        this.amountToCraft.setMaxValue(Long.MAX_VALUE);
        ((NumberEntryWidgetAccessor) this.amountToCraft).getTextField()
                .setMaxLength(this.amountToCraft.getType().amountPerUnit() == 1 ? 19 : 16);
    }
}
