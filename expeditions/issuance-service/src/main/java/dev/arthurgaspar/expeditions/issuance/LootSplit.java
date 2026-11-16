package dev.arthurgaspar.expeditions.issuance;

/** Equal shares, rounded down; the remainder goes one Poké each to the first members, so shares always add up. */
final class LootSplit {

    private LootSplit() {
    }

    static int[] split(int total, int members) {
        if (members <= 0 || total < 0) {
            throw new IllegalArgumentException("Can't split " + total + " between " + members);
        }
        int[] shares = new int[members];
        int base = total / members;
        int remainder = total % members;
        for (int i = 0; i < members; i++) {
            shares[i] = base + (i < remainder ? 1 : 0);
        }
        return shares;
    }
}
