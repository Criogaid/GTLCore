package org.gtlcore.gtlcore.common.machine.multiblock.part.ae;

import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;

import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.chance.logic.ChanceLogic;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;

import appeng.api.stacks.AEItemKey;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Collections;
import java.util.List;

public class MECraftHandler extends NotifiableMAHandlerTrait {

    public MECraftHandler(MEMolecularAssemblerIOPartMachine machine) {
        super(machine);
    }

    public MEMolecularAssemblerIOPartMachine getMachine() {
        return (MEMolecularAssemblerIOPartMachine) this.machine;
    }

    @Override
    public void handleRecipeOutput(GTRecipe recipe) {
        for (Content content : recipe.outputs.getOrDefault(ItemRecipeCapability.CAP, Collections.emptyList())) {
            if (content.content instanceof LongIngredient longIngredient) {
                getMachine().gtlcore$addToBuffer(AEItemKey.of(longIngredient.getItems()[0]),
                        longIngredient.getActualAmount());
            }
        }
        getMachine().getMETrait().notifySelfIO();
    }

    @Override
    public GTRecipe extractGTRecipe(long parallelAmount, int tickDuration) {
        GTRecipe output = GTRecipeBuilder.ofRaw().buildRawRecipe();
        List<Content> outputList = output.outputs.computeIfAbsent(ItemRecipeCapability.CAP, cap -> new ObjectArrayList<>());
        long remain = parallelAmount;
        for (var key : getMachine().gtlcore$getOutputKeysSnapshot()) {
            if (remain <= 0) break;
            if (!(key.what() instanceof AEItemKey aeItemKey)) {
                getMachine().gtlcore$removeOutput(key);
                continue;
            }

            Item item = aeItemKey.getItem();
            long multiply = getMachine().getOutputItems().getLong(key);
            if (multiply <= 0L) {
                getMachine().gtlcore$removeOutput(key);
                continue;
            }

            long extract = Math.min(multiply, remain);
            long amountPerOutput = key.amount();
            if (amountPerOutput <= 0L) {
                continue;
            }
            long maxExtractForAmount = Long.MAX_VALUE / amountPerOutput;
            if (maxExtractForAmount <= 0L) {
                continue;
            }
            extract = Math.min(extract, maxExtractForAmount);
            long ingredientAmount = extract * amountPerOutput;

            var cont = new Content(LongIngredient.create(Ingredient.of(item), ingredientAmount),
                    ChanceLogic.getMaxChancedValue(), ChanceLogic.getMaxChancedValue(), 0, null, null);
            outputList.add(cont);

            long consumed = getMachine().gtlcore$consumeOutput(key, extract);
            if (consumed <= 0L) {
                continue;
            }
            remain -= consumed;
        }
        if (outputList.isEmpty()) return null;
        else {
            output.duration = tickDuration;
            return output;
        }
    }
}
