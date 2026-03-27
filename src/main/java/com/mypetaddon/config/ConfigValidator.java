package com.mypetaddon.config;

import com.mypetaddon.config.ConfigManager.ValidationResult;
import com.mypetaddon.rarity.Rarity;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates the plugin's YAML configuration structure and values.
 * Returns all errors found (does not stop at first error).
 */
public final class ConfigValidator {

    @NotNull
    public ValidationResult validate(@NotNull FileConfiguration config) {
        List<String> errors = new ArrayList<>();

        validateRarityChances(config, errors);
        validatePersonalityWeights(config, errors);
        validateBondLevels(config, errors);
        validateEvolutionChains(config, errors);
        validateMobTypes(config, errors);
        validateStatMultipliers(config, errors);
        validateRequiredKeys(config, errors);

        return errors.isEmpty()
                ? ValidationResult.success()
                : ValidationResult.failure(errors);
    }

    // ─── Rarity Weights ──────────────────────────────────────────

    private void validateRarityChances(@NotNull FileConfiguration config,
                                       @NotNull List<String> errors) {
        ConfigurationSection ranges = config.getConfigurationSection("rarity.level-ranges");
        if (ranges == null) {
            errors.add("Missing required section: rarity.level-ranges");
            return;
        }

        for (String rangeKey : ranges.getKeys(false)) {
            ConfigurationSection section = ranges.getConfigurationSection(rangeKey);
            if (section == null) {
                errors.add("Invalid level range section: " + rangeKey);
                continue;
            }

            int weightSum = 0;
            for (String rarityName : section.getKeys(false)) {
                // Validate rarity name
                try {
                    Rarity.fromString(rarityName);
                } catch (IllegalArgumentException e) {
                    errors.add("Unknown rarity '" + rarityName + "' in level-range " + rangeKey);
                    continue;
                }

                int weight = section.getInt(rarityName, 0);
                if (weight < 0) {
                    errors.add("Negative weight for " + rarityName + " in level-range " + rangeKey);
                }
                weightSum += weight;
            }

            if (weightSum <= 0) {
                errors.add("Rarity weights sum to 0 or less in level-range " + rangeKey);
            }
        }
    }

    // ─── Personality Weights ─────────────────────────────────────

    private void validatePersonalityWeights(@NotNull FileConfiguration config,
                                            @NotNull List<String> errors) {
        ConfigurationSection section = config.getConfigurationSection("personalities");
        if (section == null) {
            // Personalities are optional in config (enum defaults used)
            return;
        }

        for (String key : section.getKeys(false)) {
            int weight = section.getInt(key + ".weight", -1);
            if (weight != -1 && weight <= 0) {
                errors.add("Personality weight must be > 0 for: " + key);
            }
        }
    }

    // ─── Bond Levels ─────────────────────────────────────────────

    private void validateBondLevels(@NotNull FileConfiguration config,
                                    @NotNull List<String> errors) {
        ConfigurationSection levels = config.getConfigurationSection("bond.levels");
        if (levels == null) {
            errors.add("Missing required section: bond.levels");
            return;
        }

        int previousThreshold = -1;
        List<String> sortedKeys = levels.getKeys(false).stream()
                .sorted((a, b) -> Integer.compare(parseIntSafe(a), parseIntSafe(b)))
                .toList();

        for (String levelKey : sortedKeys) {
            int threshold = levels.getInt(levelKey + ".exp-threshold", -1);
            if (threshold < 0) {
                errors.add("Bond level " + levelKey + " missing or invalid exp-threshold");
                continue;
            }

            if (previousThreshold >= 0 && threshold <= previousThreshold) {
                errors.add("Bond level " + levelKey + " exp-threshold (" + threshold
                        + ") must be greater than previous level (" + previousThreshold
                        + "). Thresholds must be ascending.");
            }

            previousThreshold = threshold;
        }
    }

    // ─── Evolution Cycles ────────────────────────────────────────

