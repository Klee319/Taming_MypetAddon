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
import java.util.function.Consumer;
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

    /** Addon Pet ID -> MyPet UUID (reverse index for O(1) lookup) */
    private final Map<UUID, UUID> addonPetIdToMypetUuid = new ConcurrentHashMap<>();

    /** Addon Pet IDs that have been modified and need DB write */
    private final Set<UUID> dirtyAddonPetIds = ConcurrentHashMap.newKeySet();

    /** Optional callback to preload equipment cache when pet data is loaded. */
    private volatile Consumer<UUID> equipmentPreloader;

    public PetDataCache(@NotNull PetDataRepository repository, @NotNull JavaPlugin plugin) {
        this.repository = repository;
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Sets an equipment preloader callback that runs during {@link #preloadForPlayer(UUID)}.
     * Called for each addon pet ID to warm the equipment cache.
     */
    public void setEquipmentPreloader(@Nullable Consumer<UUID> preloader) {
        this.equipmentPreloader = preloader;
    }

    /**
     * Gets pet data by MyPet UUID from cache only.
     * Returns null on cache miss instead of doing a synchronous DB query.
     * Data should be preloaded via {@link #preloadForPlayer(UUID)} on PlayerJoin.
     *
     * @param mypetUuid the MyPet internal UUID
     * @return the PetData, or null if not cached
     */
    @Nullable
    public PetData get(@NotNull UUID mypetUuid) {
        PetData cached = dataByMypetUuid.get(mypetUuid);
        if (cached == null) {
            logger.log(Level.FINE, "[Cache] Cache miss for MyPet UUID {0}", mypetUuid);
        }
        return cached;
    }

    /**
     * Gets pet stats by addon pet ID from cache only.
     * Returns null on cache miss instead of doing a synchronous DB query.
     * Stats should be preloaded via {@link #preloadForPlayer(UUID)} on PlayerJoin.
     *
     * @param addonPetId the addon-internal pet UUID
     * @return the PetStats, or null if not cached
     */
    @Nullable
    public PetStats getStats(@NotNull UUID addonPetId) {
        PetStats cached = statsByAddonPetId.get(addonPetId);
        if (cached == null) {
            logger.log(Level.FINE, "[Cache] Cache miss for addon pet ID {0}", addonPetId);
        }
        return cached;
    }

    /**
     * Preloads all pet data for a player into the cache asynchronously.
     * Should be called on PlayerJoinEvent to avoid sync DB hits on cache misses.
     *
     * @param ownerUuid the player's UUID
     */
    public void preloadForPlayer(@NotNull UUID ownerUuid) {
        List<PetData> pets = repository.findByOwner(ownerUuid);
        for (PetData data : pets) {
            dataByMypetUuid.putIfAbsent(data.mypetUuid(), data);
            addonPetIdToMypetUuid.putIfAbsent(data.addonPetId(), data.mypetUuid());
            repository.findStatsByAddonPetId(data.addonPetId())
                    .ifPresent(stats -> statsByAddonPetId.putIfAbsent(data.addonPetId(), stats));

            // Warm equipment cache to avoid sync DB query on main thread
            Consumer<UUID> preloader = equipmentPreloader;
            if (preloader != null) {
                try {
                    preloader.accept(data.addonPetId());
                } catch (Exception e) {
                    logger.log(Level.FINE, "[Cache] Equipment preload failed for {0}", data.addonPetId());
                }
            }
        }
    }

    /**
     * Puts pet data and stats into the cache and marks them dirty for DB persistence.
     */
    public void put(@NotNull PetData data, @NotNull PetStats stats) {
        dataByMypetUuid.put(data.mypetUuid(), data);
        addonPetIdToMypetUuid.put(data.addonPetId(), data.mypetUuid());
        statsByAddonPetId.put(data.addonPetId(), stats);
        dirtyAddonPetIds.add(data.addonPetId());
    }

    /**
     * Updates bond values in the cache and marks the entry dirty.
     * Scans the cache to find the matching entry by addon pet ID.
     */
    public void updateBond(@NotNull UUID addonPetId, int bondLevel, int bondExp) {
        UUID mypetUuid = addonPetIdToMypetUuid.get(addonPetId);
        if (mypetUuid == null) {
            return;
        }

        dataByMypetUuid.computeIfPresent(mypetUuid, (key, existing) -> {
            dirtyAddonPetIds.add(addonPetId);
            return existing.withBond(bondLevel, bondExp);
        });
    }

    /**
     * Removes a pet from the cache by its MyPet UUID.
     */
    public void invalidate(@NotNull UUID mypetUuid) {
        PetData removed = dataByMypetUuid.remove(mypetUuid);
        if (removed != null) {
            addonPetIdToMypetUuid.remove(removed.addonPetId());
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

            try {
                if (stats != null) {
                    repository.save(data, stats);
                } else {
                    // Stats not cached — persist bond update at minimum
                    repository.updateBond(data.addonPetId(), data.bondLevel(), data.bondExp());
                }
                count++;
            } catch (Exception e) {
                logger.log(Level.SEVERE,
                        "Failed to flush pet on shutdown: " + data.addonPetId(), e);
            }
        }

        dirtyAddonPetIds.clear();
        logger.info("[Cache] Flushed " + count + " pet records to database.");
    }

    /**
     * Gets pet data by addon pet ID by scanning the cache.
     * Returns null if not found in the cache.
     *
     * @param addonPetId the addon-internal pet UUID
     * @return the PetData, or null if not found
     */
    @Nullable
    public PetData getByAddonPetId(@NotNull UUID addonPetId) {
        return findDataByAddonPetId(addonPetId);
    }

    // --- Internal ---

    @Nullable
    private PetData findDataByAddonPetId(@NotNull UUID addonPetId) {
        UUID mypetUuid = addonPetIdToMypetUuid.get(addonPetId);
        if (mypetUuid == null) {
            return null;
        }
        return dataByMypetUuid.get(mypetUuid);
    }
}
