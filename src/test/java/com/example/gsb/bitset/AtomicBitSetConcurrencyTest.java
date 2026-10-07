package com.example.gsb.bitset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 并发正确性测试：
 * <ul>
 *   <li>不相交区间上的操作与串行执行结果一致（最终状态等价）；</li>
 *   <li>共享字上的 CAS 竞争最终状态确定；</li>
 *   <li>并发扩容不丢位；</li>
 *   <li>并发基数统计的结果始终在合法范围内，静默后为精确值；</li>
 *   <li>压测下 CAS 失败重试计数被记录。</li>
 * </ul>
 */
class AtomicBitSetConcurrencyTest {

    private static final int THREADS = 8;
    private static final int REGION_BITS = 8192; // 每个线程独占的位区间（2 段）

    /** 简单操作记录，用于串行回放（oracle）。 */
    private record Op(boolean value, int from, int toExclusive) { }

    @Test
    void finalStateMatchesSerialExecutionUnderStress() throws Exception {
        AtomicBitSet bits = new AtomicBitSet();
        int totalBits = THREADS * REGION_BITS;
        List<List<Op>> perThreadOps = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            Random rng = new Random(0xC0FFEEL ^ t);
            List<Op> ops = new ArrayList<>();
            int base = t * REGION_BITS;
            for (int k = 0; k < 3_000; k++) {
                int a = base + rng.nextInt(REGION_BITS);
                int b = base + rng.nextInt(REGION_BITS);
                if (a == b) {
                    b = Math.min(base + REGION_BITS, a + 1 + rng.nextInt(64));
                }
                int from = Math.min(a, b);
                int to = Math.max(a, b);
                // 限制在本线程独占区间内
                to = Math.min(to, base + REGION_BITS);
                ops.add(new Op(rng.nextBoolean(), from, to));
            }
            perThreadOps.add(ops);
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicLong errors = new AtomicLong();

        for (int t = 0; t < THREADS; t++) {
            int tid = t;
            pool.submit(() -> {
                try {
                    await(start);
                    for (Op op : perThreadOps.get(tid)) {
                        if (op.from + 1 == op.toExclusive()) {
                            if (op.value()) {
                                bits.set(op.from);
                            } else {
                                bits.clear(op.from);
                            }
                        } else {
                            if (op.value()) {
                                bits.setRange(op.from, op.toExclusive());
                            } else {
                                bits.clearRange(op.from, op.toExclusive());
                            }
                        }
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(errors.get()).isZero();

        // 串行回放同一批操作（各线程区间不相交，操作可交换，结果即任一交错的最终状态）
        java.util.BitSet expected = new java.util.BitSet(totalBits);
        for (List<Op> ops : perThreadOps) {
            for (Op op : ops) {
                expected.set(op.from, op.toExclusive(), op.value());
            }
        }

        for (int i = 0; i < totalBits; i++) {
            assertThat(bits.get(i)).as("bit %d", i).isEqualTo(expected.get(i));
        }
        assertThat(bits.cardinality()).isEqualTo(expected.cardinality());
        assertThat(bits.countRange(0, totalBits)).isEqualTo(expected.cardinality());
    }

    @Test
    void sharedWordContentionConverges() throws Exception {
        AtomicBitSet bits = new AtomicBitSet();
        int bitsPerThread = 16; // 全部落在同一个字（word 0）内
        int rounds = 5_000;

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch phaseOneStart = new CountDownLatch(1);
        CountDownLatch phaseOneDone = new CountDownLatch(THREADS);
        CountDownLatch phaseTwoStart = new CountDownLatch(1);
        CountDownLatch phaseTwoDone = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            int tid = t;
            pool.submit(() -> {
                try {
                    await(phaseOneStart);
                    for (int r = 0; r < rounds; r++) {
                        for (int b = 0; b < bitsPerThread; b++) {
                            bits.testAndSet(tid * bitsPerThread + b);
                        }
                    }
                    phaseOneDone.countDown();

                    await(phaseTwoStart);
                    for (int r = 0; r < rounds; r++) {
                        for (int b = 0; b < bitsPerThread; b++) {
                            bits.testAndClear(tid * bitsPerThread + b);
                        }
                    }
                } finally {
                    phaseTwoDone.countDown();
                }
            });
        }

        phaseOneStart.countDown();
        assertThat(phaseOneDone.await(60, TimeUnit.SECONDS)).isTrue();
        int total = THREADS * bitsPerThread;
        // 所有 testAndSet 至少生效一次：区间计数为精确值（线程已在屏障处）
        assertThat(bits.countRange(0, total)).isEqualTo(total);
        long observedSets = bits.setCount();
        // testAndSet 返回的旧值：每位第一次必为 false，后续为 true；置位次数恰为 total
        assertThat(observedSets).isEqualTo(total);

        phaseTwoStart.countDown();
        assertThat(phaseTwoDone.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(bits.countRange(0, total)).isZero();
    }

    @Test
    void concurrentGrowthLosesNoBits() throws Exception {
        AtomicBitSet bits = new AtomicBitSet();
        int farIndexStride = 1 << 16; // 各线程写不同段
        int perThread = 64;

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            int tid = t;
            pool.submit(() -> {
                try {
                    await(start);
                    for (int k = 0; k < perThread; k++) {
                        int idx = (tid * perThread + k) * farIndexStride;
                        // 写一些位，再用区间操作写同段内的位，覆盖扩容 + 位更新交错
                        bits.set(idx);
                        bits.setRange(idx + 1, Math.min(idx + 64, idx + 64));
                    }
                } finally {
                    finish.countDown();
                }
            });
        }
        start.countDown();
        assertThat(finish.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        for (int t = 0; t < THREADS; t++) {
            for (int k = 0; k < perThread; k++) {
                int idx = (t * perThread + k) * farIndexStride;
                for (int b = 0; b < 64; b++) {
                    assertThat(bits.get(idx + b))
                        .as("bit %d (thread %d, k %d)", idx + b, t, k).isTrue();
                }
            }
        }
        assertThat(bits.cardinality()).isEqualTo((long) THREADS * perThread * 64);
    }

    @Test
    void cardinalityStaysBoundedDuringConcurrentMutation() throws Exception {
        AtomicBitSet bits = new AtomicBitSet();
        int n = 50_000;

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            int tid = t;
            pool.submit(() -> {
                try {
                    await(start);
                    for (int i = tid; i < n; i += THREADS) {
                        bits.set(i);
                    }
                } finally {
                    writersDone.countDown();
                }
            });
        }
        start.countDown();

        // 只读置位、不清位，近似基数必然落在 [0, n] 且单调不减
        long last = 0;
        while (writersDone.getCount() > 0) {
            long c = bits.cardinality();
            assertThat(c).isBetween(0L, (long) n);
            assertThat(c).isGreaterThanOrEqualTo(last);
            last = c;
        }
        assertThat(writersDone.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(bits.cardinality()).isEqualTo(n);
    }

    @Test
    void contentionRecordsCasRetries() throws Exception {
        AtomicBitSet bits = new AtomicBitSet();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            pool.submit(() -> {
                try {
                    await(start);
                    for (int i = 0; i < 200_000; i++) {
                        int idx = i & 7; // 8 个位、同一个字
                        bits.testAndSet(idx);
                        bits.testAndClear(idx);
                    }
                } finally {
                    finish.countDown();
                }
            });
        }
        start.countDown();
        assertThat(finish.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // 8 线程 × 40 万次同字 CAS，竞争必然产生重试
        assertThat(bits.casRetryCount()).isPositive();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
