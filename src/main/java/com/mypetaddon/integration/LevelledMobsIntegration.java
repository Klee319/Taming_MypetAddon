package com.mypetaddon.integration;

import com.mypetaddon.config.ConfigManager;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Three-layer integration with LevelledMobs for retrieving mob levels.
 *
 * <ol>
 *   <li>Layer 1 - Official LevelledMobs API via reflection (no compile dependency)</li>
 *   <li>Layer 2 - PDC fallback using a configurable key name</li>
 *   <li>Layer 3 - Returns 0 with a warning log</li>
 * </ol>
 *
 * The API is lazily initialized on the first {@link #getMobLevel} call.
 */
public final class LevelledMobsIntegration {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final Logger logger;

    // Lazy-init state
    private volatile boolean initialized = false;
    private boolean apiAvailable = false;
    private Object levelInterface = null;
    private Method getLevelMethod = null;

    public LevelledMobsIntegration(@NotNull JavaPlugin plugin, @NotNull ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.logger = plugin.getLogger();
    }

    /**
     * Returns the mob level for a given living entity using a three-layer fallback strategy.
     *
     * @param entity the living entity to check
     * @return the mob level, or 0 if unavailable
     */
    public int getMobLevel(@NotNull LivingEntity entity) {
        if (!initialized) {
            initializeApi();
        }

        // Layer 1: Official API via reflection
        if (apiAvailable && levelInterface != null && getLevelMethod != null) {
            try {
                Object result = getLevelMethod.invoke(levelInterface, entity);
                if (result instanceof Integer level) {
                    return level;
                }
            } catch (Exception e) {
                logger.warning("[LevelledMobs] API call failed, falling back to PDC: " + e.getMessage());
            }
        }

        // Layer 2: PDC fallback
        int pdcLevel = readLevelFromPdc(entity);
        if (pdcLevel > 0) {
            return pdcLevel;
        }

        // Layer 3: Default to 0
        if (apiAvailable) {
            logger.fine("[LevelledMobs] Could not determine level for entity " + entity.getType()
                    + ", defaulting to 0.");
        }
        return 0;
    }

    /**
     * Returns whether LevelledMobs integration is available
     * (either API or PDC fallback detected).
     */
    public boolean isAvailable() {
        if (!initialized) {
            initializeApi();
        }
        return apiAvailable;
    }

    // ─── Internal ────────────────────────────────────────────────

    private synchronized void initializeApi() {
        if (initialized) {
            return;
        }

        try {
            // Attempt to load the LevelledMobs LevelInterface via reflection
            Class<?> levelInterfaceClass = Class.forName("me.lokka30.levelledmobs.LevelInterface");
            Class<?> levelledMobsClass = Class.forName("me.lokka30.levelledmobs.LevelledMobs");

            // Get the singleton instance
            Method getInstanceMethod = levelledMobsClass.getMethod("getInstance");
            Object lmInstance = getInstanceMethod.invoke(null);

            // Get the LevelInterface from the instance
            Method getLevelInterfaceMethod = levelledMobsClass.getMethod("getLevelInterface");
            levelInterface = getLevelInterfaceMethod.invoke(lmInstance);

            // Cache the getLevelOfMob method
            getLevelMethod = levelInterfaceClass.getMethod("getLevelOfMob", LivingEntity.class);

            apiAvailable = true;
            logger.info("[LevelledMobs] API integration loaded successfully.");
        } catch (ClassNotFoundException e) {
            // LevelledMobs not installed - check if PDC fallback might work
            if (plugin.getServer().getPluginManager().getPlugin("LevelledMobs") != null) {
                apiAvailable = true;
                logger.info("[LevelledMobs] Plugin detected but API unavailable. Using PDC fallback.");
            } else {
                apiAvailable = false;
                logger.info("[LevelledMobs] Not installed. Rarity will use fallback weights.");
            }
        } catch (Exception e) {
            apiAvailable = plugin.getServer().getPluginManager().getPlugin("LevelledMobs") != null;
            logger.warning("[LevelledMobs] API reflection failed: " + e.getMessage()
                    + ". Using PDC fallback.");
        } finally {
            initialized = true;
        }
    }

    private int readLevelFromPdc(@NotNull LivingEntity entity) {
        try {
            String pdcKeyName = configManager.getLevelledMobsPdcKey();
            NamespacedKey key = new NamespacedKey("levelledmobs", pdcKeyName);
            PersistentDataContainer pdc = entity.getPersistentDataContainer();

            if (pdc.has(key, PersistentDataType.INTEGER)) {
                Integer value = pdc.get(key, PersistentDataType.INTEGER);
                return value != null ? value : 0;
            }
        } catch (Exception e) {
            logger.fine("[LevelledMobs] PDC read failed: " + e.getMessage());
        }
        return 0;
    }
}
