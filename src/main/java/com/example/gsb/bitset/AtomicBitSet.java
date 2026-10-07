package com.example.gsb.bitset;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongUnaryOperator;

/**
 * 基于 CAS 的并发原子位集合（lock-free，无全局锁）。
 *
 * <p>存储模型：位按 64 位一“字”存放在 {@link AtomicLongArray} 中，所有置位/清位
 * 均通过对单个字的 {@code compareAndSet} 自旋完成，不使用任何锁。
 *
 * <p>自动扩容：底层数组引用保存在 {@link AtomicReference} 中。下标超出容量时，
 * 线程按“当前字数翻倍且足以容纳目标字”的规则分配新数组、拷贝旧内容，然后对数组引用
 * 做一次 CAS 安装。并发扩容时多个线程可能各自分配新数组，只有一个 CAS 成功，
 * 失败者丢弃自己的副本并改用胜者已安装的数组，因此扩容本身不需要锁。
 *
 * <p>防丢位：扩容的“拷贝→安装”与写线程的“字 CAS”交错时会丢位，采用双向兜底：
 * <ul>
 *   <li>写路径：字 CAS 成功后再校验数组引用是否仍指向自己写入的数组；若引用已变
 *       （写入落在安装之后），本次写入可能未被拷贝，在新数组上重试（置位/清位幂等，
 *       重试安全）。</li>
 *   <li>扩容路径：安装新数组后再做一遍差异迁移，把“拷贝之后、安装之前”落在旧数组
 *       上的变更（其写线程可能已校验通过并返回）合并进当前数组。</li>
 * </ul>
 * 数组只增不换回旧对象，不存在 ABA 问题。
 *
 * <p>基数一致性：{@link #cardinality()} 与 {@link #countRange(int, int)} 在统计期间
 * 允许并发修改，返回的是<b>近似值</b>而非一致快照——每个字的读取是原子的，
 * 但不同字读取于不同时间点，且统计期间可能发生扩容。只有在无并发写时结果才精确。
 */
public class AtomicBitSet {

    private static final int BITS_PER_WORD = Long.SIZE;
    private static final int WORD_SHIFT = 6;

    /** 当前字数组，扩容时整体 CAS 替换；只增不减。 */
    private final AtomicReference<AtomicLongArray> words;

    /** 置位类操作（set/testAndSet/setRange/getAndSetAll）完成的调用次数。 */
    private final LongAdder setOperations = new LongAdder();

    /** 字级 CAS 失败（发生写竞争）导致重试的累计次数。 */
    private final LongAdder casRetries = new LongAdder();

    /** 创建初始容量为 1 个字（64 位）的位集合。 */
    public AtomicBitSet() {
        this(BITS_PER_WORD);
    }

    /**
     * 创建初始容量至少能容纳 {@code initialBits} 位的位集合（按字对齐，至少 1 个字）。
     */
    public AtomicBitSet(int initialBits) {
        if (initialBits < 0) {
            throw new IllegalArgumentException("initialBits must be >= 0: " + initialBits);
        }
        int wordCount = Math.max(1, (initialBits + BITS_PER_WORD - 1) / BITS_PER_WORD);
        this.words = new AtomicReference<>(new AtomicLongArray(wordCount));
    }

    // ------------------------------------------------------------------
    // 基本位操作
    // ------------------------------------------------------------------

    /** 读取指定位，超出当前容量的位视为 0。 */
    public boolean get(int bitIndex) {
        checkBitIndex(bitIndex);
        AtomicLongArray arr = words.get();
        int wordIndex = wordIndex(bitIndex);
        if (wordIndex >= arr.length()) {
            return false;
        }
        return (arr.get(wordIndex) & bitMask(bitIndex)) != 0L;
    }

    /** 置位（幂等）。下标越界时自动扩容。 */
    public void set(int bitIndex) {
        internalTestAndSet(bitIndex);
        setOperations.increment();
    }

