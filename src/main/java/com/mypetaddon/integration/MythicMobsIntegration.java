package com.mypetaddon.integration;

import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Reflection-based integration with MythicMobs.
 * Uses lazy initialization to load the MythicBukkit class on first use,
 * avoiding a hard compile dependency.
 */
public final class MythicMobsIntegration {

    private final JavaPlugin plugin;
    private final Logger logger;

    // Lazy-init state
    private volatile boolean initialized = false;
    private boolean available = false;
    private Object mythicBukkitInstance = null;
    private Method getMobManagerMethod = null;
    private Method isActiveMobMethod = null;

    public MythicMobsIntegration(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Checks whether the given entity is a MythicMobs mob.
     *
     * @param entity the entity to check
     * @return true if the entity is a MythicMobs mob, false otherwise or if MythicMobs is not installed
     */
    public boolean isMythicMob(@NotNull Entity entity) {
        if (!initialized) {
            initializeApi();
        }

        if (!available) {
            return false;
        }

        try {
            Object mobManager = getMobManagerMethod.invoke(mythicBukkitInstance);
            if (mobManager == null) {
                return false;
            }
            Object result = isActiveMobMethod.invoke(mobManager, entity.getUniqueId());
            // getActiveMob returns Optional<ActiveMob> — check isPresent(), not null
            if (result instanceof java.util.Optional<?> optional) {
                return optional.isPresent();
            }
            return result != null;
        } catch (Exception e) {
            logger.fine("[MythicMobs] Failed to check entity: " + e.getMessage());
            return false;
        }
    }

    /**
     * Returns whether MythicMobs integration is available.
     */
    public boolean isAvailable() {
        if (!initialized) {
            initializeApi();
        }
        return available;
    }

    // ─── Internal ────────────────────────────────────────────────

    private synchronized void initializeApi() {
        if (initialized) {
            return;
        }

        try {
            Class<?> mythicBukkitClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");

            // Get the singleton instance
            Method instMethod = mythicBukkitClass.getMethod("inst");
            mythicBukkitInstance = instMethod.invoke(null);

            // Cache getMobManager method
            getMobManagerMethod = mythicBukkitClass.getMethod("getMobManager");

            // Get the MobManager class and its getActiveMob method (returns Optional)
            Object mobManager = getMobManagerMethod.invoke(mythicBukkitInstance);
            Class<?> mobManagerClass = mobManager.getClass();
            isActiveMobMethod = mobManagerClass.getMethod("getActiveMob", java.util.UUID.class);

            available = true;
            logger.info("[MythicMobs] Integration loaded successfully.");
        } catch (ClassNotFoundException e) {
            available = false;
            logger.info("[MythicMobs] Not installed. MythicMobs features disabled.");
        } catch (Exception e) {
            available = false;
            logger.warning("[MythicMobs] Reflection failed: " + e.getMessage()
                    + ". MythicMobs features disabled.");
        } finally {
            initialized = true;
        }
    }
}
