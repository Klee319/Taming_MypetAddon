package com.mypetaddon.data;

import com.mypetaddon.personality.Personality;
import com.mypetaddon.rarity.Rarity;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Migrates legacy YAML-based pet data to the SQLite database.
 * The legacy format stores stats under: mypet.{uuid}.base.{statName}
 * Migration is idempotent (skips if migration_completed flag is set).
 */
public final class MigrationManager {

    private final JavaPlugin plugin;
    private final PetDataRepository repository;
    private final Logger logger;
    private final Random random;

    public MigrationManager(@NotNull JavaPlugin plugin, @NotNull PetDataRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.logger = plugin.getLogger();
        this.random = new Random();
    }

    /**
     * Checks for legacy data and migrates if needed.
     * Sets a migration_completed flag in config to prevent re-migration.
     */
    public void migrateIfNeeded() {
        FileConfiguration pluginConfig = plugin.getConfig();

        // Check if migration was already completed
        if (pluginConfig.getBoolean("migration_completed", false)) {
            logger.info("[Migration] Migration already completed. Skipping.");
            return;
        }

        File legacyFile = findLegacyFile();
        if (legacyFile == null) {
            logger.info("[Migration] No legacy data found. Skipping migration.");
            markCompleted();
            return;
        }

        logger.info("[Migration] Legacy data found at: " + legacyFile.getName()
                + ". Starting migration...");
        int migrated = 0;
        int failed = 0;

        try {
            YamlConfiguration legacyConfig = YamlConfiguration.loadConfiguration(legacyFile);
            ConfigurationSection mypetSection = legacyConfig.getConfigurationSection("mypet");

            if (mypetSection == null) {
                logger.warning("[Migration] No 'mypet' section found in legacy file.");
                markCompleted();
                return;
            }

            for (String uuidStr : mypetSection.getKeys(false)) {
                try {
                    UUID mypetUuid = UUID.fromString(uuidStr);
                    MigrationResult result = migrateSinglePet(mypetSection, uuidStr, mypetUuid);

                    if (result.success()) {
                        migrated++;
                    } else {
                        failed++;
                        logger.warning("[Migration] Failed to migrate pet "
                                + uuidStr + ": " + result.error());
                    }
                } catch (IllegalArgumentException e) {
                    failed++;
                    logger.warning("[Migration] Invalid UUID in legacy data: " + uuidStr);
                }
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "[Migration] Migration encountered an error", e);
        }

        markCompleted();
        logger.info("[Migration] Migration complete. Migrated: " + migrated
                + ", Failed: " + failed);
    }

    // --- Internal ---

    private MigrationResult migrateSinglePet(@NotNull ConfigurationSection mypetSection,
                                             @NotNull String uuidStr,
                                             @NotNull UUID mypetUuid) {
        ConfigurationSection petSection = mypetSection.getConfigurationSection(uuidStr);
        if (petSection == null) {
            return new MigrationResult(false, "No data section found");
        }

        // Read owner UUID
        String ownerStr = petSection.getString("owner");
        if (ownerStr == null) {
            return new MigrationResult(false, "Missing owner UUID");
        }

        UUID ownerUuid;
        try {
            ownerUuid = UUID.fromString(ownerStr);
        } catch (IllegalArgumentException e) {
            return new MigrationResult(false, "Invalid owner UUID: " + ownerStr);
        }

        // Read mob type
        String mobType = petSection.getString("mob_type", "UNKNOWN");

        // Read base stats from mypet.<uuid>.base.<statName>
        ConfigurationSection baseSection = petSection.getConfigurationSection("base");
        Map<String, Double> baseValues = new LinkedHashMap<>();
        Map<String, Double> upgradedValues = new LinkedHashMap<>();

        if (baseSection != null) {
            for (String statName : baseSection.getKeys(false)) {
                double value = baseSection.getDouble(statName, 0.0);
                baseValues.put(statName, value);
                upgradedValues.put(statName, value); // Legacy: upgraded = base
            }
        }

        // Create new addon pet ID
        UUID addonPetId = UUID.randomUUID();
        long now = System.currentTimeMillis() / 1000L;

        // Legacy pets default to COMMON rarity and random personality
        PetData petData = new PetData(
                addonPetId,
                mypetUuid,
                ownerUuid,
                mobType,
                Rarity.COMMON,
                Personality.weightedRandom(random),
                0,    // bond level
                0,    // bond exp
                0,    // original LM level (unknown for legacy)
                now,
                null, // not evolved
                0.0   // no captured scale for legacy pets
        );

        PetStats petStats = new PetStats(addonPetId, baseValues, upgradedValues);

        repository.save(petData, petStats);
        return new MigrationResult(true, null);
    }

    @Nullable
    private File findLegacyFile() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            return null;
        }

        // Check common legacy file names
        String[] candidates = {"pets.yml", "pet-data.yml", "legacy-pets.yml", "data.yml"};
        for (String name : candidates) {
            File f = new File(dataFolder, name);
            if (f.exists() && f.isFile()) {
                return f;
            }
        }

        // Check for any YAML file containing a 'mypet' section
        File[] yamlFiles = dataFolder.listFiles((dir, name) ->
                name.endsWith(".yml") && !name.equals("config.yml"));
        if (yamlFiles != null) {
            for (File yamlFile : yamlFiles) {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(yamlFile);
                if (config.contains("mypet")) {
                    return yamlFile;
                }
            }
        }

        return null;
    }

    private void markCompleted() {
        plugin.getConfig().set("migration_completed", true);
        try {
            plugin.saveConfig();
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "[Migration] Failed to save migration_completed flag", e);
        }
    }

    // --- Result Record ---

    private record MigrationResult(boolean success, @Nullable String error) {}
}
