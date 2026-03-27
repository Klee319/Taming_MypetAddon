package com.mypetaddon.bond;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.personality.Personality;
import com.mypetaddon.stats.StatsManager;
import de.Keyle.MyPet.MyPetApi;
import de.Keyle.MyPet.api.entity.MyPet;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages bond/affinity experience gain and decay with source-specific debounce.
 * Bond EXP accumulates and determines the bond level (1-5), which provides
 * additive stat bonuses via {@link BondLevel}.
 */
public final class BondManager {

    private final MyPetAddonPlugin plugin;
    private final ConfigManager configManager;
    private final PetDataCache petDataCache;
    private final Logger logger;

    /** Set after StatsManager is initialized (avoids circular dependency). */
    @Nullable
    private volatile StatsManager statsManager;

    /** Addon Pet ID -> (source -> last gain timestamp). */
    private final Map<UUID, Map<String, Long>> lastGainTimestamps = new ConcurrentHashMap<>();

    public BondManager(@NotNull MyPetAddonPlugin plugin,
                       @NotNull ConfigManager configManager,
                       @NotNull PetDataCache petDataCache) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.petDataCache = petDataCache;
        this.logger = plugin.getLogger();
    }

    /**
     * Sets the stats manager for bond-level-change stat reapplication.
     * Called after StatsManager is initialized.
     */
    public void setStatsManager(@NotNull StatsManager statsManager) {
        this.statsManager = statsManager;
    }

    /**
     * Adds bond experience to a pet from a specific source, subject to debounce.
     * If the LOYAL personality is active, applies the bondExpMultiplier custom effect.
     *
     * @param addonPetId  the addon-internal pet UUID
     * @param source      the bond source identifier (e.g. "combat-kill", "feeding")
     * @param baseAmount  the base EXP amount (read from config by caller or passed directly)
     * @param personality the pet's personality (for LOYAL bond-gain multiplier)
     */
    public void addBondExp(@NotNull UUID addonPetId,
                           @NotNull String source,
                           int baseAmount,
                           @NotNull Personality personality) {
        if (baseAmount <= 0) {
            return;
        }

        // Debounce check
        long now = System.currentTimeMillis();
        long debounceMs = configManager.getBondDebounce(source);

        Map<String, Long> sourceTimestamps = lastGainTimestamps
                .computeIfAbsent(addonPetId, k -> new ConcurrentHashMap<>());

        // Atomic check-and-update using compute to avoid race conditions
        boolean[] debounced = {false};
        sourceTimestamps.compute(source, (key, lastGain) -> {
            if (lastGain != null && (now - lastGain) < debounceMs) {
                debounced[0] = true;
                return lastGain; // Keep existing timestamp
            }
            return now; // Update to current time
        });
        if (debounced[0]) {
            return; // Still in debounce window
        }

        // Apply LOYAL personality multiplier
        double multiplier = personality.getCustomEffect("bondExpMultiplier", 1.0);
        int finalAmount = (int) Math.round(baseAmount * multiplier);

        // Update cache
        updateBondInCache(addonPetId, finalAmount);
    }

    /**
     * Applies daily bond decay based on time since last login.
     * Decay is capped by the max-offline-decay config value.
     *
     * @param addonPetId    the addon-internal pet UUID
     * @param lastLoginTime the player's last login timestamp in milliseconds
     */
    public void applyDailyDecay(@NotNull UUID addonPetId, long lastLoginTime) {
        long now = System.currentTimeMillis();
        long offlineMs = now - lastLoginTime;
        long daysOffline = offlineMs / (1000L * 60L * 60L * 24L);

        if (daysOffline <= 0) {
            return;
        }

        int dailyDecay = configManager.getBondLoss("daily-decay");
        int maxOfflineDecay = configManager.getBondLoss("max-offline-decay");

        if (dailyDecay <= 0) {
            return;
        }

        int totalDecay = (int) Math.min(daysOffline * dailyDecay, maxOfflineDecay);
        if (totalDecay <= 0) {
            return;
        }

        updateBondInCache(addonPetId, -totalDecay);
    }

    /**
     * Applies a bond penalty for pet death (bypasses debounce).
     *
     * @param addonPetId   the addon-internal pet UUID
     * @param penaltyAmount the amount of bond EXP to subtract
     */
    public void applyDeathPenalty(@NotNull UUID addonPetId, int penaltyAmount) {
        if (penaltyAmount <= 0) {
            return;
        }
        updateBondInCache(addonPetId, -penaltyAmount);
    }

    /**
     * Cleans up debounce timestamps for a pet (e.g. when pet is removed).
     */
    public void cleanup(@NotNull UUID addonPetId) {
        lastGainTimestamps.remove(addonPetId);
    }

    // ─── Internal ────────────────────────────────────────────────

    /**
     * Updates bond EXP in the cache and checks for level changes.
     * Ensures EXP does not go below 0.
     */
    private void updateBondInCache(@NotNull UUID addonPetId, int expDelta) {
        // Find the PetData by scanning cache (addonPetId lookup)
        // The cache exposes updateBond which handles the scan internally
        // We need current values first to calculate new level

        // We iterate through cached data to find the matching entry
        // This is a known limitation - consider adding addonPetId index to cache
        PetData petData = findPetDataByAddonId(addonPetId);
        if (petData == null) {
            return;
        }

        int currentExp = petData.bondExp();
        int newExp = Math.max(0, currentExp + expDelta);
        int currentLevel = petData.bondLevel();
        int newLevel = BondLevel.fromExp(newExp);

        petDataCache.updateBond(addonPetId, newLevel, newExp);

        if (newLevel != currentLevel) {
            logger.info("[Bond] Pet " + addonPetId + " bond level changed: "
                    + currentLevel + " -> " + newLevel);

            // Trigger stat reapply on the main thread
            StatsManager sm = statsManager;
            if (sm != null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player owner = Bukkit.getPlayer(petData.ownerUuid());
                    if (owner == null || !owner.isOnline()) {
                        return;
                    }
                    try {
                        var pm = MyPetApi.getPlayerManager();
                        if (!pm.isMyPetPlayer(owner)) {
                            return;
                        }
                        var myPetPlayer = pm.getMyPetPlayer(owner);
                        if (myPetPlayer.hasMyPet()) {
                            sm.applyStats(myPetPlayer.getMyPet());
                        }
                    } catch (Exception e) {
                        logger.fine("[Bond] Could not reapply stats after bond level change: "
                                + e.getMessage());
                    }
                });
            }
        }
    }

    /**
     * Finds PetData by addon pet ID by checking the cache.
     * Returns null if not found.
     */
    private PetData findPetDataByAddonId(@NotNull UUID addonPetId) {
        return petDataCache.getByAddonPetId(addonPetId);
    }
}
