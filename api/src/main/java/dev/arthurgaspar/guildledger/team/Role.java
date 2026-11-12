package dev.arthurgaspar.guildledger.team;

/** Declared from least to most authority; {@link #atLeast} relies on that order. */
public enum Role {
    MEMBER, OFFICER, LEADER;

    public boolean atLeast(Role other) {
        return compareTo(other) >= 0;
    }
}
