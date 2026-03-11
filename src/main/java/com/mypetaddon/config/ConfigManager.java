package com.mypetaddon.config;

import com.mypetaddon.personality.Personality;
import com.mypetaddon.rarity.Rarity;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Manages YAML config loading, validation, and atomic reload.
 * All getters return immutable snapshots or defensive copies.
 */
public final class ConfigManager {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final ConfigValidator validator;
    private volatile FileConfiguration config;

    public ConfigManager(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.validator = new ConfigValidator();
    }

    /**
     * Loads config.yml and validates it. Returns true if valid.
     */
    public boolean loadAndValidate() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration loaded = plugin.getConfig();

        ValidationResult result = validator.validate(loaded);
        if (!result.valid()) {
            result.errors().forEach(error -> logger.severe("[Config] " + error));
            return false;
        }

        this.config = loaded;
        logger.info("[Config] Configuration loaded and validated successfully.");
        return true;
    }

    /**
     * Reloads config atomically: only applies if the new config is valid.
     */
    @NotNull
    public ValidationResult reload() {
        plugin.reloadConfig();
        FileConfiguration candidate = plugin.getConfig();

        ValidationResult result = validator.validate(candidate);
        if (result.valid()) {
            this.config = candidate;
            logger.info("[Config] Configuration reloaded successfully.");
        } else {
            logger.warning("[Config] Reload failed validation. Keeping previous config.");
            result.errors().forEach(error -> logger.warning("[Config] " + error));
        }
        return result;
    }

    // ─── Rarity Chances ──────────────────────────────────────────

    /**
     * Returns rarity weight map for a given mob level.
     * Finds the matching level-range section from config.
     */
    @NotNull
    public Map<Rarity, Integer> getRarityChances(int mobLevel) {
        ConfigurationSection ranges = config.getConfigurationSection("rarity-chances.level-ranges");
        if (ranges == null) {
            return getNoLevelledMobsFallback();
        }

        for (String rangeKey : ranges.getKeys(false)) {
            if (matchesLevelRange(rangeKey, mobLevel)) {
                return parseRarityWeights(ranges.getConfigurationSection(rangeKey));
            }
        }

        return getNoLevelledMobsFallback();
    }

    /**
     * Fallback rarity weights when LevelledMobs is not installed.
     */
    @NotNull
    public Map<Rarity, Integer> getNoLevelledMobsFallback() {
        ConfigurationSection section = config.getConfigurationSection("rarity-chances.no-levelledmobs-fallback");
        if (section == null) {
            // Default: COMMON only
            return Map.of(Rarity.COMMON, 100);
        }
        return parseRarityWeights(section);
    }

    /**
     * Returns the environment bonus value for a given condition (e.g. "full-moon", "thunderstorm").
     */
    public int getEnvironmentBonus(@NotNull String condition) {
        return config.getInt("rarity-chances.environment-bonuses." + condition, 0);
    }

    /**
     * Returns the maximum bonus cap for environment bonuses.
     */
    public int getMaxBonusCap() {
        return config.getInt("rarity-chances.max-bonus-cap", 50);
    }

    // ─── Personality ─────────────────────────────────────────────

    /**
     * Returns the selection weight for a personality from config,
     * falling back to the enum's default weight.
     */
    public int getPersonalityWeight(@NotNull Personality personality) {
        String path = "personalities." + personality.name() + ".weight";
        return config.getInt(path, personality.getWeight());
    }

    // ─── Taming ──────────────────────────────────────────────────

    /**
     * Returns the taming config section accessors.
     */
    @NotNull
    public String getTamingAnimalItem() {
        return config.getString("taming.animal-item", "ENCHANTED_GOLDEN_APPLE");
    }

    @NotNull
    public String getTamingMonsterItem() {
        return config.getString("taming.monster-item", "DIAMOND");
    }

    public long getTamingTimeout() {
        return config.getLong("taming.timeout-seconds", 30) * 1000L;
    }

    public int getTamingMaxDistance() {
        return config.getInt("taming.max-distance", 10);
    }

    public boolean isTamingRequireKill() {
        return config.getBoolean("taming.monster-require-kill", true);
    }

    // ─── Bond ────────────────────────────────────────────────────

    /**
     * Returns the bond EXP gain for a given source (e.g. "combat-kill", "feeding").
     */
    public int getBondGain(@NotNull String source) {
        return config.getInt("bond.gain." + source, 0);
    }

    /**
     * Returns the bond EXP loss for a given source (e.g. "daily-decay", "pet-death").
     */
    public int getBondLoss(@NotNull String source) {
        return config.getInt("bond.loss." + source, 0);
    }

    /**
     * Returns the debounce interval in milliseconds for a bond source.
     */
    public long getBondDebounce(@NotNull String source) {
        return config.getLong("bond.debounce." + source, 0L);
    }

    // ─── Pet Base Values ─────────────────────────────────────────

    /**
     * Returns stat base value ranges for a mob type.
     * Map key is stat name, value is [min, max].
     */
    @NotNull
    public Map<String, double[]> getPetBaseValues(@NotNull String mobTypeName) {
        ConfigurationSection section = config.getConfigurationSection(
                "pet-base-values." + mobTypeName);
        if (section == null) {
            return Collections.emptyMap();
        }

        Map<String, double[]> result = new java.util.LinkedHashMap<>();
        for (String statName : section.getKeys(false)) {
            double min = section.getDouble(statName + ".min", 0.0);
            double max = section.getDouble(statName + ".max", min);
            result.put(statName, new double[]{min, max});
        }
        return Collections.unmodifiableMap(result);
    }

    // ─── Rarity Tier Config ──────────────────────────────────────

    /**
     * Returns the config section for a rarity tier (multiplier, slots, particle, etc.).
     */
    @Nullable
    public ConfigurationSection getRarityTier(@NotNull Rarity rarity) {
        return config.getConfigurationSection("rarity-tiers." + rarity.name());
    }

    // ─── LevelledMobs PDC Key ────────────────────────────────────

    /**
     * Returns the PDC key name used by LevelledMobs to store mob level.
     */
    @NotNull
    public String getLevelledMobsPdcKey() {
        return config.getString("levelledmobs.pdc-key", "level");
    }

    // ─── Generic Accessors ───────────────────────────────────────

    @NotNull
    public String getString(@NotNull String path, @NotNull String defaultValue) {
        return config.getString(path, defaultValue);
    }

    public int getInt(@NotNull String path, int defaultValue) {
        return config.getInt(path, defaultValue);
    }

    public double getDouble(@NotNull String path, double defaultValue) {
        return config.getDouble(path, defaultValue);
    }

    /**
     * Returns the underlying FileConfiguration. Prefer typed getters.
     */
    @NotNull
    public FileConfiguration getConfig() {
        return config;
    }

    // ─── Internal Helpers ────────────────────────────────────────

    private boolean matchesLevelRange(@NotNull String rangeKey, int level) {
        if (rangeKey.endsWith("+")) {
            int min = Integer.parseInt(rangeKey.replace("+", ""));
            return level >= min;
        }
        String[] parts = rangeKey.split("-");
        if (parts.length == 2) {
            int min = Integer.parseInt(parts[0]);
            int max = Integer.parseInt(parts[1]);
            return level >= min && level <= max;
        }
        return false;
    }

    @NotNull
    private Map<Rarity, Integer> parseRarityWeights(@Nullable ConfigurationSection section) {
        if (section == null) {
            return Map.of(Rarity.COMMON, 100);
        }
        Map<Rarity, Integer> weights = new EnumMap<>(Rarity.class);
        for (String key : section.getKeys(false)) {
            try {
                Rarity rarity = Rarity.fromString(key);
                int weight = section.getInt(key, 0);
                if (weight > 0) {
                    weights.put(rarity, weight);
                }
            } catch (IllegalArgumentException e) {
                logger.warning("[Config] Unknown rarity in weights: " + key);
            }
        }
        return Collections.unmodifiableMap(weights);
    }

    // ─── Validation Result ───────────────────────────────────────

    /**
     * Result of a config validation pass.
     */
    public record ValidationResult(boolean valid, @NotNull List<String> errors) {

        public ValidationResult {
            errors = List.copyOf(errors);
        }

        @NotNull
        public static ValidationResult success() {
            return new ValidationResult(true, List.of());
        }

        @NotNull
        public static ValidationResult failure(@NotNull List<String> errors) {
            return new ValidationResult(false, errors);
        }
    }
}
