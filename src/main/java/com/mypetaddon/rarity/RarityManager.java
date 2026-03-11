package com.mypetaddon.rarity;

import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.integration.LevelledMobsIntegration;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Handles rarity determination when a mob is tamed.
 * Considers mob level (via LevelledMobs), weighted random selection,
 * and environment bonuses that can upgrade the result.
 */
public final class RarityManager {

    private final ConfigManager configManager;
    private final LevelledMobsIntegration levelledMobsIntegration;
    private final Logger logger;

    public RarityManager(@NotNull ConfigManager configManager,
                         @NotNull LevelledMobsIntegration levelledMobsIntegration) {
        this.configManager = configManager;
        this.levelledMobsIntegration = levelledMobsIntegration;
        this.logger = java.util.logging.Logger.getLogger(RarityManager.class.getName());
    }

    /**
     * Rolls a rarity for a tamed entity, factoring in mob level and environment bonuses.
     *
     * @param entity the entity being tamed
     * @param player the player taming the entity
     * @return the determined rarity
     */
    @NotNull
    public Rarity rollRarity(@NotNull LivingEntity entity, @NotNull Player player) {
        // Step 1: Get mob level
        int mobLevel = levelledMobsIntegration.getMobLevel(entity);

        // Step 2: Get rarity weights based on level (or fallback)
        Map<Rarity, Integer> weights = levelledMobsIntegration.isAvailable()
                ? configManager.getRarityChances(mobLevel)
                : configManager.getNoLevelledMobsFallback();

        // Step 3: Weighted random selection
        Rarity baseRarity = weightedRandomSelect(weights);

        // Step 4: Calculate environment bonuses
        int environmentBonus = getEnvironmentBonuses(player);

        // Step 5: Cap the bonus
        int maxCap = configManager.getMaxBonusCap();
        int cappedBonus = Math.min(environmentBonus, maxCap);

        // Step 6: Roll for upgrade
        if (cappedBonus > 0) {
            int roll = ThreadLocalRandom.current().nextInt(100);
            if (roll < cappedBonus) {
                Rarity upgraded = baseRarity.upgrade();
                if (upgraded != baseRarity) {
                    logger.info("[Rarity] Upgraded " + baseRarity.getDisplayName()
                            + " -> " + upgraded.getDisplayName()
                            + " for " + player.getName()
                            + " (bonus: " + cappedBonus + "%, roll: " + roll + ")");
                    return upgraded;
                }
            }
        }

        return baseRarity;
    }

    /**
     * Checks if the world is currently in a full moon phase.
     * Minecraft full moon occurs when the moon phase is 0 (getFullTime / 24000 % 8 == 0).
     */
    public boolean isFullMoon(@NotNull World world) {
        long days = world.getFullTime() / 24000L;
        return days % 8 == 0;
    }

    /**
     * Checks if the world is currently experiencing a thunderstorm.
     */
    public boolean isThunderstorm(@NotNull World world) {
        return world.isThundering();
    }

    /**
     * Calculates the total environment bonus percentage for a player
     * based on current conditions (full moon, thunderstorm, biome, dimension).
     *
     * @param player the player to evaluate
     * @return the total bonus percentage (uncapped)
     */
    public int getEnvironmentBonuses(@NotNull Player player) {
        int total = 0;
        World world = player.getWorld();

        // Full moon bonus
        if (isFullMoon(world)) {
            total += configManager.getEnvironmentBonus("full-moon");
        }

        // Thunderstorm bonus
        if (isThunderstorm(world)) {
            total += configManager.getEnvironmentBonus("thunderstorm");
        }

        // Biome bonus
        String biomeName = player.getLocation().getBlock().getBiome().getKey().getKey().toLowerCase();
        int biomeBonus = configManager.getEnvironmentBonus("biome." + biomeName);
        if (biomeBonus > 0) {
            total += biomeBonus;
        }

        // Dimension bonus
        String dimensionKey = switch (world.getEnvironment()) {
            case NETHER -> "nether";
            case THE_END -> "the-end";
            default -> "overworld";
        };
        int dimensionBonus = configManager.getEnvironmentBonus("dimension." + dimensionKey);
        if (dimensionBonus > 0) {
            total += dimensionBonus;
        }

        return total;
    }

    // ─── Internal ────────────────────────────────────────────────

    @NotNull
    private Rarity weightedRandomSelect(@NotNull Map<Rarity, Integer> weights) {
        if (weights.isEmpty()) {
            return Rarity.COMMON;
        }

        int totalWeight = 0;
        for (int w : weights.values()) {
            totalWeight += w;
        }

        if (totalWeight <= 0) {
            return Rarity.COMMON;
        }

        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;

        for (Map.Entry<Rarity, Integer> entry : weights.entrySet()) {
            cumulative += entry.getValue();
            if (roll < cumulative) {
                return entry.getKey();
            }
        }

        // Should never reach here, but defensive fallback
        return weights.keySet().iterator().next();
    }
}
