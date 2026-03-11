package com.mypetaddon.bond;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.personality.Personality;
import org.jetbrains.annotations.NotNull;

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

        Long lastGain = sourceTimestamps.get(source);
        if (lastGain != null && (now - lastGain) < debounceMs) {
            return; // Still in debounce window
        }
        sourceTimestamps.put(source, now);

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
            // Stats will be recalculated with the new bond level on next MyPet activation
            // or can be triggered manually through StatsManager
        }
    }

    /**
     * Finds PetData by addon pet ID by checking the cache.
     * Returns null if not found.
     */
    @SuppressWarnings("unchecked")
    private PetData findPetDataByAddonId(@NotNull UUID addonPetId) {
        // Access the cache's internal map via reflection as a fallback
        // Ideally the cache would expose a getByAddonPetId method
        try {
            var field = PetDataCache.class.getDeclaredField("dataByMypetUuid");
            field.setAccessible(true);
            Map<UUID, PetData> dataMap = (Map<UUID, PetData>) field.get(petDataCache);

            for (PetData data : dataMap.values()) {
                if (data.addonPetId().equals(addonPetId)) {
                    return data;
                }
            }
        } catch (Exception e) {
            logger.warning("[Bond] Failed to look up PetData for addon pet: " + addonPetId);
        }
        return null;
    }
}
