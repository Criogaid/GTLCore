package org.gtlcore.gtlcore.integration.ae2;

import org.gtlcore.gtlcore.api.recipe.ingredient.CacheHashStrategies;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.gtlcore.gtlcore.config.AE2CalculationMode;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.utils.NumberUtils;

import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.common.data.GTItems;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import net.minecraft.nbt.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ICraftingInventory;
import appeng.crafting.pattern.*;
import it.unimi.dsi.fastutil.objects.*;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static appeng.crafting.execution.CraftingCpuHelper.*;

public class AEUtils {

    private static final int BATCH_SIZE = 64;
    private static final int MAX_FAILED_ATTEMPTS = 5;

    public static <T extends AEKey> boolean reFunds(Object2LongMap<T> buffer, @Nullable IGrid network, IActionSource actionSource) {
        if (buffer.isEmpty()) return false;

        if (network == null) return false;

        final MEStorage networkInv = network.getStorageService().getInventory();
        final var energy = network.getEnergyService();
        int operationsBatched = 0, consecutiveFailures = 0;
        boolean didWork = false;

        for (var it = Object2LongMaps.fastIterator(buffer); it.hasNext() && operationsBatched < BATCH_SIZE;) {
            var entry = it.next();
            long amount = entry.getLongValue();

            if (amount <= 0) {
                it.remove();
                continue;
            }

            long inserted = StorageHelper.poweredInsert(energy, networkInv, entry.getKey(), amount, actionSource);
            operationsBatched++;

            if (inserted > 0) {
                didWork = true;
                consecutiveFailures = 0;
                long left = amount - inserted;
                if (left <= 0) {
                    it.remove();
                } else {
                    entry.setValue(left);
                }
            } else {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_FAILED_ATTEMPTS) {
                    break;
                }
            }
        }
        return didWork;
    }

    // ========================================
    // Recipe
    // ========================================

    public static boolean testFluidIngredient(FluidIngredient fluidIngredient, AEFluidKey fluidKey) {
        if (fluidIngredient.isEmpty()) {
            return false;
        } else if (fluidIngredient.getNbt() != null && !fluidIngredient.getNbt().equals(fluidKey.getTag())) {
            return false;
        } else {
            for (FluidStack fluidStack : fluidIngredient.getStacks()) {
                if (fluidStack.getFluid() == fluidKey.getFluid()) {
                    return true;
                }
            }
            return false;
        }
    }

    // ========================================
    // Persist
    // ========================================

    public static <T> ListTag createListTag(Function<T, CompoundTag> keySerializer, Object2LongMap<T> map) {
        ListTag tag = new ListTag();
        for (var it = Object2LongMaps.fastIterator(map); it.hasNext();) {
            var entry = it.next();
            var ct = keySerializer.apply(entry.getKey());
            ct.putLong("real", entry.getLongValue());
            tag.add(ct);
        }
        return tag;
    }

    public static <K> void loadInventory(ListTag tag, Function<CompoundTag, K> keyExtractor, Object2LongMap<K> targetMap) {
        for (Tag t : tag) {
            if (!(t instanceof CompoundTag ct)) continue;
            K key = keyExtractor.apply(ct);
            long value = ct.getLong("real");
            if (key != null && value > 0) {
                targetMap.put(key, value);
            }
        }
    }

    public static <T> ListTag createListTag(Function<T, CompoundTag> keySerializer, ObjectSet<T> map) {
        ListTag tag = new ListTag();
        for (T t : map) {
            var ct = keySerializer.apply(t);
            tag.add(ct);
        }
        return tag;
    }

    public static <K> void loadInventory(ListTag tag, Function<CompoundTag, K> keyExtractor, ObjectSet<K> targetMap) {
        for (Tag t : tag) {
            if (!(t instanceof CompoundTag ct)) continue;
            K key = keyExtractor.apply(ct);
            if (key != null) {
                targetMap.add(key);
            }
        }
    }

    public static CompoundTag writeTag(@Nullable GenericStack stack) {
        if (stack == null) {
            return new CompoundTag();
        } else {
            CompoundTag tag = stack.what().toTagGeneric().copy();
            tag.putLong("#", stack.amount());
            return tag;
        }
    }

    // ========================================
    // ME IO Machine Utils
    // ========================================

    public static Pair<Object2LongOpenHashMap<Item>, Object2LongOpenHashMap<Fluid>> mergeInternalSlot(MEPatternBufferPartMachine.InternalSlot[] internalSlots) {
        Object2LongOpenHashMap<Item> items = new Object2LongOpenHashMap<>();
        Object2LongOpenHashMap<Fluid> fluids = new Object2LongOpenHashMap<>();
        for (var internalSlot : Arrays.stream(internalSlots).filter(MEPatternBufferPartMachine.InternalSlot::isActive).toList()) {
            for (var it = Object2LongMaps.fastIterator(internalSlot.getItemInventory()); it.hasNext();) {
                var entry = it.next();
                items.addTo(entry.getKey().getItem(), entry.getLongValue());
            }
            for (var it = Object2LongMaps.fastIterator(internalSlot.getFluidInventory()); it.hasNext();) {
                var entry = it.next();
                fluids.addTo(entry.getKey().getFluid(), entry.getLongValue());
            }
        }
        return new ImmutablePair<>(items, fluids);
    }

    public static Object2LongMap<Ingredient> ingredientsMapWithOutCircuit(List<Ingredient> ingredients, Consumer<Integer> consumer) {
        var result = new Object2LongOpenCustomHashMap<>(CacheHashStrategies.IngredientHashStrategy.INSTANCE);
        for (Ingredient ingredient : ingredients) {
            var items = ingredient.getItems();
            if (items.length == 0 || items[0].isEmpty()) {
                continue;
            }
            if (GTItems.INTEGRATED_CIRCUIT.is(items[0].getItem())) {
                consumer.accept(IntCircuitBehaviour.getCircuitConfiguration(items[0]));
                continue;
            }
            result.addTo(ingredient, ingredient instanceof LongIngredient longIngredient ? longIngredient.getActualAmount() : items[0].getCount());
        }
        return result;
    }

    public static Object2LongMap<Ingredient> ingredientsMap(List<Ingredient> ingredients) {
        var result = new Object2LongOpenCustomHashMap<>(CacheHashStrategies.IngredientHashStrategy.INSTANCE);
        for (Ingredient ingredient : ingredients) {
            var items = ingredient.getItems();
            if (items.length == 0 || items[0].isEmpty()) {
                continue;
            }
            result.addTo(ingredient, ingredient instanceof LongIngredient longIngredient ? longIngredient.getActualAmount() : items[0].getCount());
        }
        return result;
    }

    public static Object2LongMap<FluidIngredient> fluidIngredientsMap(List<FluidIngredient> ingredients) {
        var result = new Object2LongOpenCustomHashMap<>(CacheHashStrategies.FluidIngredientHashStrategy.INSTANCE);
        for (FluidIngredient ingredient : ingredients) {
            if (ingredient.isEmpty()) continue;
            result.addTo(ingredient, ingredient.getAmount());
        }
        return result;
    }

    public static Function<ItemStack, Boolean> PROCESS_FILTER = stack -> stack.getItem() instanceof ProcessingPatternItem;

    public static boolean molecularFilter(ItemStack stack, Level level) {
        final var item = stack.getItem();
        if (item instanceof CraftingPatternItem craftingPatternItem) {
            var pattern = craftingPatternItem.decode(stack, level, false);
            if (pattern != null) {
                return !hasContainerItems(pattern);
            }
        } else {
            return item instanceof SmithingTablePatternItem || item instanceof StonecuttingPatternItem;
        }
        return false;
    }

    private static boolean hasContainerItems(AECraftingPattern pattern) {
        IPatternDetails.IInput[] inputs = pattern.getInputs();

        for (IPatternDetails.IInput input : inputs) {
            AEKey key = input.getPossibleInputs()[0].what();
            AEKey remainingKey = input.getRemainingKey(key);
            if (remainingKey != null) {
                if (!(remainingKey instanceof AEItemKey itemKey) || itemKey.toStack().isDamageableItem()) {
                    return true;
                }
            }
        }

        return false;
    }

    public static Pair<IPatternDetails, @Nullable ObjectSet<Item>> createProcessingFromCraftPattern(IMolecularAssemblerSupportedPattern molecularAssemblerSupportedPattern, Level level) {
        IPatternDetails.IInput[] inputs = molecularAssemblerSupportedPattern.getInputs();

        ObjectArrayList<GenericStack> normalInputs = new ObjectArrayList<>();
        ObjectSet<Item> remainingInputs = new ObjectArraySet<>();
        for (IPatternDetails.IInput input : inputs) {
            final var stack = input.getPossibleInputs()[0];
            final var remaining = input.getRemainingKey(stack.what());
            if (remaining != null) {
                assert remaining instanceof AEItemKey;
                if (((AEItemKey) remaining).toStack().isDamageableItem()) {
                    return ImmutablePair.of(null, null);

                } else remainingInputs.add(((AEItemKey) remaining).getItem());
            } else {
                normalInputs.add(new GenericStack(stack.what(), NumberUtils.saturatedMultiply(stack.amount(), input.getMultiplier())));
            }
        }

        ItemStack pattern = PatternDetailsHelper.encodeProcessingPattern(normalInputs.toArray(new GenericStack[0]), molecularAssemblerSupportedPattern.getOutputs());

        return ImmutablePair.of(PatternDetailsHelper.decodePattern(pattern, level), remainingInputs);
    }

    // ========================================
    // ME Processing Pattern Multiply
    // ========================================

    public static final AE2CalculationMode CALCULATION_MODE = ConfigHolder.INSTANCE.ae2CalculationMode;

    public static void pushInputsToMEPatternBufferInventory(KeyCounter[] inputHolder, IPatternDetails.PatternInputSink inputSink) {
        for (var inputList : inputHolder) {
            for (var input : inputList) {
                inputSink.pushInput(input.getKey(), input.getLongValue());
            }
        }
    }

    public static KeyCounter[] extractForProcessingPattern(AEProcessingPattern originDetail,
                                                           ICraftingInventory sourceInv,
                                                           KeyCounter expectedOutputs) {
        return extractForProcessingPattern(originDetail, sourceInv, expectedOutputs, 1);
    }

    /**
     * 处理样板单轮提取结果。
     *
     * @param inputHolder 按输入槽位对齐的已提取内容；零消耗槽位保留空计数器，不参与后验证。
     * @param appliedMultiplier 本轮实际应用的批次数；自动翻倍路径下已按 long 安全上界裁剪。
     */
    public record ProcessingPatternExtractionResult(KeyCounter[] inputHolder, long appliedMultiplier) {}

    private static long getPrimaryInputAmountPerOperation(IPatternDetails.IInput input) {
        GenericStack[] possibleInputs = input.getPossibleInputs();
        if (possibleInputs.length == 0) {
            return 0L;
        }
        return NumberUtils.saturatedMultiply(possibleInputs[0].amount(), input.getMultiplier());
    }

    public static @Nullable ProcessingPatternExtractionResult extractForAutoExpandedProcessingPattern(AEProcessingPattern originDetail,
                                                                                                      ICraftingInventory sourceInv,
                                                                                                      KeyCounter expectedOutputs,
                                                                                                      long requestedMultiplier) {
        return extractForProcessingPatternInternal(originDetail, sourceInv, expectedOutputs, requestedMultiplier, true);
    }

    private static @Nullable ProcessingPatternExtractionResult extractForProcessingPatternInternal(AEProcessingPattern originDetail,
                                                                                                   ICraftingInventory sourceInv,
                                                                                                   KeyCounter expectedOutputs,
                                                                                                   long requestedMultiplier,
                                                                                                   boolean clampToSafeMultiplier) {
        if (requestedMultiplier <= 0L) {
            return null;
        }

        IPatternDetails.IInput[] inputs = originDetail.getInputs();
        KeyCounter[] inputHolder = new KeyCounter[inputs.length];
        Object2LongOpenHashMap<AEKey> required = new Object2LongOpenHashMap<>(inputs.length);
        AEKey[] inputKeys = new AEKey[inputs.length];
        long[] inputAmountsPerOperation = new long[inputs.length];
        boolean[] inputRequiresExtraction = new boolean[inputs.length];

        for (int x = 0; x < inputs.length; x++) {
            GenericStack[] possibleInputs = inputs[x].getPossibleInputs();
            if (possibleInputs.length == 0) {
                return null;
            }

            AEKey key = possibleInputs[0].what();
            long amountPerOperation = getPrimaryInputAmountPerOperation(inputs[x]);
            inputKeys[x] = key;
            inputAmountsPerOperation[x] = amountPerOperation;
            if (amountPerOperation <= 0L) {
                continue;
            }

            inputRequiresExtraction[x] = true;
            // 这里按 AEKey 先做 (a + b) * m，而不是各槽位分别做 a * m + b * m。
            // 对正常整数算术二者等价，但执行期的安全批次、库存预检和真实扣料都是按“同 key 总量”完成；
            // 先聚合同类项才能让重复 key 的上界判断与后续实际提取保持同一口径。
            required.put(key, NumberUtils.saturatedAdd(required.getLong(key), amountPerOperation));
        }

        long appliedMultiplier = requestedMultiplier;
        if (clampToSafeMultiplier && requestedMultiplier > 1L) {
            long maxAmountPerOperation = 0L;
            // AEProcessingPattern 在这条执行路径上会按 AEKey 汇总后再提取/记账，
            // 因此安全批次也必须基于“同类项合并后的每操作总量”来取上界。
            for (var entry : required.object2LongEntrySet()) {
                maxAmountPerOperation = Math.max(maxAmountPerOperation, entry.getLongValue());
            }

            Object2LongOpenHashMap<AEKey> outputPerOperation = new Object2LongOpenHashMap<>(originDetail.getOutputs().length);
            for (GenericStack output : originDetail.getOutputs()) {
                long amount = output.amount();
                if (amount <= 0L) {
                    continue;
                }
                AEKey key = output.what();
                long aggregatedAmount = NumberUtils.saturatedAdd(outputPerOperation.getLong(key), amount);
                outputPerOperation.put(key, aggregatedAmount);
                maxAmountPerOperation = Math.max(maxAmountPerOperation, aggregatedAmount);
            }

            if (maxAmountPerOperation > 0L) {
                appliedMultiplier = Math.min(appliedMultiplier, Long.MAX_VALUE / maxAmountPerOperation);
            }
        }

        if (appliedMultiplier <= 0L) {
            return null;
        }

        for (var entry : required.object2LongEntrySet()) {
            entry.setValue(NumberUtils.saturatedMultiply(entry.getLongValue(), appliedMultiplier));
        }

        for (var entry : required.object2LongEntrySet()) {
            long requiredAmount = entry.getLongValue();
            if (requiredAmount <= 0) continue;
            long available = sourceInv.extract(entry.getKey(), requiredAmount, Actionable.SIMULATE);
            if (available < requiredAmount) {
                return null;
            }
        }

        // 实际提取
        for (int x = 0; x < inputs.length; x++) {
            var list = inputHolder[x] = new KeyCounter();
            GenericStack[] possibleInputs = inputs[x].getPossibleInputs();
            if (possibleInputs.length == 0) {
                return null;
            }
            if (!inputRequiresExtraction[x]) {
                continue;
            }
            long amount = NumberUtils.saturatedMultiply(inputAmountsPerOperation[x], appliedMultiplier);
            long extracted = AEUtils.extractTemplates(sourceInv, inputKeys[x], amount);
            list.add(inputKeys[x], extracted);
            if (extracted < amount) {
                CraftingCpuHelper.reinjectPatternInputs(sourceInv, inputHolder);
                break;
            }
        }

        // 后验证：确保所有 inputHolder 都有效
        for (int x = 0; x < inputHolder.length; x++) {
            var list = inputHolder[x];
            if (list == null) {
                return null;
            }
            if (!inputRequiresExtraction[x]) {
                continue;
            }
            boolean hasAny = false;
            for (var entry : list) {
                hasAny = true;
                if (entry.getLongValue() <= 0) {
                    return null;
                }
            }
            if (!hasAny) {
                return null;
            }
        }

        for (GenericStack output : originDetail.getOutputs()) {
            expectedOutputs.add(output.what(), NumberUtils.saturatedMultiply(output.amount(), appliedMultiplier));
        }
        return new ProcessingPatternExtractionResult(inputHolder, appliedMultiplier);
    }

    public static KeyCounter[] extractForProcessingPattern(AEProcessingPattern originDetail,
                                                           ICraftingInventory sourceInv,
                                                           KeyCounter expectedOutputs,
                                                           long multiplier) {
        ProcessingPatternExtractionResult result = extractForProcessingPatternInternal(originDetail, sourceInv, expectedOutputs,
                multiplier, false);
        return result == null ? null : result.inputHolder();
    }

    private static long extractTemplates(ICraftingInventory inv, AEKey key, long amount) {
        if (amount <= 0) return 0;
        long simEx = inv.extract(key, amount, Actionable.SIMULATE);
        if (simEx <= 0) return 0;
        long extracted = inv.extract(key, simEx, Actionable.MODULATE);
        if (extracted <= 0 || extracted != simEx) {
            throw new IllegalStateException("Failed to correctly extract whole number. Invalid simulation!");
        }
        return extracted;
    }

    public static KeyCounter[] extractForCraftPattern(IPatternDetails details,
                                                      ICraftingInventory sourceInv,
                                                      Level level,
                                                      KeyCounter expectedOutputs,
                                                      KeyCounter expectedContainerItems) {
        var inputs = details.getInputs();
        KeyCounter[] inputHolder = new KeyCounter[inputs.length];
        boolean found = true;

        for (int x = 0; x < inputs.length; x++) {
            var list = inputHolder[x] = new KeyCounter();
            long remainingMultiplier = inputs[x].getMultiplier();
            for (var template : getValidItemTemplates(sourceInv, inputs[x], level)) {
                long extracted = CraftingCpuHelper.extractTemplates(sourceInv, template, remainingMultiplier);
                list.add(template.key(), NumberUtils.saturatedMultiply(extracted, template.amount()));

                var containerItem = inputs[x].getRemainingKey(template.key());
                if (containerItem != null) {
                    expectedContainerItems.add(containerItem, extracted);
                }

                remainingMultiplier -= extracted;
                if (remainingMultiplier <= 0)
                    break;
            }

            if (remainingMultiplier > 0) {
                found = false;
                break;
            }
        }

        if (!found) {
            reinjectPatternInputs(sourceInv, inputHolder);
            return null;
        }

        for (GenericStack output : details.getOutputs()) {
            expectedOutputs.add(output.what(), output.amount());
        }

        return inputHolder;
    }
}
