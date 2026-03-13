package org.gtlcore.gtlcore.utils;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;

import java.math.BigInteger;
import java.util.function.Function;

/**
 * 维护“long 主账本 + BigInteger 溢出账本”的计数工具。
 * 只用于非负累计场景，避免 Long 溢出导致的静默吞量。
 */
public final class OverflowLongCounter {

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final String EXTRA_KEY = "extra";

    private OverflowLongCounter() {}

    public static <K> void addPositive(Object2LongMap<K> base, Object2ObjectMap<K, BigInteger> overflow, K key,
                                       long amount) {
        if (key == null || amount <= 0L) return;

        long current = base.getLong(key);
        if (current < 0L) {
            current = 0L;
            base.removeLong(key);
        }

        BigInteger total = BigInteger.valueOf(current).add(overflow.getOrDefault(key, ZERO))
                .add(BigInteger.valueOf(amount));

        if (total.compareTo(LONG_MAX) > 0) {
            base.put(key, Long.MAX_VALUE);
            overflow.put(key, total.subtract(LONG_MAX));
        } else {
            long value = total.longValue();
            if (value > 0L) {
                base.put(key, value);
            } else {
                base.removeLong(key);
            }
            overflow.remove(key);
        }
    }

    public static <K> long consume(Object2LongMap<K> base, Object2ObjectMap<K, BigInteger> overflow, K key,
                                   long requested) {
        if (key == null || requested <= 0L) return 0L;

        long current = base.getLong(key);
        if (current < 0L) {
            current = 0L;
            base.removeLong(key);
        }

        BigInteger extra = overflow.getOrDefault(key, ZERO);
        if (current == 0L && extra.signum() <= 0) return 0L;

        long baseTaken = Math.min(requested, current);
        long remainingRequest = requested - baseTaken;
        long extraTaken = 0L;

        if (remainingRequest > 0L && extra.signum() > 0) {
            BigInteger req = BigInteger.valueOf(remainingRequest);
            if (extra.compareTo(req) >= 0) {
                extraTaken = remainingRequest;
                extra = extra.subtract(req);
            } else {
                // remainingRequest <= Long.MAX_VALUE，此分支下 extra < remainingRequest，安全转 long。
                extraTaken = extra.longValue();
                extra = ZERO;
            }
        }

        long newBase = current - baseTaken;

        if (newBase < Long.MAX_VALUE && extra.signum() > 0) {
            long room = Long.MAX_VALUE - newBase;
            if (room > 0L) {
                BigInteger roomBi = BigInteger.valueOf(room);
                long refill = extra.compareTo(roomBi) >= 0 ? room : extra.longValue();
                if (refill > 0L) {
                    newBase += refill;
                    extra = extra.subtract(BigInteger.valueOf(refill));
                }
            }
        }

        if (newBase > 0L) {
            base.put(key, newBase);
        } else {
            base.removeLong(key);
        }

        if (extra.signum() > 0) {
            overflow.put(key, extra);
        } else {
            overflow.remove(key);
        }

        return baseTaken + extraTaken;
    }

    public static <K> void rebalance(Object2LongMap<K> base, Object2ObjectMap<K, BigInteger> overflow) {
        for (var it = overflow.object2ObjectEntrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            var key = entry.getKey();
            var extra = entry.getValue();
            if (extra == null || extra.signum() <= 0) {
                it.remove();
                continue;
            }

            long current = base.getLong(key);
            if (current < 0L) {
                current = 0L;
                base.removeLong(key);
            }

            if (current < Long.MAX_VALUE) {
                long room = Long.MAX_VALUE - current;
                if (room > 0L) {
                    BigInteger roomBi = BigInteger.valueOf(room);
                    long refill = extra.compareTo(roomBi) >= 0 ? room : extra.longValue();
                    if (refill > 0L) {
                        current += refill;
                        extra = extra.subtract(BigInteger.valueOf(refill));
                    }
                }
            }

            if (current > 0L) {
                base.put(key, current);
            } else {
                base.removeLong(key);
            }

            if (extra.signum() > 0) {
                entry.setValue(extra);
            } else {
                it.remove();
            }
        }
    }

    public static <K> boolean isEmpty(Object2LongMap<K> base, Object2ObjectMap<K, BigInteger> overflow) {
        return base.isEmpty() && overflow.isEmpty();
    }

    public static <K> void writeOverflow(CompoundTag root, String listName, Object2ObjectMap<K, BigInteger> overflow,
                                         Function<K, CompoundTag> keySerializer) {
        if (overflow.isEmpty()) return;
        ListTag list = new ListTag();
        for (var entry : overflow.object2ObjectEntrySet()) {
            var extra = entry.getValue();
            if (extra == null || extra.signum() <= 0) continue;
            var tag = keySerializer.apply(entry.getKey()).copy();
            tag.putString(EXTRA_KEY, extra.toString());
            list.add(tag);
        }
        if (!list.isEmpty()) {
            root.put(listName, list);
        }
    }

    public static <K> void readOverflow(CompoundTag root, String listName, Object2ObjectMap<K, BigInteger> overflow,
                                        Function<CompoundTag, K> keyDeserializer) {
        overflow.clear();
        if (!root.contains(listName, Tag.TAG_LIST)) return;
        ListTag list = root.getList(listName, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            var tag = list.getCompound(i);
            var key = keyDeserializer.apply(tag);
            if (key == null) continue;

            String extraText = tag.getString(EXTRA_KEY);
            if (extraText.isEmpty()) continue;
            try {
                var extra = new BigInteger(extraText);
                if (extra.signum() > 0) {
                    overflow.put(key, extra);
                }
            } catch (NumberFormatException ignored) {
                // 忽略损坏条目，避免加载期崩溃。
            }
        }
    }
}
