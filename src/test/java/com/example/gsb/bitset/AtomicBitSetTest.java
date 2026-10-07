package com.example.gsb.bitset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AtomicBitSetTest {

    // ------------------------------------------------------------------
    // 基本位操作
    // ------------------------------------------------------------------

    @Test
    void setGetAndClearSingleBits() {
        AtomicBitSet bits = new AtomicBitSet(128);
        assertThat(bits.get(0)).isFalse();
        assertThat(bits.get(127)).isFalse();

        bits.set(0);
        bits.set(63);
        bits.set(64);
        assertThat(bits.get(0)).isTrue();
        assertThat(bits.get(63)).isTrue();
        assertThat(bits.get(64)).isTrue();
        assertThat(bits.get(1)).isFalse();

        bits.clear(63);
        assertThat(bits.get(63)).isFalse();
        assertThat(bits.get(0)).isTrue();

        // 幂等
        bits.set(0);
        bits.clear(63);
        assertThat(bits.get(0)).isTrue();
        assertThat(bits.get(63)).isFalse();
    }

    @Test
    void rejectsNegativeIndexesAndInvalidRanges() {
        AtomicBitSet bits = new AtomicBitSet();
        assertThatThrownBy(() -> bits.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> bits.set(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> bits.clear(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> bits.testAndSet(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> bits.testAndClear(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> bits.setRange(-1, 5)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> bits.setRange(5, 4)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> bits.clearRange(9, 2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> bits.countRange(3, 1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> new AtomicBitSet(-8)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // 原子复合操作
    // ------------------------------------------------------------------

    @Test
    void testAndSetReturnsPreviousState() {
        AtomicBitSet bits = new AtomicBitSet(64);
        assertThat(bits.testAndSet(7)).isFalse();
        assertThat(bits.get(7)).isTrue();
        assertThat(bits.testAndSet(7)).isTrue();
        assertThat(bits.get(7)).isTrue();
    }

    @Test
    void testAndClearReturnsPreviousState() {
        AtomicBitSet bits = new AtomicBitSet(64);
        bits.set(3);
        assertThat(bits.testAndClear(3)).isTrue();
        assertThat(bits.get(3)).isFalse();
        assertThat(bits.testAndClear(3)).isFalse();
        // 超出容量的位必为 0，且不触发扩容
        assertThat(bits.testAndClear(10_000)).isFalse();
        assertThat(bits.wordCount()).isEqualTo(1);
    }

    @Test
    void getAndSetAllReturnsPreviousStatesInBatch() {
        AtomicBitSet bits = new AtomicBitSet(256);
        bits.set(1);
        bits.set(200);

        BitSet previous = bits.getAndSetAll(1, 2, 200, 201);
        assertThat(previous.get(1)).isTrue();
        assertThat(previous.get(200)).isTrue();
        assertThat(previous.get(2)).isFalse();
        assertThat(previous.get(201)).isFalse();
        assertThat(previous.cardinality()).isEqualTo(2);

        for (int index : new int[] {1, 2, 200, 201}) {
            assertThat(bits.get(index)).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // 区间操作
    // ------------------------------------------------------------------

    @Test
    void setRangeSpansMultipleWords() {
        AtomicBitSet bits = new AtomicBitSet(256);
        bits.setRange(3, 131); // 跨 3 个字
        assertThat(bits.get(2)).isFalse();
        assertThat(bits.get(3)).isTrue();
        assertThat(bits.get(63)).isTrue();
        assertThat(bits.get(64)).isTrue();
        assertThat(bits.get(130)).isTrue();
        assertThat(bits.get(131)).isFalse();
        assertThat(bits.countRange(0, 256)).isEqualTo(128);
    }

    @Test
    void clearRangeClearsOnlyInsideRange() {
        AtomicBitSet bits = new AtomicBitSet(256);
        bits.setRange(0, 200);
        bits.clearRange(50, 150);
        assertThat(bits.countRange(0, 50)).isEqualTo(50);
        assertThat(bits.countRange(50, 150)).isZero();
        assertThat(bits.countRange(150, 200)).isEqualTo(50);
        assertThat(bits.get(49)).isTrue();
        assertThat(bits.get(50)).isFalse();
        assertThat(bits.get(149)).isFalse();
        assertThat(bits.get(150)).isTrue();
    }

    @Test
    void rangeOperationsHandleBoundariesBeyondCapacity() {
        AtomicBitSet bits = new AtomicBitSet(64);
        bits.setRange(0, 64);

        // 清位/计数超出容量：按已有容量截断，不扩容、不报错
        bits.clearRange(32, 1_000_000);
        assertThat(bits.wordCount()).isEqualTo(1);
        assertThat(bits.countRange(0, 1_000_000)).isEqualTo(32);

        // 置位超出容量：自动扩容
        bits.setRange(64, 200);
        assertThat(bits.countRange(0, 1_000_000)).isEqualTo(32 + 136);
        assertThat(bits.capacity()).isGreaterThanOrEqualTo(200);

        // 空区间是 no-op
        bits.setRange(10, 10);
        bits.clearRange(10, 10);
        assertThat(bits.countRange(10, 10)).isZero();
    }

    // ------------------------------------------------------------------
    // 自动扩容
    // ------------------------------------------------------------------

    @Test
    void expandsByWordsAndPreservesExistingBits() {
        AtomicBitSet bits = new AtomicBitSet(64);
        bits.set(0);
        bits.set(63);
        assertThat(bits.wordCount()).isEqualTo(1);

        bits.set(1000); // 需要至少 16 个字
        assertThat(bits.wordCount()).isGreaterThanOrEqualTo(16);
        assertThat(bits.get(0)).isTrue();
        assertThat(bits.get(63)).isTrue();
        assertThat(bits.get(1000)).isTrue();
        assertThat(bits.cardinality()).isEqualTo(3);

        bits.set(70); // 容量内，不再扩容
        int words = bits.wordCount();
        bits.set(71);
        assertThat(bits.wordCount()).isEqualTo(words);
    }

    @Test
    void concurrentExpansionDoesNotLoseBits() throws Exception {
        int threads = 8;
        int bitsPerThread = 2_000;
        int totalBits = threads * bitsPerThread;
        AtomicBitSet bits = new AtomicBitSet(64); // 初始仅 1 个字，强制频繁扩容

        runConcurrently(threads, thread -> {
            // 交错写入，制造扩容与写并发的交错窗口
            for (int i = 0; i < bitsPerThread; i++) {
                bits.set(thread + i * threads);
            }
            // 重叠区域重复置位，进一步制造竞争
            for (int i = 0; i < 500; i++) {
                bits.testAndSet(totalBits - 1 - i);
            }
        });

        assertThat(bits.cardinality()).isEqualTo(totalBits);
        for (int i = 0; i < totalBits; i++) {
            assertThat(bits.get(i)).as("bit %d", i).isTrue();
        }
        assertThat(bits.capacity()).isGreaterThanOrEqualTo(totalBits);
    }

    // ------------------------------------------------------------------
    // 并发正确性：最终状态与串行模拟一致
    // ------------------------------------------------------------------

    @Test
    void concurrentMixedOperationsMatchSerialSimulation() throws Exception {
        int threads = 8;
        int bitsPerThread = 1_024;
        int opsPerThread = 4_000;
        AtomicBitSet bits = new AtomicBitSet(64);

        // 每个线程生成确定性的操作序列（种子固定），操作范围限定在自己的分区内
        List<List<int[]>> allOps = new ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            allOps.add(generateOps(thread, thread * bitsPerThread, bitsPerThread, opsPerThread));
        }

        runConcurrently(threads, thread -> applyOps(bits, allOps.get(thread)));

        // 串行重放同一批操作到普通 BitSet，作为期望结果
        BitSet expected = new BitSet();
        for (List<int[]> ops : allOps) {
            for (int[] op : ops) {
                applyOpSerially(expected, op);
            }
        }

        for (int i = 0; i < threads * bitsPerThread; i++) {
            assertThat(bits.get(i)).as("bit %d", i).isEqualTo(expected.get(i));
        }
        assertThat(bits.cardinality()).isEqualTo(expected.cardinality());
    }

    @Test
    void concurrentTestAndSetOnSameBitYieldsFalseExactlyOnce() throws Exception {
        int threads = 16;
        int bitCount = 64;
        AtomicBitSet bits = new AtomicBitSet(bitCount);
        AtomicInteger falseReturns = new AtomicInteger();

        runConcurrently(threads, thread -> {
            for (int bit = 0; bit < bitCount; bit++) {
                if (!bits.testAndSet(bit)) {
                    falseReturns.incrementAndGet();
                }
            }
        });

        // 每个位在所有线程中恰好有一个线程观察到“之前是 0”
        assertThat(falseReturns.get()).isEqualTo(bitCount);
        assertThat(bits.cardinality()).isEqualTo(bitCount);
    }

    // ------------------------------------------------------------------
    // 并发基数统计
    // ------------------------------------------------------------------

    @Test
    void cardinalityStaysBoundedDuringConcurrentWritesAndIsExactAtQuiescence() throws Exception {
        int threads = 4;
        int bitsPerThread = 5_000;
        int totalBits = threads * bitsPerThread;
        AtomicBitSet bits = new AtomicBitSet(64);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> writers = new ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            int offset = thread * bitsPerThread;
            writers.add(pool.submit(() -> {
                await(start);
                for (int i = 0; i < bitsPerThread; i++) {
                    bits.set(offset + i);
                }
            }));
        }

        // 写入进行时在主线程持续统计：结果必须始终落在 [0, totalBits] 内且单调有界
        start.countDown();
        boolean allDone = false;
        while (!allDone) {
            long cardinality = bits.cardinality();
            assertThat(cardinality).isBetween(0L, (long) totalBits);
            allDone = writers.stream().allMatch(Future::isDone);
        }
        for (Future<?> writer : writers) {
            writer.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // 静默后结果精确
        assertThat(bits.cardinality()).isEqualTo(totalBits);
        assertThat(bits.countRange(0, totalBits)).isEqualTo(totalBits);
    }

    // ------------------------------------------------------------------
    // 统计信息
    // ------------------------------------------------------------------

    @Test
    void tracksSetOperationsCardinalityAndWordCount() {
        AtomicBitSet bits = new AtomicBitSet(128);
        bits.set(1);
        bits.testAndSet(2);
        bits.setRange(10, 20);
        bits.getAndSetAll(30, 31);
        bits.clear(1); // 清位不计入置位次数

        assertThat(bits.setOperationCount()).isEqualTo(4);
        assertThat(bits.cardinality()).isEqualTo(1 + 10 + 2); // 位 1 已被清掉
        assertThat(bits.wordCount()).isEqualTo(2);
        assertThat(bits.casRetryCount()).isGreaterThanOrEqualTo(0);

        AtomicBitSet.Stats stats = bits.stats();
        assertThat(stats.setOperations()).isEqualTo(4);
        assertThat(stats.cardinality()).isEqualTo(13);
        assertThat(stats.wordCount()).isEqualTo(2);
        assertThat(stats.casRetries()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void casRetriesAreCountedUnderContention() throws Exception {
        int threads = 16;
        int opsPerThread = 10_000;
        AtomicBitSet bits = new AtomicBitSet(64); // 所有线程竞争同一个字

        runConcurrently(threads, thread -> {
            for (int i = 0; i < opsPerThread; i++) {
                bits.testAndSet(i % 64);
                if ((i & 1) == 0) {
                    bits.testAndClear(i % 64);
                }
            }
        });

        assertThat(bits.casRetryCount()).isGreaterThan(0);
        assertThat(bits.setOperationCount()).isEqualTo((long) threads * opsPerThread);
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    /** 生成确定性混合操作序列：{类型, 参数1, 参数2}，类型 0=set 1=clear 2=testAndSet 3=testAndClear 4=setRange 5=clearRange。 */
    private static List<int[]> generateOps(int seed, int partitionStart, int partitionSize, int opCount) {
        Random random = new Random(seed);
        List<int[]> ops = new ArrayList<>(opCount);
        for (int i = 0; i < opCount; i++) {
            int type = random.nextInt(6);
            if (type <= 3) {
                ops.add(new int[] {type, partitionStart + random.nextInt(partitionSize), 0});
            } else {
                int from = partitionStart + random.nextInt(partitionSize);
                int to = Math.min(from + 1 + random.nextInt(64), partitionStart + partitionSize);
                ops.add(new int[] {type, from, to});
            }
        }
        return ops;
    }

    private static void applyOps(AtomicBitSet bits, List<int[]> ops) {
        for (int[] op : ops) {
            switch (op[0]) {
                case 0 -> bits.set(op[1]);
                case 1 -> bits.clear(op[1]);
                case 2 -> bits.testAndSet(op[1]);
                case 3 -> bits.testAndClear(op[1]);
                case 4 -> bits.setRange(op[1], op[2]);
                case 5 -> bits.clearRange(op[1], op[2]);
                default -> throw new IllegalStateException();
            }
        }
    }

    private static void applyOpSerially(BitSet bits, int[] op) {
        switch (op[0]) {
            case 0, 2 -> bits.set(op[1]);
            case 1, 3 -> bits.clear(op[1]);
            case 4 -> bits.set(op[1], op[2]);
            case 5 -> bits.clear(op[1], op[2]);
            default -> throw new IllegalStateException();
        }
    }

    private static void runConcurrently(int threads, ThrowingIntConsumer task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            int threadIndex = thread;
            futures.add(pool.submit(() -> {
                await(start);
                try {
                    task.accept(threadIndex);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingIntConsumer {
        void accept(int value) throws Exception;
    }
}
