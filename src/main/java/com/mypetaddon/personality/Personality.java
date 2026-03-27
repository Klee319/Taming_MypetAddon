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
            "勇敢", "戦闘で恐れを知らず、攻撃力が上昇する",
            20,
            Map.of("Damage", 1.10, "Life", 0.95),
            Map.of("aggroRange", 1.2)
    ),
    STURDY(
            "頑強", "タフで打たれ強く、ダメージを吸収する",
            20,
            Map.of("Life", 1.15, "Damage", 0.95),
            Map.of("knockbackResist", 1.3)
    ),
    AGILE(
            "俊敏", "素早い身のこなしで攻撃を回避する",
            18,
            Map.of("Speed", 1.15, "Life", 0.90),
            Map.of("dodgeChance", 0.08)
    ),
    LOYAL(
            "忠誠", "深い絆を持ち、信頼を早く獲得する",
            18,
            Map.of("Life", 1.05),
            Map.of("bondExpMultiplier", 1.25)
    ),
    FIERCE(
            "獰猛", "壊滅的なクリティカルストライクを放つ",
            12,
            Map.of("Damage", 1.20, "Life", 0.90, "Speed", 0.95),
            Map.of("critChance", 0.10)
    ),
    CAUTIOUS(
            "慎重", "注意深く、不意を突かれることが少ない",
            12,
            Map.of("Life", 1.10, "Speed", 1.05, "Damage", 0.90),
            Map.of("threatDetectRange", 1.5)
    ),
    LUCKY(
            "幸運", "幸運に恵まれた仲間",
            5,
            Map.of(),
            Map.of("lootBonusChance", 0.15, "rareDropMultiplier", 1.3)
    ),
    GENIUS(
            "天才", "明晰な頭脳でスキルを素早く習得する",
            5,
            Map.of("Damage", 1.05),
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
     * @param statName     the stat to look up (e.g. "Life", "Damage", "Speed")
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
