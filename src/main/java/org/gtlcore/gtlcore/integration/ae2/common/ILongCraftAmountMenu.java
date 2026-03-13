package org.gtlcore.gtlcore.integration.ae2.common;

import appeng.api.stacks.AEKey;

public interface ILongCraftAmountMenu {

    void gtlcore$confirmLong(long amount, boolean craftMissingAmount, boolean autoStart);

    void gtlcore$setWhatToCraftLong(AEKey whatToCraft, long amount);
}
