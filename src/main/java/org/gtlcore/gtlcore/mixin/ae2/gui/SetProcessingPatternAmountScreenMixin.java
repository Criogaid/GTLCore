package org.gtlcore.gtlcore.mixin.ae2.gui;

import appeng.api.stacks.GenericStack;
import appeng.client.gui.me.items.SetProcessingPatternAmountScreen;
import org.spongepowered.asm.mixin.*;

@Mixin(SetProcessingPatternAmountScreen.class)
public abstract class SetProcessingPatternAmountScreenMixin {

    @Shadow(remap = false)
    @Final
    private GenericStack currentStack;

    /**
     * @author .
     * @reason 样板终端中键设置数量上限
     */
    @Overwrite(remap = false)
    private long getMaxAmount() {
        long amountPerUnit = this.currentStack.what().getAmountPerUnit();
        if (amountPerUnit <= 0) {
            return Long.MAX_VALUE;
        }
        return (Long.MAX_VALUE / amountPerUnit) * amountPerUnit;
    }
}
