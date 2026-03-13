package org.gtlcore.gtlcore.integration.ae2.common;

public class LongConfirmRequest {

    public long amount;
    public boolean craftMissingAmount;
    public boolean autoStart;

    public LongConfirmRequest() {}

    public LongConfirmRequest(long amount, boolean craftMissingAmount, boolean autoStart) {
        this.amount = amount;
        this.craftMissingAmount = craftMissingAmount;
        this.autoStart = autoStart;
    }
}
