package com.mypetaddon.data;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable record representing a pet's stat values (base and upgraded).
 */
public record PetStats(
        @NotNull UUID addonPetId,
        @NotNull Map<String, Double> baseValues,
        @NotNull Map<String, Double> upgradedValues
) {

    public PetStats {
        baseValues = Map.copyOf(baseValues);
        upgradedValues = Map.copyOf(upgradedValues);
    }

    /**
     * Returns the effective value for a stat (base + upgraded, additive).
     */
    public double getEffective(@NotNull String statName) {
        return baseValues.getOrDefault(statName, 0.0)
                + upgradedValues.getOrDefault(statName, 0.0);
    }

    /**
     * Returns a copy with an upgraded stat value applied.
     */
    @NotNull
    public PetStats withUpgradedStat(@NotNull String statName, double value) {
        Map<String, Double> newUpgraded = new LinkedHashMap<>(upgradedValues);
        newUpgraded.put(statName, value);
        return new PetStats(addonPetId, baseValues, Map.copyOf(newUpgraded));
    }
}
