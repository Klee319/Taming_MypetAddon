package com.mypetaddon.data.cache;

import com.mypetaddon.data.PetData;
import com.mypetaddon.data.PetDataRepository;
import com.mypetaddon.data.PetStats;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ConcurrentHashMap-based in-memory cache with dirty tracking.
 * Provides fast lookups by MyPet UUID and addon pet ID.
 * Dirty entries are periodically flushed to the database.
 */
public final class PetDataCache {

    private final PetDataRepository repository;
    private final JavaPlugin plugin;
    private final Logger logger;

    /** MyPet UUID -> PetData */
    private final Map<UUID, PetData> dataByMypetUuid = new ConcurrentHashMap<>();

    /** Addon Pet ID -> PetStats */
    private final Map<UUID, PetStats> statsByAddonPetId = new ConcurrentHashMap<>();

    /** Addon Pet IDs that have been modified and need DB write */
    private final Set<UUID> dirtyAddonPetIds = ConcurrentHashMap.newKeySet();

    public PetDataCache(@NotNull PetDataRepository repository, @NotNull JavaPlugin plugin) {
        this.repository = repository;
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Gets pet data by MyPet UUID. Loads from database if not cached.
     *
     * @param mypetUuid the MyPet internal UUID
     * @return the PetData, or null if not found
     */
    @Nullable
    public PetData get(@NotNull UUID mypetUuid) {
        PetData cached = dataByMypetUuid.get(mypetUuid);
        if (cached != null) {
            return cached;
        }

        // Load from DB on cache miss
        return repository.findByMypetUuid(mypetUuid)
                .map(data -> {
                    dataByMypetUuid.put(mypetUuid, data);
                    return data;
                })
                .orElse(null);
    }

    /**
     * Gets pet stats by addon pet ID. Loads from database if not cached.
     *
     * @param addonPetId the addon-internal pet UUID
     * @return the PetStats, or null if not found
     */
    @Nullable
    public PetStats getStats(@NotNull UUID addonPetId) {
        PetStats cached = statsByAddonPetId.get(addonPetId);
        if (cached != null) {
            return cached;
        }

        return repository.findStatsByAddonPetId(addonPetId)
                .map(stats -> {
                    statsByAddonPetId.put(addonPetId, stats);
                    return stats;
                })
                .orElse(null);
    }

    /**
     * Puts pet data and stats into the cache and marks them dirty for DB persistence.
     */
    public void put(@NotNull PetData data, @NotNull PetStats stats) {
        dataByMypetUuid.put(data.mypetUuid(), data);
        statsByAddonPetId.put(data.addonPetId(), stats);
        dirtyAddonPetIds.add(data.addonPetId());
    }

    /**
     * Updates bond values in the cache and marks the entry dirty.
     * Scans the cache to find the matching entry by addon pet ID.
     */
    public void updateBond(@NotNull UUID addonPetId, int bondLevel, int bondExp) {
        dataByMypetUuid.replaceAll((mypetUuid, existing) -> {
            if (existing.addonPetId().equals(addonPetId)) {
                dirtyAddonPetIds.add(addonPetId);
                return existing.withBond(bondLevel, bondExp);
            }
            return existing;
        });
    }

    /**
     * Removes a pet from the cache by its MyPet UUID.
     */
    public void invalidate(@NotNull UUID mypetUuid) {
        PetData removed = dataByMypetUuid.remove(mypetUuid);
        if (removed != null) {
            statsByAddonPetId.remove(removed.addonPetId());
            dirtyAddonPetIds.remove(removed.addonPetId());
        }
    }

    /**
     * Writes all dirty entries to the database.
     * Called periodically by the async scheduler task.
     */
    public void flushDirty() {
        if (dirtyAddonPetIds.isEmpty()) {
            return;
        }

        // Snapshot and clear dirty set to avoid holding lock during IO
        List<UUID> toFlush = new ArrayList<>(dirtyAddonPetIds);
        dirtyAddonPetIds.removeAll(toFlush);

        for (UUID addonPetId : toFlush) {
            try {
                PetData data = findDataByAddonPetId(addonPetId);
                PetStats stats = statsByAddonPetId.get(addonPetId);

                if (data != null && stats != null) {
                    repository.save(data, stats);
                } else if (data != null) {
                    // Stats not cached, just persist bond update
                    repository.updateBond(addonPetId, data.bondLevel(), data.bondExp());
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to flush dirty pet: " + addonPetId, e);
                // Re-add to dirty set for retry on next tick
                dirtyAddonPetIds.add(addonPetId);
            }
        }
    }

    /**
     * Writes ALL cached entries to the database synchronously.
     * Called during plugin disable (onDisable) to ensure no data loss.
     */
    public void flushAll() {
        logger.info("[Cache] Flushing all cached pet data to database...");
        int count = 0;

        for (Map.Entry<UUID, PetData> entry : dataByMypetUuid.entrySet()) {
            PetData data = entry.getValue();
            PetStats stats = statsByAddonPetId.get(data.addonPetId());

            if (stats != null) {
                try {
                    repository.save(data, stats);
                    count++;
                } catch (Exception e) {
                    logger.log(Level.SEVERE,
                            "Failed to flush pet on shutdown: " + data.addonPetId(), e);
                }
            }
        }

        dirtyAddonPetIds.clear();
        logger.info("[Cache] Flushed " + count + " pet records to database.");
    }

    // --- Internal ---

    @Nullable
    private PetData findDataByAddonPetId(@NotNull UUID addonPetId) {
        for (PetData data : dataByMypetUuid.values()) {
            if (data.addonPetId().equals(addonPetId)) {
                return data;
            }
        }
        return null;
    }
}
