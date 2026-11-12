package dev.arthurgaspar.guildledger.mission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import dev.arthurgaspar.guildledger.team.SplitMode;

/**
 * Splits a mission's Poké reward. Pure and deterministic:
 * <ol>
 *   <li>The treasury takes {@code floor(reward × cut%)}.</li>
 *   <li>The rest is divided by weight among participants: the mission leader and every member who recruited
 *       someone in the party. EQUAL gives each participant weight 1; BY_PARTY gives one per recruit sent plus one
 *       for leading.</li>
 *   <li>Each share is rounded down; leftover Poké goes to the treasury, so nothing is created or lost.</li>
 * </ol>
 * Members who have left the team don't participate.
 */
public final class Payout {

    public record Share(long membershipId, int weight, long amount) {}

    public record Result(long treasury, List<Share> shares) {

        public long total() {
            return treasury + shares.stream().mapToLong(Share::amount).sum();
        }
    }

    private Payout() {
    }

    /**
     * @param partyRecruiters membership id of whoever recruited each party member (null if unknown)
     * @param active          ids of memberships still on the team
     */
    public static Result split(long reward, int cutPct, SplitMode mode, Long leaderId, List<Long> partyRecruiters,
                               Set<Long> active) {
        Map<Long, Integer> weights = new TreeMap<>();
        if (leaderId != null && active.contains(leaderId)) {
            weights.put(leaderId, 1);
        }
        for (Long recruiter : partyRecruiters) {
            if (recruiter == null || !active.contains(recruiter)) {
                continue;
            }
            if (mode == SplitMode.BY_PARTY) {
                weights.merge(recruiter, 1, Integer::sum);
            } else {
                weights.putIfAbsent(recruiter, 1);
            }
        }

        long cut = reward * cutPct / 100;
        long pool = reward - cut;
        int totalWeight = weights.values().stream().mapToInt(Integer::intValue).sum();
        if (totalWeight == 0) {
            return new Result(reward, List.of());
        }
        List<Share> shares = new ArrayList<>();
        long paid = 0;
        for (var e : weights.entrySet()) {
            long amount = pool * e.getValue() / totalWeight;
            shares.add(new Share(e.getKey(), e.getValue(), amount));
            paid += amount;
        }
        return new Result(cut + (pool - paid), shares);
    }
}
