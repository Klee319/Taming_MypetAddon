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

        int previousMax = -1;
        List<String> sortedKeys = levels.getKeys(false).stream()
                .sorted((a, b) -> Integer.compare(parseIntSafe(a), parseIntSafe(b)))
                .toList();

        for (String levelKey : sortedKeys) {
            int min = levels.getInt(levelKey + ".min", 0);
            int max = levels.getInt(levelKey + ".max", -1);

            if (previousMax >= 0 && min < previousMax) {
                errors.add("Bond level " + levelKey + " min (" + min
                        + ") is less than previous level max (" + previousMax
                        + "). Levels must be ascending.");
            }

            if (max != -1 && max <= min) {
                errors.add("Bond level " + levelKey + " max (" + max
                        + ") must be greater than min (" + min + ").");
            }

            previousMax = max;
        }
    }

    // ─── Evolution Cycles ────────────────────────────────────────

    private void validateEvolutionChains(@NotNull FileConfiguration config,
                                         @NotNull List<String> errors) {
        ConfigurationSection evolutions = config.getConfigurationSection("evolutions");
        if (evolutions == null) {
            return; // Evolutions are optional
        }

        for (String fromType : evolutions.getKeys(false)) {
            Set<String> visited = new HashSet<>();
            String current = fromType;

            while (current != null && !visited.contains(current)) {
                visited.add(current);
                current = evolutions.getString(current + ".target");
            }

            if (current != null && visited.contains(current)) {
                errors.add("Evolution cycle detected starting from: " + fromType
                        + " (cycle at " + current + ")");
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

        // Also validate evolution mob types
        ConfigurationSection evolutions = config.getConfigurationSection("evolutions");
        if (evolutions != null) {
            for (String fromType : evolutions.getKeys(false)) {
                validateEntityTypeName(fromType, "evolutions (from)", errors);
                String toType = evolutions.getString(fromType + ".target");
                if (toType != null) {
                    validateEntityTypeName(toType, "evolutions (to)", errors);
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
