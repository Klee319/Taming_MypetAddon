package com.mypetaddon.bond;

import com.mypetaddon.config.ConfigManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
            300,    // Level 3: 300 exp
            600,    // Level 4: 600 exp
            1000    // Level 5: 1000 exp
    };

    private static final Map<Integer, Map<String, Double>> STAT_BONUSES = Map.of(
            1, Map.of("Life", 0.0, "Damage", 0.0, "Speed", 0.0),
            2, Map.of("Life", 0.05, "Damage", 0.03, "Speed", 0.02),
            3, Map.of("Life", 0.10, "Damage", 0.07, "Speed", 0.05),
            4, Map.of("Life", 0.18, "Damage", 0.12, "Speed", 0.08),
            5, Map.of("Life", 0.28, "Damage", 0.20, "Speed", 0.12)
    );

    /** Optional config source for overriding defaults. Set via {@link #initialize(ConfigManager)}. */
    @Nullable
    private static volatile ConfigManager configManager;

    private BondLevel() {
        // Utility class - no instantiation
    }

    /**
     * Initializes config-based overrides. Call once during plugin startup.
     */
    public static void initialize(@NotNull ConfigManager cm) {
        configManager = cm;
    }

    /**
     * Determines the bond level from cumulative experience points.
     *
     * @param exp the total accumulated bond experience
     * @return the bond level (1-5)
     */
    public static int fromExp(int exp) {
        int level = MIN_LEVEL;
        for (int i = MAX_LEVEL; i >= MIN_LEVEL; i--) {
            if (exp >= getExpForLevel(i)) {
                level = i;
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
        // Read from config, fall back to hardcoded default
        ConfigManager cm = configManager;
        if (cm != null) {
            int configValue = cm.getBondExpThreshold(level);
            if (configValue >= 0) {
                return configValue;
            }
        }
        return EXP_THRESHOLDS[level - 1];
    }

    /**
     * Returns the stat bonus multiplier for a given bond level and stat name.
     * Returns 0.0 if the stat name is not recognized for that level.
     *
     * @param level    the bond level (1-5)
     * @param statName the stat identifier (e.g. "Life", "Damage", "Speed")
     * @return the bonus multiplier (e.g. 0.10 means +10%)
     * @throws IllegalArgumentException if level is out of range
     */
    public static double getStatBonus(int level, @NotNull String statName) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException(
                    "Bond level must be between " + MIN_LEVEL + " and " + MAX_LEVEL + ", got: " + level);
        }
        // Read from config, fall back to hardcoded default
        ConfigManager cm = configManager;
        if (cm != null) {
            double configValue = cm.getBondStatBonus(level, statName);
            if (configValue >= 0.0) {
                return configValue;
            }
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
        return getExpForLevel(currentLevel + 1) - getExpForLevel(currentLevel);
    }
}
