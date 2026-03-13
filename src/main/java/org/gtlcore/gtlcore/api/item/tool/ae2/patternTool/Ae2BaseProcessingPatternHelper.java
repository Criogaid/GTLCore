package org.gtlcore.gtlcore.api.item.tool.ae2.patternTool;

import org.gtlcore.gtlcore.GTLCore;

import com.gregtechceu.gtceu.common.data.GTItems;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.crafting.pattern.EncodedPatternItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Ae2BaseProcessingPatternHelper {

    public enum ScaleFailurePart {
        NONE,
        INPUT,
        OUTPUT
    }

    public enum ScaleFailureReason {
        NONE,
        INVALID_SCALE,
        DIVIDE_NOT_DIVISIBLE,
        MULTIPLY_LIMIT_EXCEEDED
    }

    public record ScaleValidationResult(ScaleFailurePart part, ScaleFailureReason reason) {

        public static final ScaleValidationResult SUCCESS = new ScaleValidationResult(ScaleFailurePart.NONE, ScaleFailureReason.NONE);

        public boolean success() {
            return reason == ScaleFailureReason.NONE;
        }
    }

    // 乘或除 输入解码后样板，输出编码后样板
    public static ItemStack multiplyScale(int scale, boolean div, AEProcessingPattern patternDetail, long maxItemStack, long maxFluidStack) {
        return multiplyScaleSeparated(scale, div, scale, div, patternDetail, maxItemStack, maxFluidStack);
    }

    // 乘或除 输入和输出可分别设置
    public static ItemStack multiplyScaleSeparated(int inputScale, boolean inputDiv, int outputScale, boolean outputDiv, AEProcessingPattern patternDetail, long maxItemStack, long maxFluidStack) {
        ScaleValidationResult validationResult = validateScaleSeparated(inputScale, inputDiv, outputScale, outputDiv, patternDetail, maxItemStack, maxFluidStack);
        if (!validationResult.success()) {
            GTLCore.LOGGER.info("内部错误：无法整除 或 乘数过大");
            return null;
        }

        var input = patternDetail.getSparseInputs();
        var output = patternDetail.getOutputs();
        var mulInput = new GenericStack[input.length];
        var mulOutput = new GenericStack[output.length];
        modifyStacks(input, mulInput, inputScale, inputDiv);
        modifyStacks(output, mulOutput, outputScale, outputDiv);
        return PatternDetailsHelper.encodeProcessingPattern(mulInput, mulOutput);
    }

    public static ScaleValidationResult validateScaleSeparated(int inputScale, boolean inputDiv, int outputScale, boolean outputDiv, AEProcessingPattern patternDetail, long maxItemStack, long maxFluidStack) {
        ScaleFailureReason inputFailure = checkModifyFailure(patternDetail.getSparseInputs(), inputScale, inputDiv, maxItemStack, maxFluidStack);
        if (inputFailure != ScaleFailureReason.NONE) {
            return new ScaleValidationResult(ScaleFailurePart.INPUT, inputFailure);
        }
        ScaleFailureReason outputFailure = checkModifyFailure(patternDetail.getOutputs(), outputScale, outputDiv, maxItemStack, maxFluidStack);
        if (outputFailure != ScaleFailureReason.NONE) {
            return new ScaleValidationResult(ScaleFailurePart.OUTPUT, outputFailure);
        }
        return ScaleValidationResult.SUCCESS;
    }

    // 从样板物品解码样板
    public static AEProcessingPattern decodeToAEProcessingPattern(ItemStack patternStack, ServerPlayer serverPlayer) {
        if (patternStack.getItem() instanceof EncodedPatternItem patternItem_1) {
            if (patternItem_1.decode(patternStack, serverPlayer.level(), false) instanceof AEProcessingPattern processStack) {
                return processStack;
            } else {
                GTLCore.LOGGER.info("Ae2BaseProcessingPattern requires a EncodedPatternItem 意外之内的输入：非处理样板");
            }
        } else {
            GTLCore.LOGGER.info("Ae2BaseProcessingPattern requires a EncodedPatternItem 意外之内的输入：非AE样板");
        }
        return null;
    }

    // 转换到list方便操作
    public static List<GenericStack> transGenericStackArrayToList(GenericStack[] genericStackArray) {
        return new ArrayList<>(Arrays.asList(genericStackArray));
    }

    // 转换回[]，构造AE2 API参数
    public static GenericStack[] transGenericStackListToArray(List<GenericStack> genericStackList) {
        GenericStack[] genericStackArrayList = new GenericStack[genericStackList.size()];
        return genericStackList.toArray(genericStackArrayList);
    }

    // 返回排除黑名单物品后的列表
    public static List<GenericStack> GenericStackListBlackFilter(List<GenericStack> inputGenericStacks, List<Item> matchItemList) {
        return inputGenericStacks.stream().filter(
                genericStack -> !itemMatch(genericStack, matchItemList)).toList();
    }

    private static boolean itemMatch(GenericStack genericStack, List<Item> matchItemList) {
        if (!(genericStack.what() instanceof AEItemKey itemKey)) {
            return false;
        }
        for (Item item : matchItemList) {
            if (itemKey.getItem() == item) {
                return true;
            }
        }
        return false;
    }

    private static ScaleFailureReason checkModifyFailure(GenericStack[] stacks, int scale, boolean div, long maxItemStack, long maxFluidStack) {
        if (scale <= 0) {
            return ScaleFailureReason.INVALID_SCALE;
        }
        if (div) {
            for (var stack : stacks) {
                if (stack != null) {
                    if (isScaleBlacklisted(stack)) {
                        continue;
                    }
                    if (stack.amount() % scale != 0) {
                        return ScaleFailureReason.DIVIDE_NOT_DIVISIBLE;
                    }
                }
            }
        } else {
            for (var stack : stacks) {
                if (stack != null) {
                    if (isScaleBlacklisted(stack)) {
                        continue;
                    }
                    long amount = stack.amount();
                    if (stack.what().getType().equals(AEKeyType.fluids())) {
                        long upper = safeMultiply(maxFluidStack, stack.what().getAmountPerUnit());
                        if (willExceedAfterScale(amount, scale, upper)) {
                            return ScaleFailureReason.MULTIPLY_LIMIT_EXCEEDED;
                        }
                    }
                    if (stack.what().getType().equals(AEKeyType.items())) {
                        long upper = safeMultiply(maxItemStack, stack.what().getAmountPerUnit());
                        if (willExceedAfterScale(amount, scale, upper)) {
                            return ScaleFailureReason.MULTIPLY_LIMIT_EXCEEDED;
                        }
                    }
                }
            }
        }
        return ScaleFailureReason.NONE;
    }

    private static long safeMultiply(long left, long right) {
        if (left <= 0 || right <= 0) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static boolean willExceedAfterScale(long amount, int scale, long upper) {
        if (amount <= 0) {
            return false;
        }
        return amount > upper / scale;
    }

    private static void modifyStacks(GenericStack[] stacks, GenericStack[] des, int scale, boolean div) {
        for (int i = 0; i < stacks.length; i++) {
            if (stacks[i] != null) {
                long amt = stacks[i].amount();
                if (!isScaleBlacklisted(stacks[i])) {
                    amt = div ? stacks[i].amount() / scale : stacks[i].amount() * scale;
                }
                des[i] = new GenericStack(stacks[i].what(), amt);
            }
        }
    }

    private static boolean isScaleBlacklisted(GenericStack stack) {
        if (stack == null) {
            return false;
        }
        if (!(stack.what() instanceof AEItemKey itemKey)) {
            return false;
        }
        ItemStack itemStack = itemKey.toStack();
        return GTItems.INTEGRATED_CIRCUIT.is(itemStack.getItem()) &&
                IntCircuitBehaviour.getCircuitConfiguration(itemStack) >= 0;
    }
}
