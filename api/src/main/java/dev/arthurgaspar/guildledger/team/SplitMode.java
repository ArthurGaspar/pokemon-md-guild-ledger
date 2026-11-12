package dev.arthurgaspar.guildledger.team;

/** How the members' part of a mission reward is divided. */
public enum SplitMode {
    /** Every participant (mission leader and each member whose recruit went) gets one share. */
    EQUAL,
    /** One share per recruit a member put in the party, plus one for the mission leader. */
    BY_PARTY
}
