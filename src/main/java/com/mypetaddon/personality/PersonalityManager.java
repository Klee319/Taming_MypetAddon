package com.mypetaddon.personality;

import com.mypetaddon.config.ConfigManager;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages personality assignment for newly tamed pets.
 * Reads selection weights from config, falling back to the enum's built-in defaults.
 */
public final class PersonalityManager {

    private final ConfigManager configManager;

    public PersonalityManager(@NotNull ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * Rolls a random personality using config-defined weights.
     * Falls back to the enum's default weight if a personality is not configured.
     *
     * @return the selected personality
     */
    @NotNull
    public Personality rollPersonality() {
        Personality[] values = Personality.values();

        int totalWeight = 0;
        int[] weights = new int[values.length];

        for (int i = 0; i < values.length; i++) {
            int weight = configManager.getPersonalityWeight(values[i]);
            weights[i] = Math.max(weight, 0);
            totalWeight += weights[i];
        }

        if (totalWeight <= 0) {
            // All weights zero or negative — fall back to pure enum random
            return Personality.weightedRandom(new java.util.Random(
                    ThreadLocalRandom.current().nextLong()));
        }

        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;

        for (int i = 0; i < values.length; i++) {
            cumulative += weights[i];
            if (roll < cumulative) {
                return values[i];
            }
        }

        // Defensive fallback — should never reach here
        return values[values.length - 1];
    }
}
