package com.mypetaddon.evolution;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Immutable record representing a single evolution branch option.
 * A mob type may have multiple branches (e.g., Zombie → Husk | Drowned | Zombie Villager).
 *
 * @param key              unique branch identifier within the parent mob type
 * @param displayName      colored display name for GUI
 * @param target           target EntityType name after evolution
 * @param fixedSkilltree   skilltree name to assign on evolution (null = keep current)
 * @param minLevel         minimum pet level required
 * @param minBondLevel     minimum bond level required
 * @param requiredBiome    required biome name (empty = no restriction)
 * @param requiredItem     required item Material name (empty = no item required)
 * @param requiredItemAmount number of items required
 * @param statBonus        stat multiplier bonuses applied on evolution (e.g., Life → 1.15 = +15%)
 */
public record EvolutionBranch(
        @NotNull String key,
        @NotNull String displayName,
        @NotNull String target,
        @Nullable String fixedSkilltree,
        int minLevel,
        int minBondLevel,
        @NotNull String requiredBiome,
        @NotNull String requiredItem,
        int requiredItemAmount,
        @NotNull Map<String, Double> statBonus
) {
    public EvolutionBranch {
        statBonus = Map.copyOf(statBonus);
    }
}
