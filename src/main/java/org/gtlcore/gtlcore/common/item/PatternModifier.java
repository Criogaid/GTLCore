package org.gtlcore.gtlcore.common.item;

import org.gtlcore.gtlcore.api.item.tool.ae2.patternTool.Ae2BaseProcessingPatternHelper;
import org.gtlcore.gtlcore.api.item.tool.ae2.patternTool.Ae2BaseProcessingPatternHelper.ScaleValidationResult;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;

import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.component.IItemUIFactory;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget;

import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import appeng.api.inventories.InternalInventory;
import appeng.api.parts.IPart;
import appeng.blockentity.networking.CableBusBlockEntity;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import lombok.Setter;

@Setter
public class PatternModifier implements IItemUIFactory {

    private static final int MIN_SCALE = 1;
    private static final int MAX_APPLIED_NUMBER = 16;
    private static final long AE2_PROVIDER_MAX_ITEM_STACK = Long.MAX_VALUE;
    private static final long AE2_PROVIDER_MAX_FLUID_STACK = Long.MAX_VALUE;
    private static final long ME_PATTERN_BUFFER_MAX_ITEM_STACK = Long.MAX_VALUE;
    private static final long ME_PATTERN_BUFFER_MAX_FLUID_STACK = Long.MAX_VALUE;
    private static final String TAG_ROOT = "pattern_modifier";
    private static final String TAG_INPUT_SCALE = "input_scale";
    private static final String TAG_INPUT_DIV_SCALE = "input_div_scale";
    private static final String TAG_OUTPUT_SCALE = "output_scale";
    private static final String TAG_OUTPUT_DIV_SCALE = "output_div_scale";
    private static final String TAG_MAX_ITEM_STACK = "max_item_stack";
    private static final String TAG_MAX_FLUID_STACK = "max_fluid_stack";
    private static final String TAG_APPLIED_NUMBER = "applied_number";

    public static final PatternModifier INSTANCE = new PatternModifier();
    private static final PatternScaleLimits AE2_PROVIDER_LIMITS = new PatternScaleLimits(AE2_PROVIDER_MAX_ITEM_STACK, AE2_PROVIDER_MAX_FLUID_STACK);
    private static final PatternScaleLimits ME_PATTERN_BUFFER_LIMITS = new PatternScaleLimits(ME_PATTERN_BUFFER_MAX_ITEM_STACK, ME_PATTERN_BUFFER_MAX_FLUID_STACK);

    private int Ae2PatternInputScale = 1;
    private int Ae2PatternInputDivScale = 1;
    private int Ae2PatternOutputScale = 1;
    private int Ae2PatternOutputDivScale = 1;
    private int Ae2PatternGeneratorAppliedNumber = 1;

    private record PatternApplyResult(boolean success, ItemStack itemStack, Component message, int appliedRounds) {

        static PatternApplyResult success(ItemStack itemStack, int appliedRounds) {
            return new PatternApplyResult(true, itemStack, Component.empty(), appliedRounds);
        }

        static PatternApplyResult failed(ItemStack rollbackStack, Component message, int appliedRounds) {
            return new PatternApplyResult(false, rollbackStack, message, appliedRounds);
        }
    }

    private record PatternScaleLimits(long maxItemStack, long maxFluidStack) {}

    private record PatternStepResult(boolean success, ItemStack itemStack, Component message) {

        static PatternStepResult success(ItemStack itemStack) {
            return new PatternStepResult(true, itemStack, Component.empty());
        }

        static PatternStepResult failed(Component message) {
            return new PatternStepResult(false, ItemStack.EMPTY, message);
        }
    }

