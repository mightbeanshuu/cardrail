package dev.cardrail.loadgen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StanSequenceTest {

    @Test
    void startsAtOneAndIsZeroPadded() {
        StanSequence s = new StanSequence();
        assertThat(s.next()).isEqualTo("000001");
        assertThat(s.next()).isEqualTo("000002");
    }

    @Test
    void wrapsFrom999999To000001SkippingZero() {
        StanSequence s = new StanSequence(999_998);
        assertThat(s.next()).isEqualTo("999998");
        assertThat(s.next()).isEqualTo("999999");
        assertThat(s.next()).isEqualTo("000001");
    }

    @Test
    void fullCycleVisitsEveryStanOnce() {
        StanSequence s = new StanSequence(500_000);
        boolean[] seen = new boolean[StanSequence.MAX + 1];
        for (int i = 0; i < StanSequence.MAX; i++) {
            int v = s.nextValue();
            assertThat(seen[v]).isFalse();
            seen[v] = true;
        }
        assertThat(seen[0]).isFalse();
        assertThat(s.nextValue()).isEqualTo(500_000);
    }

    @Test
    void rejectsOutOfRangeStart() {
        assertThatThrownBy(() -> new StanSequence(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StanSequence(1_000_000)).isInstanceOf(IllegalArgumentException.class);
    }
}