    private void validateEvolutionChains(@NotNull FileConfiguration config,
                                         @NotNull List<String> errors) {
        ConfigurationSection evolutions = config.getConfigurationSection("evolutions");
        if (evolutions == null) {
            return; // Evolutions are optional
        }

        // Collect all evolution targets per source type (branches structure)
        for (String fromType : evolutions.getKeys(false)) {
            ConfigurationSection branches = evolutions.getConfigurationSection(fromType + ".branches");
            if (branches == null) {
                errors.add("Evolution entry '" + fromType + "' has no branches section");
                continue;
            }

            for (String branchKey : branches.getKeys(false)) {
                String target = branches.getString(branchKey + ".target");
                if (target == null || target.isEmpty()) {
                    errors.add("Evolution branch '" + fromType + "." + branchKey + "' has no target");
                    continue;
                }

                // Check for cycles: follow target -> its branches -> targets...
                Set<String> visited = new HashSet<>();
                visited.add(fromType);
                String current = target;

                while (current != null && !visited.contains(current)) {
                    visited.add(current);
                    // Find if this target has its own evolution
                    ConfigurationSection nextBranches =
                            evolutions.getConfigurationSection(current + ".branches");
                    if (nextBranches == null) {
                        current = null;
                    } else {
                        // Check all branches of the next target for cycles
                        current = null;
                        for (String nextBranchKey : nextBranches.getKeys(false)) {
                            String nextTarget = nextBranches.getString(nextBranchKey + ".target");
                            if (nextTarget != null && visited.contains(nextTarget)) {
                                errors.add("Evolution cycle detected: " + fromType
                                        + " -> " + target + " ... -> " + nextTarget);
                            } else if (nextTarget != null && current == null) {
                                current = nextTarget; // Follow first chain
                            }
                        }
                    }
                }
            }
        }
    }

    // ─── Mob Types ───────────────────────────────────────────────

    private void validateMobTypes(@NotNull FileConfiguration config,
                                  @NotNull List<String> errors) {
        // pet-base-values uses tier structure (tier-1, tier-2, etc.) with types lists
        // Validate the mob types inside each tier
        ConfigurationSection baseValues = config.getConfigurationSection("pet-base-values");
        if (baseValues != null) {
            for (String tierKey : baseValues.getKeys(false)) {
                List<String> types = config.getStringList("pet-base-values." + tierKey + ".types");
                for (String mobTypeName : types) {
                    try {
                        EntityType.valueOf(mobTypeName.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        errors.add("Unknown EntityType '" + mobTypeName + "' in pet-base-values." + tierKey + ".types");
                    }
                }
            }
        }

        // Also validate evolution mob types (branches structure)
        ConfigurationSection evolutions = config.getConfigurationSection("evolutions");
        if (evolutions != null) {
            for (String fromType : evolutions.getKeys(false)) {
                validateEntityTypeName(fromType, "evolutions (from)", errors);
                ConfigurationSection branches = evolutions.getConfigurationSection(fromType + ".branches");
                if (branches != null) {
                    for (String branchKey : branches.getKeys(false)) {
                        String toType = branches.getString(branchKey + ".target");
                        if (toType != null) {
                            validateEntityTypeName(toType, "evolutions." + fromType + ".branches." + branchKey, errors);
                        }
                    }
                }
            }
        }
    }

    private void validateEntityTypeName(@NotNull String name, @NotNull String context,
                                        @NotNull List<String> errors) {
        try {
            EntityType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add("Unknown EntityType '" + name + "' in " + context);
        }
    }

    // ─── Stat Multipliers ────────────────────────────────────────

    private void validateStatMultipliers(@NotNull FileConfiguration config,
                                         @NotNull List<String> errors) {
        ConfigurationSection tiers = config.getConfigurationSection("rarity.tiers");
        if (tiers == null) {
            return;
        }

        for (String tierName : tiers.getKeys(false)) {
            double multiplier = tiers.getDouble(tierName + ".stat-multiplier", 1.0);
            if (multiplier <= 0) {
                errors.add("Stat multiplier must be positive for rarity tier: " + tierName
                        + " (got " + multiplier + ")");
            }
        }
    }

    // ─── Required Keys ───────────────────────────────────────────

    private void validateRequiredKeys(@NotNull FileConfiguration config,
                                      @NotNull List<String> errors) {
        List<String> requiredPaths = List.of(
                "rarity.level-ranges",
                "bond.levels",
                "bond.gain",
                "bond.loss"
        );

        for (String path : requiredPaths) {
            if (!config.contains(path)) {
                errors.add("Missing required config key: " + path);
            }
        }
    }

    // ─── Utilities ───────────────────────────────────────────────

    private int parseIntSafe(@NotNull String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
