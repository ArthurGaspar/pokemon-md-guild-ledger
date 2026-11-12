package dev.arthurgaspar.guildledger.ledger;

public enum Reason {
    /** Items or Poké brought in from outside the team. */
    DEPOSIT,
    /** Items thrown away from storage. */
    DISCARD,
    /** A member moves Poké from their wallet into the treasury. */
    CONTRIBUTION,
    /** The leader pays a member from the treasury. */
    PAYOUT,
    /** A member takes Poké out of the team ledger. Also applied automatically when someone leaves. */
    CASH_OUT,
    /** Storage items packed into a mission bag at departure. */
    MISSION_PACK,
    /** Unused bag items returned to storage after a mission. */
    MISSION_RETURN,
    /** Bag items consumed during a completed mission. */
    ITEM_USED,
    /** Bag items lost when a mission fails. */
    ITEM_LOST,
    /** Poké and items paid by the client of a completed mission. */
    MISSION_REWARD
}