    @Override
    public ModularUI createUI(HeldItemUIFactory.HeldItemHolder heldItemHolder, Player player) {
        loadConfigFromStack(heldItemHolder.getHeld());
        return new ModularUI(206, 166, heldItemHolder, player).widget(
                new WidgetGroup(0, 0, 206, 166)
                        .addWidget(new ImageWidget(8, 8, 190, 150, GuiTextures.DISPLAY))
                        .addWidget(new LabelWidget(12, 12, "AE样板倍乘器"))
                        .addWidget(new LabelWidget(12, 22, "设置倍数后，shift右键样板供应器方块使用"))
                        .addWidget(new LabelWidget(12, 32, "输入/输出可独立设置，均为先乘后除"))
                        .addWidget(new LabelWidget(12, 42, "应用次数为N时，等价重复执行N次"))
                        .addWidget(new AETextInputButtonWidget(120, 46 + 4, 72, 12)
                                .setText(String.valueOf(Ae2PatternInputScale))
                                .setOnConfirm(s -> setAe2PatternInputScale(s, heldItemHolder.getHeld()))
                                .setButtonTooltips(Component.translatable("tooltip.gtlcore.pattern_input_multiplier_scale")))
                        .addWidget(new AETextInputButtonWidget(120, 60 + 4, 72, 12)
                                .setText(String.valueOf(Ae2PatternInputDivScale))
                                .setOnConfirm(s -> setAe2PatternInputDivScale(s, heldItemHolder.getHeld()))
                                .setButtonTooltips(Component.translatable("tooltip.gtlcore.pattern_input_divider_scale")))
                        .addWidget(new AETextInputButtonWidget(120, 74 + 4, 72, 12)
                                .setText(String.valueOf(Ae2PatternOutputScale))
                                .setOnConfirm(s -> setAe2PatternOutputScale(s, heldItemHolder.getHeld()))
                                .setButtonTooltips(Component.translatable("tooltip.gtlcore.pattern_output_multiplier_scale")))
                        .addWidget(new AETextInputButtonWidget(120, 88 + 4, 72, 12)
                                .setText(String.valueOf(Ae2PatternOutputDivScale))
                                .setOnConfirm(s -> setAe2PatternOutputDivScale(s, heldItemHolder.getHeld()))
                                .setButtonTooltips(Component.translatable("tooltip.gtlcore.pattern_output_divider_scale")))
                        .addWidget(new AETextInputButtonWidget(120, 102 + 4, 72, 12)
                                .setText(String.valueOf(Ae2PatternGeneratorAppliedNumber))
                                .setOnConfirm(s -> setAe2PatternGeneratorAppliedNumber(s, heldItemHolder.getHeld()))
                                .setButtonTooltips(Component.translatable("tooltip.gtlcore.pattern_applied_number"))))
                .background(GuiTextures.BACKGROUND);
    }

    private void setAe2PatternGeneratorAppliedNumber(String s, ItemStack stack) {
        Ae2PatternGeneratorAppliedNumber = clampInt(parseIntOrDefault(s, Ae2PatternGeneratorAppliedNumber), MIN_SCALE, MAX_APPLIED_NUMBER);
        saveConfigToStack(stack);
    }

    private void setAe2PatternInputDivScale(String s, ItemStack stack) {
        Ae2PatternInputDivScale = clampInt(parseIntOrDefault(s, Ae2PatternInputDivScale), MIN_SCALE, Integer.MAX_VALUE);
        saveConfigToStack(stack);
    }

    private void setAe2PatternInputScale(String s, ItemStack stack) {
        Ae2PatternInputScale = clampInt(parseIntOrDefault(s, Ae2PatternInputScale), MIN_SCALE, Integer.MAX_VALUE);
        saveConfigToStack(stack);
    }

    private void setAe2PatternOutputScale(String s, ItemStack stack) {
        Ae2PatternOutputScale = clampInt(parseIntOrDefault(s, Ae2PatternOutputScale), MIN_SCALE, Integer.MAX_VALUE);
        saveConfigToStack(stack);
    }

    private void setAe2PatternOutputDivScale(String s, ItemStack stack) {
        Ae2PatternOutputDivScale = clampInt(parseIntOrDefault(s, Ae2PatternOutputDivScale), MIN_SCALE, Integer.MAX_VALUE);
        saveConfigToStack(stack);
    }

