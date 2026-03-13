package org.gtlcore.gtlcore.integration.ae2.crafting;

/**
 * Duck-typing 接口，通过 Mixin 注入到 {@link appeng.crafting.CraftingPlan}。
 * 用于标记合成计划是否应忽略缺失物品。
 */
public interface ICraftingPlanIgnoreMissingMode {

    boolean gtlcore$isIgnoreMissing();

    void gtlcore$setIgnoreMissing(boolean value);
}
