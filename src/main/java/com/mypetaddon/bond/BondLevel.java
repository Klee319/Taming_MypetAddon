package com.mypetaddon.bond;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Utility class for bond level calculations.
 * Bond levels range from 1 to 5, with increasing experience thresholds
 * and stat bonuses at each tier.
 */
public final class BondLevel {

    /** Maximum bond level. */
    public static final int MAX_LEVEL = 5;

    /** Minimum bond level. */
    public static final int MIN_LEVEL = 1;

    private static final int[] EXP_THRESHOLDS = {
            0,      // Level 1: 0 exp
            100,    // Level 2: 100 exp
            350,    // Level 3: 350 exp
            750,    // Level 4: 750 exp
            1500    // Level 5: 1500 exp
    };

    private static final Map<Integer, Map<String, Double>> STAT_BONUSES = Map.of(
            1, Map.of("health", 0.0, "damage", 0.0, "speed", 0.0),
            2, Map.of("health", 0.05, "damage", 0.03, "speed", 0.02),
            3, Map.of("health", 0.10, "damage", 0.07, "speed", 0.05),
            4, Map.of("health", 0.18, "damage", 0.12, "speed", 0.08),
            5, Map.of("health", 0.28, "damage", 0.20, "speed", 0.12)
    );

    private BondLevel() {
        // Utility class - no instantiation
    }

    /**
     * Determines the bond level from cumulative experience points.
     *
     * @param exp the total accumulated bond experience
     * @return the bond level (1-5)
     */
    public static int fromExp(int exp) {
        int level = MIN_LEVEL;
        for (int i = EXP_THRESHOLDS.length - 1; i >= 0; i--) {
            if (exp >= EXP_THRESHOLDS[i]) {
                level = i + 1;
                break;
            }
        }
        return Math.min(level, MAX_LEVEL);
    }

    /**
     * Returns the minimum experience required to reach the given level.
     *
     * @param level the bond level (1-5)
     * @return the minimum experience for that level
     * @throws IllegalArgumentException if level is out of range
     */
    public static int getExpForLevel(int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException(
                    "Bond level must be between " + MIN_LEVEL + " and " + MAX_LEVEL + ", got: " + level);
        }
        return EXP_THRESHOLDS[level - 1];
    }

    /**
     * Returns the stat bonus multiplier for a given bond level and stat name.
     * Returns 0.0 if the stat name is not recognized for that level.
     *
     * @param level    the bond level (1-5)
     * @param statName the stat identifier (e.g. "health", "damage", "speed")
     * @return the bonus multiplier (e.g. 0.10 means +10%)
     * @throws IllegalArgumentException if level is out of range
     */
    public static double getStatBonus(int level, @NotNull String statName) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException(
                    "Bond level must be between " + MIN_LEVEL + " and " + MAX_LEVEL + ", got: " + level);
        }
        return STAT_BONUSES.get(level).getOrDefault(statName, 0.0);
    }

    /**
     * Returns the experience required to reach the next level from the current one.
     * Returns 0 if already at max level.
     *
     * @param currentLevel the current bond level (1-5)
     * @return the experience gap to the next level, or 0 at max
     */
    public static int getExpToNextLevel(int currentLevel) {
        if (currentLevel < MIN_LEVEL || currentLevel > MAX_LEVEL) {
            throw new IllegalArgumentException(
                    "Bond level must be between " + MIN_LEVEL + " and " + MAX_LEVEL + ", got: " + currentLevel);
        }
        if (currentLevel >= MAX_LEVEL) {
            return 0;
        }
        return EXP_THRESHOLDS[currentLevel] - EXP_THRESHOLDS[currentLevel - 1];
    }
}