    /** 清位（幂等）。超出当前容量的位本来就是 0，直接返回。 */
    public void clear(int bitIndex) {
        internalTestAndClear(bitIndex);
    }

    // ------------------------------------------------------------------
    // 原子复合操作（返回操作前的状态）
    // ------------------------------------------------------------------

    /**
     * 原子地“测试并置位”：返回该位操作前的值，并把它置为 1。下标越界时自动扩容。
     */
    public boolean testAndSet(int bitIndex) {
        boolean previous = internalTestAndSet(bitIndex);
        setOperations.increment();
        return previous;
    }

    /**
     * 原子地“测试并清位”：返回该位操作前的值，并把它清为 0。
     * 下标超出当前容量时位必为 0，直接返回 {@code false}，不触发扩容。
     */
    public boolean testAndClear(int bitIndex) {
        return internalTestAndClear(bitIndex);
    }

    /**
     * 批量置位：把 {@code bitIndexes} 中的位全部置 1，并返回这些位<b>操作前</b>的状态。
     *
     * <p>返回的 {@link BitSet} 中，位 {@code i} 为 1 当且仅当 {@code bitIndexes} 中的
     * 位 {@code i} 在本次批量操作前已经是 1。每一位的更新都是独立 CAS 原子完成的，
     * 整体不加锁。
     */
    public BitSet getAndSetAll(int... bitIndexes) {
        BitSet previous = new BitSet();
        for (int bitIndex : bitIndexes) {
            if (internalTestAndSet(bitIndex)) {
                previous.set(bitIndex);
            }
        }
        setOperations.increment();
        return previous;
    }

    // ------------------------------------------------------------------
    // 区间操作（区间均为 [fromInclusive, toExclusive)）
    // ------------------------------------------------------------------

    /** 把区间 {@code [fromInclusive, toExclusive)} 内的位全部置 1，必要时自动扩容。 */
    public void setRange(int fromInclusive, int toExclusive) {
        checkRange(fromInclusive, toExclusive);
        if (fromInclusive == toExclusive) {
            return;
        }
        int firstWord = wordIndex(fromInclusive);
        int lastWord = wordIndex(toExclusive - 1);
        ensureCapacity(lastWord);
        for (int word = firstWord; word <= lastWord; word++) {
            long mask = rangeMask(word, fromInclusive, toExclusive);
            getAndUpdateWord(word, old -> old | mask);
        }
        setOperations.increment();
    }

    /** 把区间 {@code [fromInclusive, toExclusive)} 内的位全部清 0；超出容量的部分视为已是 0。 */
    public void clearRange(int fromInclusive, int toExclusive) {
        checkRange(fromInclusive, toExclusive);
        if (fromInclusive == toExclusive) {
            return;
        }
        int firstWord = wordIndex(fromInclusive);
        int lastWord = Math.min(wordIndex(toExclusive - 1), words.get().length() - 1);
        for (int word = firstWord; word <= lastWord; word++) {
            long mask = rangeMask(word, fromInclusive, toExclusive);
            getAndUpdateWord(word, old -> old & ~mask);
        }
    }

