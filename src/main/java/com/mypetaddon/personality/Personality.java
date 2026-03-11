package com.mypetaddon.personality;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Map;
import java.util.Random;

/**
 * Represents a pet's personality, affecting stat modifiers and custom effects.
 * Each personality has a selection weight used for weighted random assignment.
 */
public enum Personality {

    BRAVE(
            "Brave", "Fearless in battle, boosting damage dealt.",
            20,
            Map.of("damage", 1.10, "health", 0.95),
            Map.of("aggroRange", 1.2)
    ),
    STURDY(
            "Sturdy", "Tough and resilient, absorbing more damage.",
            20,
            Map.of("health", 1.15, "damage", 0.95),
            Map.of("knockbackResist", 1.3)
    ),
    AGILE(
            "Agile", "Quick on its feet, dodging attacks with ease.",
            18,
            Map.of("speed", 1.15, "health", 0.90),
            Map.of("dodgeChance", 0.08)
    ),
    LOYAL(
            "Loyal", "Deeply bonded, earning trust faster.",
            18,
            Map.of("health", 1.05),
            Map.of("bondExpMultiplier", 1.25)
    ),
    FIERCE(
            "Fierce", "Unleashes devastating critical strikes.",
            12,
            Map.of("damage", 1.20, "health", 0.90, "speed", 0.95),
            Map.of("critChance", 0.10)
    ),
    CAUTIOUS(
            "Cautious", "Alert and careful, rarely caught off guard.",
            12,
            Map.of("health", 1.10, "speed", 1.05, "damage", 0.90),
            Map.of("threatDetectRange", 1.5)
    ),
    LUCKY(
            "Lucky", "Fortune favors this companion.",
            5,
            Map.of(),
            Map.of("lootBonusChance", 0.15, "rareDropMultiplier", 1.3)
    ),
    GENIUS(
            "Genius", "Brilliant mind, mastering skills rapidly.",
            5,
            Map.of("damage", 1.05),
            Map.of("skillExpMultiplier", 1.30, "skillCooldownReduction", 0.10)
    );

    private final String displayName;
    private final String description;
    private final int weight;
    private final Map<String, Double> modifiers;
    private final Map<String, Double> customEffects;

    Personality(@NotNull String displayName,
                @NotNull String description,
                int weight,
                @NotNull Map<String, Double> modifiers,
                @NotNull Map<String, Double> customEffects) {
        this.displayName = displayName;
        this.description = description;
        this.weight = weight;
        this.modifiers = Map.copyOf(modifiers);
        this.customEffects = Map.copyOf(customEffects);
    }

    @NotNull
    public String getDisplayName() {
        return displayName;
    }

    @NotNull
    public String getDescription() {
        return description;
    }

    public int getWeight() {
        return weight;
    }

    /**
     * Returns an unmodifiable view of the stat modifiers.
     */
    @NotNull
    public Map<String, Double> getModifiers() {
        return modifiers;
    }

    /**
     * Returns an unmodifiable view of the custom effects.
     */
    @NotNull
    public Map<String, Double> getCustomEffects() {
        return customEffects;
    }

    /**
     * Gets the modifier value for a given stat name, returning
     * the default value if no modifier is defined.
     *
     * @param statName     the stat to look up (e.g. "damage", "health")
     * @param defaultValue the fallback value if not present
     * @return the modifier value
     */
    public double getModifier(@NotNull String statName, double defaultValue) {
        return modifiers.getOrDefault(statName, defaultValue);
    }

    /**
     * Gets the custom effect value for a given effect name, returning
     * the default value if no effect is defined.
     *
     * @param effectName   the effect to look up (e.g. "critChance", "dodgeChance")
     * @param defaultValue the fallback value if not present
     * @return the effect value
     */
    public double getCustomEffect(@NotNull String effectName, double defaultValue) {
        return customEffects.getOrDefault(effectName, defaultValue);
    }

    /**
     * Selects a random personality using weighted probabilities.
     *
     * @param random the random source
     * @return a weighted-random Personality
     */
    @NotNull
    public static Personality weightedRandom(@NotNull Random random) {
        Personality[] values = values();
        int totalWeight = 0;
        for (Personality p : values) {
            totalWeight += p.weight;
        }

        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (Personality p : values) {
            cumulative += p.weight;
            if (roll < cumulative) {
                return p;
            }
        }

        // Should never reach here, but return last as fallback
        return values[values.length - 1];
    }
}
