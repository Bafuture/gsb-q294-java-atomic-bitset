package com.example.gsb.bitset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 单线程语义：基础位操作、原子复合操作、区间操作、扩容与统计。 */
class AtomicBitSetTest {

    @Test
    void setGetClearBasic() {
        AtomicBitSet bits = new AtomicBitSet();
        assertThat(bits.get(0)).isFalse();

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
    }

    @Test
    void testAndSetReturnsPreviousState() {
        AtomicBitSet bits = new AtomicBitSet();
        assertThat(bits.testAndSet(7)).isFalse(); // 之前是 0
        assertThat(bits.testAndSet(7)).isTrue();  // 之前是 1
        assertThat(bits.get(7)).isTrue();
    }

    @Test
    void testAndClearReturnsPreviousState() {
        AtomicBitSet bits = new AtomicBitSet();
        assertThat(bits.testAndClear(9)).isFalse(); // 之前是 0，清位为无操作
        bits.set(9);
        assertThat(bits.testAndClear(9)).isTrue();  // 之前是 1
        assertThat(bits.get(9)).isFalse();
    }

    @Test
    void testAndSetAllReturnsPreviousStates() {
        AtomicBitSet bits = new AtomicBitSet();
        bits.set(2);

        boolean[] previous = bits.testAndSetAll(1, 2, 3);
        assertThat(previous).containsExactly(false, true, false);
        assertThat(bits.get(1)).isTrue();
        assertThat(bits.get(2)).isTrue();
        assertThat(bits.get(3)).isTrue();

        boolean[] again = bits.testAndSetAll(1, 2, 3);
        assertThat(again).containsExactly(true, true, true);
    }

    @Test
    void setRangeCoversExactInterval() {
        AtomicBitSet bits = new AtomicBitSet();
        bits.setRange(3, 70); // 跨字边界

        assertThat(bits.get(2)).isFalse();
        for (int i = 3; i < 70; i++) {
            assertThat(bits.get(i)).as("bit %d", i).isTrue();
        }
        assertThat(bits.get(70)).isFalse();
        assertThat(bits.countRange(0, 100)).isEqualTo(67);
    }

    @Test
    void clearRangeClearsExactInterval() {
        AtomicBitSet bits = new AtomicBitSet();
        bits.setRange(0, 200);
        bits.clearRange(50, 150);

        assertThat(bits.countRange(0, 200)).isEqualTo(100);
        for (int i = 0; i < 200; i++) {
            assertThat(bits.get(i)).isEqualTo(i < 50 || i >= 150);
        }
    }

    @Test
    void countRangeHandlesPartialWordsAndEmptyRange() {
        AtomicBitSet bits = new AtomicBitSet();
        bits.setRange(10, 20);
        bits.set(100);

        assertThat(bits.countRange(0, 0)).isZero();      // 空区间
        assertThat(bits.countRange(5, 5)).isZero();      // 空区间
        assertThat(bits.countRange(0, 10)).isZero();
        assertThat(bits.countRange(10, 20)).isEqualTo(10);
        assertThat(bits.countRange(15, 101)).isEqualTo(6); // 15..19 共 5 个 + 100
        assertThat(bits.countRange(0, 5000)).isEqualTo(11); // 超出已分配容量按 0 计
    }

    @Test
    void rangeOperationsRejectInvalidRanges() {
        AtomicBitSet bits = new AtomicBitSet();
        assertThatThrownBy(() -> bits.setRange(-1, 5)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> bits.setRange(5, 3)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> bits.countRange(3, 2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> bits.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void growsAutomaticallyWithoutLosingBits() {
        AtomicBitSet bits = new AtomicBitSet();
        bits.set(1);
        bits.set(100);

        long wordsBefore = bits.wordCount();
        bits.set(1_000_000); // 远超初始容量，触发多级扩容

        assertThat(bits.wordCount()).isGreaterThan(wordsBefore);
        // 已有位不丢，高位可正常读写
        assertThat(bits.get(1)).isTrue();
        assertThat(bits.get(100)).isTrue();
        assertThat(bits.get(1_000_000)).isTrue();
        assertThat(bits.countRange(1_000_000, 1_000_001)).isEqualTo(1);
        assertThat(bits.cardinality()).isEqualTo(3);
    }

    @Test
    void clearBeyondCapacityIsNoOpAndDoesNotGrow() {
        AtomicBitSet bits = new AtomicBitSet();
        long wordsBefore = bits.wordCount();
        bits.clear(500_000);
        bits.clearRange(100_000, 200_000);
        assertThat(bits.wordCount()).isEqualTo(wordsBefore);
        assertThat(bits.cardinality()).isZero();
    }

    @Test
    void cardinalityIsExactWhenQuiescent() {
        AtomicBitSet bits = new AtomicBitSet();
        bits.setRange(0, 1000);
        bits.clearRange(100, 200);
        bits.set(5000);
        assertThat(bits.cardinality()).isEqualTo(901);
        assertThat(bits.cardinality()).isEqualTo(bits.countRange(0, 6000));
    }

    @Test
    void statisticsTrackSetsRetriesAndSizes() {
        AtomicBitSet bits = new AtomicBitSet();
        bits.set(1);
        bits.set(1);              // 重复置位不计入
        bits.testAndSetAll(2, 3); // 2 次新置位
        bits.setRange(10, 20);    // 10 次新置位
        bits.set(1 << 20);        // 触发扩容 + 1 次置位

        assertThat(bits.setCount()).isEqualTo(14);
        assertThat(bits.casRetryCount()).isGreaterThanOrEqualTo(0);
        assertThat(bits.cardinality()).isEqualTo(14);
        assertThat(bits.wordCount()).isEqualTo(bits.bitCapacity() / 64);
        assertThat(bits.wordCount()).isGreaterThanOrEqualTo(AtomicBitSet.SEGMENT_WORDS);
    }
}