    /**
     * 统计区间 {@code [fromInclusive, toExclusive)} 内为 1 的位数。
     *
     * <p>统计期间允许并发修改，返回值为<b>近似值</b>（逐字原子读取，但非一致快照）；
     * 无并发写时结果精确。超出当前容量的部分按 0 计。
     */
    public long countRange(int fromInclusive, int toExclusive) {
        checkRange(fromInclusive, toExclusive);
        if (fromInclusive == toExclusive) {
            return 0L;
        }
        AtomicLongArray arr = words.get();
        int firstWord = wordIndex(fromInclusive);
        int lastWord = Math.min(wordIndex(toExclusive - 1), arr.length() - 1);
        long count = 0L;
        for (int word = firstWord; word <= lastWord; word++) {
            count += Long.bitCount(arr.get(word) & rangeMask(word, fromInclusive, toExclusive));
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 基数与快照
    // ------------------------------------------------------------------

    /**
     * 统计整个位集合中为 1 的位数。
     *
     * <p>统计期间允许并发修改，返回值为<b>近似值</b>而非一致快照：方法开始时读取一次
     * 数组引用，随后逐字原子读取求和，期间其他线程的增删与扩容都会被部分观察到。
     * 无并发写时结果精确。
     */
    public long cardinality() {
        AtomicLongArray arr = words.get();
        long count = 0L;
        for (int word = 0; word < arr.length(); word++) {
            count += Long.bitCount(arr.get(word));
        }
        return count;
    }

    /**
     * 导出当前内容为普通 {@link BitSet}。与 {@link #cardinality()} 一样，
     * 并发修改下得到的是逐字读取拼出的弱一致视图，不是某一时刻的一致快照。
     */
    public BitSet toBitSet() {
        AtomicLongArray arr = words.get();
        long[] snapshot = new long[arr.length()];
        for (int word = 0; word < arr.length(); word++) {
            snapshot[word] = arr.get(word);
        }
        return BitSet.valueOf(snapshot);
    }

    // ------------------------------------------------------------------
    // 统计信息
    // ------------------------------------------------------------------

    /** 置位类操作已完成的调用次数（set / testAndSet / setRange / getAndSetAll 各计 1 次）。 */
    public long setOperationCount() {
        return setOperations.sum();
    }

    /** 字级 CAS 因竞争失败而重试的累计次数。 */
    public long casRetryCount() {
        return casRetries.sum();
    }

    /** 当前容量对应的字数。 */
    public int wordCount() {
        return words.get().length();
    }

    /** 当前容量（位）= 字数 × 64。 */
    public long capacity() {
        return (long) words.get().length() * BITS_PER_WORD;
    }

    /** 统计信息快照：置位次数、CAS 失败重试次数、当前位数（cardinality）与字数。 */
    public Stats stats() {
        return new Stats(setOperationCount(), casRetryCount(), cardinality(), wordCount());
    }

    /**
     * 统计信息。注意各字段在不同时刻采样，并发修改下彼此之间不保证是同一瞬态的值。
     *
     * @param setOperations 置位类操作完成次数
     * @param casRetries    字级 CAS 失败重试累计次数
     * @param cardinality   当前为 1 的位数（并发修改下为近似值）
     * @param wordCount     当前字数
     */
    public record Stats(long setOperations, long casRetries, long cardinality, int wordCount) {
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private boolean internalTestAndSet(int bitIndex) {
        checkBitIndex(bitIndex);
        int wordIndex = wordIndex(bitIndex);
        long mask = bitMask(bitIndex);
        ensureCapacity(wordIndex);
        long previous = getAndUpdateWord(wordIndex, old -> old | mask);
        return (previous & mask) != 0L;
    }

    private boolean internalTestAndClear(int bitIndex) {
        checkBitIndex(bitIndex);
        int wordIndex = wordIndex(bitIndex);
        long mask = bitMask(bitIndex);
        if (wordIndex >= words.get().length()) {
            return false; // 超出容量的位必为 0，无需扩容
        }
        long previous = getAndUpdateWord(wordIndex, old -> old & ~mask);
        return (previous & mask) != 0L;
    }

    /**
     * 对第 {@code wordIndex} 个字执行 CAS 自旋更新，返回更新前观察到的字值。
     *
     * <p>要求调用方已确保容量足够。{@code operator} 必须幂等（如置位/清位）：
     * 若字 CAS 成功后发现数组引用已被扩容替换，本次写入可能未被拷贝进新数组，
     * 需要在新数组上重试。
     */
    private long getAndUpdateWord(int wordIndex, LongUnaryOperator operator) {
        while (true) {
            AtomicLongArray arr = words.get();
            long oldWord = arr.get(wordIndex);
            long newWord = operator.applyAsLong(oldWord);
            if (newWord == oldWord) {
                if (words.get() == arr) {
                    return oldWord; // 无变化且视图未过期，结果已稳定
                }
                continue; // 数组已扩容，换到新数组上重新确认
            }
            if (arr.compareAndSet(wordIndex, oldWord, newWord)) {
                if (words.get() == arr) {
                    return oldWord; // 写入落在当前数组上，已生效
                }
                // 写入落在已被替换的旧数组上，可能丢失，重试
            } else {
                casRetries.increment();
            }
        }
    }

    /**
     * 确保字数组至少能容纳 {@code wordIndex}，返回满足条件的数组。
     *
     * <p>并发扩容处理：多个线程可能同时发现容量不足并各自分配新数组，
     * 通过对数组引用的 CAS 保证只有一个安装成功；失败者重新读取引用，
     * 若已满足容量则直接使用胜者安装的数组，否则基于最新数组再次尝试。
     * 已有位在扩容时逐字拷贝，配合写路径的“写后校验引用”机制保证不丢位。
     */
    private AtomicLongArray ensureCapacity(int wordIndex) {
        AtomicLongArray current = words.get();
        while (wordIndex >= current.length()) {
            int newLength = Math.max(current.length() * 2, wordIndex + 1);
            AtomicLongArray expanded = new AtomicLongArray(newLength);
            long[] copied = new long[current.length()];
            for (int word = 0; word < current.length(); word++) {
                copied[word] = current.get(word);
                expanded.set(word, copied[word]);
            }
            if (words.compareAndSet(current, expanded)) {
                transferChangesSinceCopy(current, copied);
                return expanded;
            }
            current = words.get(); // 其他线程扩容成功，基于最新数组重新判断
        }
        return current;
    }

    /**
     * 扩容安装新数组后调用：把旧数组上“拷贝之后、安装之前”发生的变更迁移到当前数组。
     *
     * <p>旧数组上的写入按发生时间分两类：安装之前的写入一定会被这里的重读看到并迁移；
     * 安装之后的写入，其写线程会在写后校验中发现数组引用已变而自行重试到当前数组。
     * 因此单遍扫描即可保证不丢位。迁移本身走 {@link #getAndUpdateWord}，
     * 始终作用于最新数组，级联扩容（连续多次扩容）下同样安全。
     */
    private void transferChangesSinceCopy(AtomicLongArray oldArray, long[] copied) {
        for (int word = 0; word < copied.length; word++) {
            long current = oldArray.get(word);
            long changed = current ^ copied[word];
            if (changed == 0L) {
                continue;
            }
            long changedMask = changed;
            long changedValue = current & changedMask;
            getAndUpdateWord(word, newWord -> (newWord & ~changedMask) | changedValue);
        }
    }

    private static int wordIndex(int bitIndex) {
        return bitIndex >>> WORD_SHIFT;
    }

    private static long bitMask(int bitIndex) {
        return 1L << (bitIndex & (BITS_PER_WORD - 1));
    }

    /** 计算第 {@code word} 个字内、落在区间 [fromInclusive, toExclusive) 中的位掩码。 */
    private static long rangeMask(int word, int fromInclusive, int toExclusive) {
        int wordStart = word * BITS_PER_WORD;
        int lowBit = Math.max(fromInclusive, wordStart) - wordStart;
        int highBit = Math.min(toExclusive, wordStart + BITS_PER_WORD) - wordStart;
        long lowMask = -1L << lowBit;
        long highMask = highBit == BITS_PER_WORD ? -1L : (1L << highBit) - 1L;
        return lowMask & highMask;
    }

    private static void checkBitIndex(int bitIndex) {
        if (bitIndex < 0) {
            throw new IndexOutOfBoundsException("bitIndex must be >= 0: " + bitIndex);
        }
    }

    private static void checkRange(int fromInclusive, int toExclusive) {
        if (fromInclusive < 0 || toExclusive < fromInclusive) {
            throw new IndexOutOfBoundsException(
                    "invalid range [" + fromInclusive + ", " + toExclusive + ")");
        }
    }
}
