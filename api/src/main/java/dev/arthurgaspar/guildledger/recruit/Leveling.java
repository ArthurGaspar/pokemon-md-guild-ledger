package dev.arthurgaspar.guildledger.recruit;

/** Medium Fast growth: reaching level n takes n³ total exp. */
public final class Leveling {

    private Leveling() {
    }

    public static int expForLevel(int level) {
        return level <= 1 ? 0 : level * level * level;
    }

    public static int levelForExp(int exp) {
        int level = Math.max(1, (int) Math.cbrt(exp));
        // cbrt can land one below an exact cube because of floating point
        while (expForLevel(level + 1) <= exp) {
            level++;
        }
        return level;
    }
}
