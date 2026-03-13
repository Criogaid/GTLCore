package org.gtlcore.gtlcore.integration.ae2.async;

import org.gtlcore.gtlcore.utils.NumberUtils;

import appeng.api.stacks.AEKey;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import lombok.Getter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class AEAccumulator {

    @Getter
    private final ConcurrentHashMap<AEKey, LongAdder> acc = new ConcurrentHashMap<>();

    public void add(AEKey key, long delta) {
        if (key == null || delta == 0) return;
        acc.computeIfAbsent(key, k -> new LongAdder()).add(delta);
    }

    public void drainTo(Object2LongOpenHashMap<AEKey> buffer) {
        for (var it = acc.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            long d = e.getValue().sumThenReset();
            if (d != 0) buffer.mergeLong(e.getKey(), d, NumberUtils::saturatedAdd);
            if (e.getValue().sum() == 0) it.remove();
        }
    }

    public void clear() {
        acc.clear();
    }

    public boolean isEmpty() {
        return acc.isEmpty();
    }
}
