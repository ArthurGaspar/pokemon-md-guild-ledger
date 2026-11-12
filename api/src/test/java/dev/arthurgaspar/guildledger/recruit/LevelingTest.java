package dev.arthurgaspar.guildledger.recruit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LevelingTest {

    @Test
    void cubesAreLevelThresholds() {
        for (int level = 2; level <= 100; level++) {
            int exp = Leveling.expForLevel(level);
            assertThat(Leveling.levelForExp(exp)).as("exactly %d exp", exp).isEqualTo(level);
            assertThat(Leveling.levelForExp(exp - 1)).as("one short of %d", level).isEqualTo(level - 1);
        }
    }

    @Test
    void levelOneStartsAtZero() {
        assertThat(Leveling.expForLevel(1)).isZero();
        assertThat(Leveling.levelForExp(0)).isEqualTo(1);
        assertThat(Leveling.levelForExp(7)).isEqualTo(1);
    }

    @Test
    void gainingExpCanSkipLevelsButStopsAtTheCap() {
        Recruit r = new Recruit(1L, 25, null, 5, null, java.time.Instant.EPOCH);
        assertThat(r.gainExp(1000, 100)).isEqualTo(5); // 125 + 1000 = 1125 -> Lv. 10
        assertThat(r.getLevel()).isEqualTo(10);

        Recruit old = new Recruit(1L, 25, null, 99, null, java.time.Instant.EPOCH);
        old.gainExp(10_000_000, 100);
        assertThat(old.getLevel()).isEqualTo(100);
    }
}
