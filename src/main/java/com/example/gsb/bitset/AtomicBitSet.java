package com.example.gsb.bitset;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * 并发原子位集合（lock-free bitmap）。
 *
 * <p>存储结构：位按 64 位字（word）存储，每 64 个字组成一个固定大小的段
 * （segment，4096 位）。所有段由一个<b>一次性分配的固定段目录</b>索引，
 * 目录覆盖 int 下标的全部范围（2^31-1 位，稀疏场景仅付出约 2MB 引用数组），
 * 段本身按需懒分配。
 *
 * <p>并发语义：
 * <ul>
 *   <li>所有置位/清位均通过字级 CAS 完成，无全局锁；</li>
 *   <li>扩容 = 通过 CAS 向目录发布一个新段。段一经发布永不替换、永不移动，
 *       目录也从不扩容或拷贝，因此不存在"写到过期数组"的丢失窗口；
 *       并发扩容的失败者直接重试并复用胜者发布的段；</li>
 *   <li>{@link #cardinality()} 与 {@link #countRange} 在并发修改下是<b>近似值</b>
 *       （逐字读取，不是一致快照）；无并发修改时结果为精确值。</li>
 * </ul>
 */
public class AtomicBitSet {

    /** 每段的字数（64 字 = 4096 位）。段大小固定，段永不替换。 */
    static final int SEGMENT_WORDS = 64;
    static final int WORD_BITS = Long.SIZE;
    static final int SEGMENT_BITS = SEGMENT_WORDS * WORD_BITS; // 4096

    private static final int WORD_SHIFT = 6;                  // log2(64)
    private static final int SEGMENT_SHIFT = WORD_SHIFT + 6;  // log2(4096)
    private static final int WORD_MASK = SEGMENT_WORDS - 1;

    /** 覆盖 int 下标全范围的段数：2^31-1 位 / 4096 位每段，向上取整。 */
    private static final int MAX_SEGMENTS = (Integer.MAX_VALUE >>> SEGMENT_SHIFT) + 1;

    /**
     * 段目录：构造时一次性分配（仅引用，约 2MB），槽位为懒分配的段。
     * 目录与已发布的段都永不替换，这是无锁正确性的关键。
     */
    private final AtomicReferenceArray<AtomicLongArray> directory =
            new AtomicReferenceArray<>(MAX_SEGMENTS);

    /** 成功将某位由 0 翻为 1 的次数（含单位置位、批量置位与区间置位）。 */
    private final LongAdder setCount = new LongAdder();

    /** CAS 失败重试次数（字级 CAS 失败 + 段发布竞争失败）。 */
    private final LongAdder casRetries = new LongAdder();

    /** 已发布的段数（由发布成功者维护，供统计用）。 */
    private final AtomicInteger publishedSegments = new AtomicInteger();

    // ------------------------------------------------------------------
    // 基础位操作
    // ------------------------------------------------------------------

    /** 读取指定位；尚未分配的区域的位视为 0，不触发扩容。 */
    public boolean get(int index) {
        checkIndex(index);
        AtomicLongArray segment = directory.get(segmentIndex(index));
        if (segment == null) {
            return false;
        }
        return (segment.get(wordIndex(index)) & bitMask(index)) != 0;
    }

    /** 置位；对应段尚未分配时自动扩容（发布新段）。 */
    public void set(int index) {
        testAndSet(index);
    }

    /** 清位；对应段尚未分配时视为无操作（该区域本来就是 0）。 */
    public void clear(int index) {
        testAndClear(index);
    }

    /**
     * 原子地"测试并置位"：返回操作前该位的值，并将该位置为 1。
     */
    public boolean testAndSet(int index) {
        checkIndex(index);
        AtomicLongArray segment = ensureSegment(segmentIndex(index));
        int word = wordIndex(index);
        long mask = bitMask(index);
        while (true) {
            long old = segment.get(word);
            if ((old & mask) != 0) {
                return true;
            }
            if (segment.compareAndSet(word, old, old | mask)) {
                setCount.increment();
                return false;
            }
            casRetries.increment();
        }
    }

    /**
     * 原子地"测试并清位"：返回操作前该位的值，并将该位清为 0。
     */
    public boolean testAndClear(int index) {
        checkIndex(index);
        AtomicLongArray segment = directory.get(segmentIndex(index));
        if (segment == null) {
            return false;
        }
        int word = wordIndex(index);
        long mask = bitMask(index);
        while (true) {
            long old = segment.get(word);
            if ((old & mask) == 0) {
                return false;
            }
            if (segment.compareAndSet(word, old, old & ~mask)) {
                return true;
            }
            casRetries.increment();
        }
    }

    /**
     * 批量置位：对给定下标逐个执行原子的测试并置位。
     *
     * <p>每一位的更新各自原子生效（非整体事务），返回数组给出每一位操作前的状态。
     *
     * @return 与 {@code indices} 等长的数组，第 i 个元素为 indices[i] 操作前的位值
     */
    public boolean[] testAndSetAll(int... indices) {
        boolean[] previous = new boolean[indices.length];
        for (int i = 0; i < indices.length; i++) {
            previous[i] = testAndSet(indices[i]);
        }
        return previous;
    }

    // ------------------------------------------------------------------
    // 区间操作（区间均为 [fromInclusive, toExclusive)）
    // ------------------------------------------------------------------

    /** 将区间 [from, to) 全部置 1，按需扩容。 */
    public void setRange(int from, int to) {
        updateRange(from, to, true);
    }

    /** 将区间 [from, to) 全部清 0；未分配区域为无操作。 */
    public void clearRange(int from, int to) {
        updateRange(from, to, false);
    }

    /**
     * 统计区间 [from, to) 内 1 的个数。
     *
     * <p>并发修改下为近似值（逐字读取，非一致快照）；无并发修改时为精确值。
     */
    public long countRange(int from, int to) {
        checkRange(from, to);
        long count = 0;
        for (int index = from; index < to; ) {
            AtomicLongArray segment = directory.get(segmentIndex(index));
            int word = wordIndex(index);
            int bit = index & (WORD_BITS - 1);
            // 本字内参与统计的结束位（不含）
            int endBit = (int) Math.min(WORD_BITS, (long) bit + (to - index));
            long mask = (-1L << bit) & (endBit == WORD_BITS ? -1L : (1L << endBit) - 1);
            if (segment != null) {
                count += Long.bitCount(segment.get(word) & mask);
            }
            index += endBit - bit;
        }
        return count;
    }

    private void updateRange(int from, int to, boolean value) {
        checkRange(from, to);
        for (int index = from; index < to; ) {
            int segIdx = segmentIndex(index);
            AtomicLongArray segment = value ? ensureSegment(segIdx) : directory.get(segIdx);
            if (segment == null) {
                // 清位且段未分配：整段都是 0，直接跳过本段剩余部分
                index = (segIdx + 1) << SEGMENT_SHIFT;
                continue;
            }
            int word = wordIndex(index);
            int bit = index & (WORD_BITS - 1);
            int endBit = (int) Math.min(WORD_BITS, (long) bit + (to - index));
            long mask = (-1L << bit) & (endBit == WORD_BITS ? -1L : (1L << endBit) - 1);
            while (true) {
                long old = segment.get(word);
                long updated = value ? (old | mask) : (old & ~mask);
                if (updated == old) {
                    break;
                }
                if (segment.compareAndSet(word, old, updated)) {
                    if (value) {
                        setCount.add(Long.bitCount(updated & ~old));
                    }
                    break;
                }
                casRetries.increment();
            }
            index += endBit - bit;
        }
    }

    // ------------------------------------------------------------------
    // 基数与统计
    // ------------------------------------------------------------------

    /**
     * 统计当前置为 1 的位数。
     *
     * <p><b>近似值</b>：统计过程中允许并发修改，逐字读取、不做全局同步，
     * 因此结果不是某一时刻的一致快照，而是介于统计起止之间某个状态的近似。
     * 无并发修改时返回精确值。
     */
    public long cardinality() {
        long count = 0;
        int published = publishedSegments.get();
        for (int s = 0; s < MAX_SEGMENTS && published > 0; s++) {
            AtomicLongArray segment = directory.get(s);
            if (segment == null) {
                continue;
            }
            published--;
            for (int w = 0; w < SEGMENT_WORDS; w++) {
                count += Long.bitCount(segment.get(w));
            }
        }
        return count;
    }

    /** 成功将位由 0 翻为 1 的总次数。 */
    public long setCount() {
        return setCount.sum();
    }

    /** CAS 失败导致的重试总次数（含段发布竞争）。 */
    public long casRetryCount() {
        return casRetries.sum();
    }

    /** 当前已分配的字数（已发布段数 × 每段字数）。 */
    public long wordCount() {
        return (long) publishedSegments.get() * SEGMENT_WORDS;
    }

    /** 当前已分配的位数（已分配字数 × 64）。 */
    public long bitCapacity() {
        return wordCount() * WORD_BITS;
    }

    // ------------------------------------------------------------------
    // 内部：寻址与扩容
    // ------------------------------------------------------------------

    private static int segmentIndex(int index) {
        return index >>> SEGMENT_SHIFT;
    }

    private static int wordIndex(int index) {
        return (index >>> WORD_SHIFT) & WORD_MASK;
    }

    private static long bitMask(int index) {
        return 1L << index; // 等价于 1L << (index & 63)
    }

    /**
     * 确保给定段存在并返回它（自动扩容的唯一入口）。
     *
     * <p>并发扩容处理：段目录在构造时一次性分配且永不扩容，扩容只是向空槽位
     * CAS 发布一个新段。段对象一经发布永不替换，因此任何线程拿到的段引用
     * 永远有效，不存在"写到过期数组"的丢失窗口；发布竞争失败的一方重试后
     * 直接复用胜者发布的段，位数据不会丢失。
     */
    private AtomicLongArray ensureSegment(int segIdx) {
        AtomicLongArray segment = directory.get(segIdx);
        if (segment != null) {
            return segment;
        }
        AtomicLongArray candidate = new AtomicLongArray(SEGMENT_WORDS);
        if (directory.compareAndSet(segIdx, null, candidate)) {
            publishedSegments.incrementAndGet();
            return candidate;
        }
        casRetries.increment(); // 与其他发布者竞争失败，复用胜者的段
        return directory.get(segIdx);
    }

    private static void checkIndex(int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("index must be >= 0: " + index);
        }
    }

    private static void checkRange(int from, int to) {
        if (from < 0 || to < from) {
            throw new IndexOutOfBoundsException("invalid range [" + from + ", " + to + ")");
        }
    }
}
