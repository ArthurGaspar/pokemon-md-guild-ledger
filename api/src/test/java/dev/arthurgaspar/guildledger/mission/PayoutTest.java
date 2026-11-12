package dev.arthurgaspar.guildledger.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.arthurgaspar.guildledger.mission.Payout.Share;
import dev.arthurgaspar.guildledger.team.SplitMode;

class PayoutTest {

    private static final Set<Long> ALL = Set.of(1L, 2L, 3L, 4L);

    @Test
    void equalSplitCountsEachParticipantOnce() {
        // leader 1; party recruited by 2, 2, 3 -> participants 1, 2, 3
        var r = Payout.split(300, 20, SplitMode.EQUAL, 1L, List.of(2L, 2L, 3L), ALL);
        assertThat(r.treasury()).isEqualTo(60);
        assertThat(r.shares()).containsExactly(new Share(1, 1, 80), new Share(2, 1, 80), new Share(3, 1, 80));
    }

    @Test
    void byPartyWeightsRecruitsSentPlusOneForLeading() {
        // leader 1 also recruited one party member -> weight 2; member 2 sent two -> weight 2; member 3 one -> 1
        var r = Payout.split(1000, 0, SplitMode.BY_PARTY, 1L, List.of(1L, 2L, 2L, 3L), ALL);
        assertThat(r.shares()).containsExactly(new Share(1, 2, 400), new Share(2, 2, 400), new Share(3, 1, 200));
        assertThat(r.treasury()).isZero();
    }

    @Test
    void roundingLeftoversGoToTheTreasury() {
        var r = Payout.split(100, 0, SplitMode.EQUAL, 1L, List.of(2L, 3L), ALL);
        assertThat(r.shares()).extracting(Share::amount).containsOnly(33L);
        assertThat(r.treasury()).isEqualTo(1);
    }

    @Test
    void cutRoundsDown() {
        var r = Payout.split(99, 15, SplitMode.EQUAL, 1L, List.of(), ALL);
        // 99 * 15% = 14.85 -> 14 to the treasury, 85 to the only participant
        assertThat(r.treasury()).isEqualTo(14);
        assertThat(r.shares()).containsExactly(new Share(1, 1, 85));
    }

    @Test
    void membersWhoLeftDontShare() {
        var r = Payout.split(200, 0, SplitMode.EQUAL, 1L, List.of(4L), Set.of(1L, 2L, 3L));
        assertThat(r.shares()).containsExactly(new Share(1, 1, 200));
    }

    @Test
    void withNobodyToPayEverythingStaysInTheTeam() {
        var r = Payout.split(500, 10, SplitMode.BY_PARTY, null, Arrays.asList(null, 9L), ALL);
        assertThat(r.shares()).isEmpty();
        assertThat(r.treasury()).isEqualTo(500);
    }

    @Test
    void fullCutLeavesZeroShares() {
        var r = Payout.split(500, 100, SplitMode.EQUAL, 1L, List.of(2L), ALL);
        assertThat(r.treasury()).isEqualTo(500);
        assertThat(r.shares()).extracting(Share::amount).containsOnly(0L);
    }

    @Test
    void neverCreatesOrLosesPoke() {
        Random random = new Random(7);
        for (int i = 0; i < 5_000; i++) {
            long reward = random.nextInt(100_001);
            int cut = random.nextInt(101);
            SplitMode mode = random.nextBoolean() ? SplitMode.EQUAL : SplitMode.BY_PARTY;
            List<Long> party = new ArrayList<>();
            for (int k = 0, n = 1 + random.nextInt(4); k < n; k++) {
                party.add(1L + random.nextInt(5));
            }
            Long leader = random.nextInt(6) == 0 ? null : 1L + random.nextInt(5);
            var r = Payout.split(reward, cut, mode, leader, party, ALL);
            assertThat(r.total()).as("reward %d cut %d %s", reward, cut, mode).isEqualTo(reward);
            assertThat(r.treasury()).isGreaterThanOrEqualTo(reward * cut / 100);
            assertThat(r.shares()).allSatisfy(s -> assertThat(s.amount()).isNotNegative());
        }
    }
}