    private void loadConfigFromStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            resetConfigToDefault();
            return;
        }
        CompoundTag tag = stack.getTagElement(TAG_ROOT);
        if (tag == null) {
            resetConfigToDefault();
            return;
        }
        Ae2PatternInputScale = readInt(tag, TAG_INPUT_SCALE, 1, MIN_SCALE, Integer.MAX_VALUE);
        Ae2PatternInputDivScale = readInt(tag, TAG_INPUT_DIV_SCALE, 1, MIN_SCALE, Integer.MAX_VALUE);
        Ae2PatternOutputScale = readInt(tag, TAG_OUTPUT_SCALE, 1, MIN_SCALE, Integer.MAX_VALUE);
        Ae2PatternOutputDivScale = readInt(tag, TAG_OUTPUT_DIV_SCALE, 1, MIN_SCALE, Integer.MAX_VALUE);
        Ae2PatternGeneratorAppliedNumber = readInt(tag, TAG_APPLIED_NUMBER, 1, MIN_SCALE, MAX_APPLIED_NUMBER);
    }

    private void saveConfigToStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        CompoundTag tag = stack.getOrCreateTagElement(TAG_ROOT);
        tag.putInt(TAG_INPUT_SCALE, Ae2PatternInputScale);
        tag.putInt(TAG_INPUT_DIV_SCALE, Ae2PatternInputDivScale);
        tag.putInt(TAG_OUTPUT_SCALE, Ae2PatternOutputScale);
        tag.putInt(TAG_OUTPUT_DIV_SCALE, Ae2PatternOutputDivScale);
        // 兼容历史版本：移除旧的上限配置字段
        tag.remove(TAG_MAX_ITEM_STACK);
        tag.remove(TAG_MAX_FLUID_STACK);
        tag.putInt(TAG_APPLIED_NUMBER, Ae2PatternGeneratorAppliedNumber);
    }

    private void resetConfigToDefault() {
        Ae2PatternInputScale = 1;
        Ae2PatternInputDivScale = 1;
        Ae2PatternOutputScale = 1;
        Ae2PatternOutputDivScale = 1;
        Ae2PatternGeneratorAppliedNumber = 1;
    }

    private static int readInt(CompoundTag tag, String key, int defaultValue, int min, int max) {
        if (!tag.contains(key)) {
            return defaultValue;
        }
        return clampInt(tag.getInt(key), min, max);
    }

    private static int parseIntOrDefault(String raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private PatternScaleLimits resolvePatternScaleLimits(Object target) {
        if (target instanceof MEPatternBufferPartMachine) {
            return ME_PATTERN_BUFFER_LIMITS;
        }
        if (target instanceof PatternProviderLogicHost) {
            return AE2_PROVIDER_LIMITS;
        }
        return AE2_PROVIDER_LIMITS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Item item, Level level, Player player, InteractionHand usedHand) {
        ItemStack stack = player.getItemInHand(usedHand);
        loadConfigFromStack(stack);
        if (player instanceof ServerPlayer serverPlayer) {
            HeldItemUIFactory.INSTANCE.openUI(serverPlayer, usedHand);
        }
        return new InteractionResultHolder<>(InteractionResult.SUCCESS, stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() instanceof ServerPlayer serverPlayer) {
            loadConfigFromStack(context.getItemInHand());
            /*
             * shift右键乘法逻辑
             */
            if (serverPlayer.isShiftKeyDown()) {
                BlockPos pos = context.getClickedPos();
                Level level = context.getLevel();
                BlockEntity tile = level.getBlockEntity(pos);
                InternalInventory internalInventory;
                PatternScaleLimits scaleLimits = AE2_PROVIDER_LIMITS;
                if (tile instanceof CableBusBlockEntity cable) {
                    // 获取点击方块坐标
                    Vec3 hitVec = context.getClickLocation();
                    // 计算具体点击位置
                    Vec3 hitInBlock = new Vec3(hitVec.x - (double) pos.getX(), hitVec.y - (double) pos.getY(), hitVec.z - (double) pos.getZ());
                    IPart part = cable.getCableBus().selectPartLocal(hitInBlock).part;
                    if (part instanceof PatternProviderLogicHost providerPart) {
                        internalInventory = providerPart.getLogic().getPatternInv();
                        scaleLimits = resolvePatternScaleLimits(providerPart);
                    } else {
                        internalInventory = null;
                    }

                } else if (tile instanceof PatternProviderLogicHost providerBlock) {
                    internalInventory = providerBlock.getLogic().getPatternInv();
                    scaleLimits = resolvePatternScaleLimits(providerBlock);
                } else if (tile instanceof MetaMachineBlockEntity mmbe && mmbe.getMetaMachine() instanceof MEPatternBufferPartMachine me) {
                    internalInventory = me.getTerminalPatternInventory();
                    scaleLimits = resolvePatternScaleLimits(me);
                } else {
                    internalInventory = null;
                }

                if (internalInventory == null) {
                    serverPlayer.displayClientMessage(Component.translatable("message.gtlcore.pattern_provider_only")
                            .withStyle(), true);
                    return InteractionResult.FAIL;
                }

                int fullSuccessSlotCount = 0;
                int partialSuccessSlotCount = 0;
                int failedSlotCount = 0;
                int changedSlotCount = 0;
                for (int slot = 0; slot < internalInventory.size(); slot++) {
                    ItemStack itemStack = internalInventory.getStackInSlot(slot);
                    if (itemStack.isEmpty()) {
                        continue;
                    }
                    PatternApplyResult result = applyPatternWithRounds(serverPlayer, itemStack, slot, scaleLimits);
                    if (result.itemStack().isEmpty()) {
                        continue;
                    }
                    if (!ItemStack.isSameItemSameTags(itemStack, result.itemStack())) {
                        internalInventory.extractItem(slot, 1, false);
                        internalInventory.insertItem(slot, result.itemStack(), false);
                        changedSlotCount++;
                    }
                    if (result.success()) {
                        fullSuccessSlotCount++;
                    } else {
                        if (result.appliedRounds() > 0) {
                            partialSuccessSlotCount++;
                        } else {
                            failedSlotCount++;
                        }
                    }
                }
                serverPlayer.displayClientMessage(
                        Component.translatable(
                                "message.gtlcore.pattern_updated_summary",
                                fullSuccessSlotCount,
                                partialSuccessSlotCount,
                                failedSlotCount,
                                changedSlotCount),
                        true);
            }
            if (!serverPlayer.isShiftKeyDown()) {
                serverPlayer.displayClientMessage(Component.translatable("message.gtlcore.right_click_air_gui"), true);
            }
        }
        return InteractionResult.SUCCESS;
    }

    private PatternApplyResult applyPatternWithRounds(ServerPlayer serverPlayer, ItemStack itemStack, int slot, PatternScaleLimits scaleLimits) {
        ItemStack committedPatternStack = itemStack.copy();
        int appliedRounds = 0;
        for (int round = 1; round <= Ae2PatternGeneratorAppliedNumber; round++) {
            ItemStack roundStartStack = committedPatternStack.copy();
            PatternStepResult multiplyResult = applySingleScaleStep(
                    serverPlayer,
                    roundStartStack,
                    slot,
                    round,
                    false,
                    Ae2PatternInputScale,
                    Ae2PatternOutputScale,
                    scaleLimits);
            if (!multiplyResult.success()) {
                return PatternApplyResult.failed(roundStartStack, multiplyResult.message(), appliedRounds);
            }

            PatternStepResult divideResult = applySingleScaleStep(
                    serverPlayer,
                    multiplyResult.itemStack(),
                    slot,
                    round,
                    true,
                    Ae2PatternInputDivScale,
                    Ae2PatternOutputDivScale,
                    scaleLimits);
            if (!divideResult.success()) {
                return PatternApplyResult.failed(roundStartStack, divideResult.message(), appliedRounds);
            }
            committedPatternStack = divideResult.itemStack();
            appliedRounds = round;
        }
        return PatternApplyResult.success(committedPatternStack, appliedRounds);
    }

    private PatternStepResult applySingleScaleStep(ServerPlayer serverPlayer, ItemStack patternStack, int slot, int round, boolean divStep, int inputScale, int outputScale, PatternScaleLimits scaleLimits) {
        var processingPattern = Ae2BaseProcessingPatternHelper.decodeToAEProcessingPattern(patternStack, serverPlayer);
        if (processingPattern == null) {
            return PatternStepResult.failed(Component.translatable("message.gtlcore.pattern_modify_failed_decode", round, slot + 1));
        }

        ScaleValidationResult validationResult = Ae2BaseProcessingPatternHelper.validateScaleSeparated(
                inputScale,
                divStep,
                outputScale,
                divStep,
                processingPattern,
                scaleLimits.maxItemStack(),
                scaleLimits.maxFluidStack());
        if (!validationResult.success()) {
            return PatternStepResult.failed(buildScaleFailureMessage(round, slot, divStep, validationResult));
        }

        ItemStack newPatternStack = Ae2BaseProcessingPatternHelper.multiplyScaleSeparated(
                inputScale,
                divStep,
                outputScale,
                divStep,
                processingPattern,
                scaleLimits.maxItemStack(),
                scaleLimits.maxFluidStack());
        if (newPatternStack == null || newPatternStack.isEmpty()) {
            return PatternStepResult.failed(Component.translatable("message.gtlcore.pattern_modify_failed_unknown", round, slot + 1));
        }
        return PatternStepResult.success(newPatternStack);
    }

    private Component buildScaleFailureMessage(int round, int slot, boolean divStep, ScaleValidationResult validationResult) {
        Component stage = Component.translatable(divStep ? "message.gtlcore.pattern_modify_stage_divide" : "message.gtlcore.pattern_modify_stage_multiply");
        Component part = switch (validationResult.part()) {
            case INPUT -> Component.translatable("message.gtlcore.pattern_modify_part_input");
            case OUTPUT -> Component.translatable("message.gtlcore.pattern_modify_part_output");
            default -> Component.translatable("message.gtlcore.pattern_modify_part_unknown");
        };
        Component reason = switch (validationResult.reason()) {
            case INVALID_SCALE -> Component.translatable("message.gtlcore.pattern_modify_reason_invalid_scale");
            case DIVIDE_NOT_DIVISIBLE -> Component.translatable("message.gtlcore.pattern_modify_reason_divide_not_divisible");
            case MULTIPLY_LIMIT_EXCEEDED -> Component.translatable("message.gtlcore.pattern_modify_reason_multiply_limit");
            default -> Component.translatable("message.gtlcore.pattern_modify_reason_unknown");
        };
        return Component.translatable("message.gtlcore.pattern_modify_failed", round, slot + 1, stage, part, reason);
    }
}
