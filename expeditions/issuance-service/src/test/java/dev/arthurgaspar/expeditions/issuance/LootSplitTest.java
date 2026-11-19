package dev.arthurgaspar.expeditions.issuance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

class LootSplitTest {

    @Test
    void remainderGoesToTheFirstMembers() {
        assertThat(LootSplit.split(10, 4)).containsExactly(3, 3, 2, 2);
        assertThat(LootSplit.split(3, 4)).containsExactly(1, 1, 1, 0);
        assertThat(LootSplit.split(0, 2)).containsExactly(0, 0);
    }

    @Test
    void sharesAlwaysAddUpAndDifferByAtMostOne() {
        Random random = new Random(42);
        for (int i = 0; i < 5_000; i++) {
            int total = random.nextInt(100_000);
            int members = 1 + random.nextInt(8);
            int[] shares = LootSplit.split(total, members);
            assertThat(Arrays.stream(shares).sum()).isEqualTo(total);
            assertThat(Arrays.stream(shares).max().getAsInt() - Arrays.stream(shares).min().getAsInt()).isLessThanOrEqualTo(1);
        }
    }
}
