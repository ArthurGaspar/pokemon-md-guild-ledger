package dev.arthurgaspar.guildledger.ledger;

/**
 * Account keys. Every transaction moves value between these and must net to zero per asset;
 * {@link #WORLD} is the counterpart for anything entering or leaving the team, so it is the only
 * account allowed to go negative.
 */
public final class Accounts {

    public static final String POKE = "POKE";

    public static final String TREASURY = "treasury";
    public static final String STORAGE = "storage";
    public static final String WORLD = "world";

    private Accounts() {
    }

    public static String wallet(long membershipId) {
        return "wallet:" + membershipId;
    }

    public static String bag(long missionId) {
        return "bag:" + missionId;
    }
}
