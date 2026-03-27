package com.mypetaddon.stats;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.PetStats;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.personality.Personality;
import de.Keyle.MyPet.MyPetApi;
import de.Keyle.MyPet.api.entity.MyPet;
import de.Keyle.MyPet.api.entity.MyPetBukkitEntity;
import de.Keyle.MyPet.api.entity.StoredMyPet;
import de.Keyle.MyPet.api.event.MyPetActivatedEvent;
import de.Keyle.MyPet.api.event.MyPetExpEvent;
import de.Keyle.MyPet.api.event.MyPetLevelDownEvent;
import de.Keyle.MyPet.api.event.MyPetLevelUpEvent;
import de.Keyle.MyPet.api.event.MyPetRemoveEvent;
import de.Keyle.MyPet.api.event.MyPetSelectSkilltreeEvent;
import de.Keyle.MyPet.api.player.MyPetPlayer;
import de.Keyle.MyPet.api.skill.modifier.UpgradeModifier;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Applies calculated stats to MyPet entities via UpgradeModifier.
 * Manages the lifecycle of custom modifiers (apply/unload) and listens
 * to MyPet events for automatic stat application.
 */
public final class StatsManager implements Listener {

    private final MyPetAddonPlugin plugin;
    private final ConfigManager configManager;
    private final PetDataCache petDataCache;
    private final ModifierPipeline modifierPipeline;
    private final Logger logger;
    private final Random random = new Random();
    private ExpModifierListener expModifierListener;

    /** MyPet UUID -> list of unload functions for applied modifiers. */
    private final Map<UUID, List<Runnable>> activeModifiers = new ConcurrentHashMap<>();

    /** MyPet UUID -> snapshot of exp when the pet was last deactivated.
     *  Used to detect and restore unintended exp loss across save/load cycles. */
    private final Map<UUID, double[]> expSnapshots = new ConcurrentHashMap<>();

    public void setExpModifierListener(@NotNull ExpModifierListener listener) {
        this.expModifierListener = listener;
    }

    public StatsManager(@NotNull MyPetAddonPlugin plugin,
                        @NotNull ConfigManager configManager,
                        @NotNull PetDataCache petDataCache,
                        @NotNull ModifierPipeline modifierPipeline) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.petDataCache = petDataCache;
        this.modifierPipeline = modifierPipeline;
        this.logger = plugin.getLogger();
    }

    /**
     * Applies all calculated stats to a MyPet entity.
     * Unloads any existing modifiers first, then recalculates and applies.
     *
     * @param myPet the active MyPet to apply stats to
     */
    public void applyStats(@NotNull MyPet myPet) {
        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            return;
        }

        PetStats petStats = petDataCache.getStats(petData.addonPetId());
        if (petStats == null) {
            return;
        }

        // Unload existing modifiers
        unloadStats(myPet);

        List<Runnable> unloaders = new ArrayList<>();
        activeModifiers.put(myPet.getUUID(), unloaders);

        // Apply stat modifiers for all stats (base and upgraded)
        int petLevel = 0;
        try {
            petLevel = myPet.getExperience().getLevel();
        } catch (Exception ignored) {}
        java.util.Set<String> allStatNames = new java.util.LinkedHashSet<>(petStats.baseValues().keySet());
        allStatNames.addAll(petStats.upgradedValues().keySet());
        for (String statName : allStatNames) {
            double finalValue = modifierPipeline.calculate(statName, petData, petStats, petLevel);

            try {
                applyStatModifier(myPet, statName, finalValue, unloaders);
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "Failed to apply stat '" + statName + "' to MyPet " + myPet.getUUID(), e);
            }
        }

        // Scale and personality effects are applied via onCreatureSpawn listener
        // because MyPet recreates the Bukkit entity on teleport/chunk reload,
        // which loses any Attribute modifiers. The spawn listener catches every
        // entity creation and re-applies them reliably.
    }

    /**
     * Unloads all applied modifiers for a MyPet.
     *
     * @param myPet the MyPet to unload stats from
     */
    public void unloadStats(@NotNull MyPet myPet) {
        List<Runnable> unloaders = activeModifiers.remove(myPet.getUUID());
        if (unloaders == null) {
            return;
        }

        for (Runnable unloader : unloaders) {
            try {
                unloader.run();
            } catch (Exception e) {
                logger.log(Level.FINE, "Error unloading modifier for MyPet " + myPet.getUUID(), e);
            }
        }
    }

    /**
     * Applies stats to all currently active MyPets.
     * Called during plugin enable for pets that are already loaded.
     * Also restores exp snapshots for pets that were active before our listeners registered
     * (MyPet activates pets before our plugin enables due to depend order).
     */
    public void applyAllActivePets() {
        boolean preventLoss = configManager.getConfig().getBoolean(
                "level-bonuses.prevent-exp-loss", true);

        // Preload cache for all online players first (sync on startup is acceptable)
        for (MyPet myPet : MyPetApi.getMyPetManager().getAllActiveMyPets()) {
            try {
                petDataCache.preloadForPlayer(myPet.getOwner().getPlayerUUID());
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "Failed to preload data for MyPet owner " + myPet.getOwner().getName(), e);
            }
        }

        for (MyPet myPet : MyPetApi.getMyPetManager().getAllActiveMyPets()) {
            try {
                // Restore exp from snapshot BEFORE applying stats,
                // so the pet has the correct level for stat calculation.
                if (preventLoss) {
                    restoreExpFromSnapshot(myPet);
                }
                applyStats(myPet);
            } catch (Exception e) {
                logger.log(Level.SEVERE,
                        "Failed to apply stats to active MyPet " + myPet.getUUID(), e);
            }
        }
    }

    /**
     * Removes all applied modifiers and caps pet health for a consistent save.
     * Called during plugin disable (before MyPet saves state to storage).
     */
    public void unloadAll() {
        for (MyPet myPet : MyPetApi.getMyPetManager().getAllActiveMyPets()) {
            try {
                // Snapshot exp before unloading
                double currentExp = myPet.getExperience().getExp();
                int currentLevel = myPet.getExperience().getLevel();
                expSnapshots.put(myPet.getUUID(), new double[]{currentExp, currentLevel});

                List<Runnable> unloaders = activeModifiers.remove(myPet.getUUID());
                if (unloaders != null) {
                    for (Runnable unloader : unloaders) {
                        try {
                            unloader.run();
                        } catch (Exception ignored) {}
                    }
                }
                // Cap health to base maxHealth (without our modifiers)
                double maxHealth = myPet.getMaxHealth();
                if (myPet.getHealth() > maxHealth) {
                    myPet.setHealth(maxHealth);
                }
            } catch (Exception e) {
                logger.log(Level.FINE, "Error during unloadAll for pet " + myPet.getUUID(), e);
            }
        }
        activeModifiers.clear();
    }

    /**
     * Returns the modifier pipeline used for stat calculations.
     */
    @NotNull
    public ModifierPipeline getModifierPipeline() {
        return modifierPipeline;
    }

    /**
     * Creates base stats for a newly tamed pet by reading config base values
     * and rolling random values within the defined ranges.
     *
     * @param petData the pet's core data (mob type determines stat ranges)
     * @return a new PetStats with randomized base values
     */
    @NotNull
    public PetStats createBaseStats(@NotNull PetData petData) {
        Map<String, double[]> ranges = configManager.getPetBaseValues(petData.mobType());
        Map<String, Double> baseValues = new LinkedHashMap<>();

        for (Map.Entry<String, double[]> entry : ranges.entrySet()) {
            String statName = entry.getKey();
            double min = entry.getValue()[0];
            double max = entry.getValue()[1];

            // Mild bias toward middle-low values using triangular distribution
            double r1 = random.nextDouble();
            double r2 = random.nextDouble();
            double roll = Math.min(r1, r2); // average of two rolls biased toward lower end
            double value = min + (max - min) * roll;

            // Round to one decimal place for precision (avoid Math.floor crushing small ranges)
            value = Math.round(value * 10.0) / 10.0;
            baseValues.put(statName, value);
        }

        return new PetStats(petData.addonPetId(), Map.copyOf(baseValues), Map.of());
    }

    // ─── MyPet Event Listeners ──────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onMyPetActivated(@NotNull MyPetActivatedEvent event) {
        MyPet myPet = event.getMyPet();

        // Restore exp if it decreased since the last deactivation snapshot.
        boolean preventLoss = configManager.getConfig().getBoolean(
                "level-bonuses.prevent-exp-loss", true);
        if (preventLoss) {
            restoreExpFromSnapshot(myPet);

            // Schedule multiple delayed verifications to catch exp reductions that
            // happen AFTER the activation event (MyPet internal recalculations,
            // other plugin interference, delayed death penalties, etc.).
            for (long delay : new long[]{5L, 20L, 40L}) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (myPet.getStatus() == MyPet.PetState.Here
                            || myPet.getStatus() == MyPet.PetState.Despawned) {
                        verifyAndRestoreExp(myPet);
                    }
                }, delay);
            }
        }

        applyStats(myPet);
    }

    /**
     * Intercepts MyPetLevelDownEvent (which is NOT cancellable).
     * When a level decrease is detected, immediately restores the pet's exp/level
     * from the snapshot using reflection to bypass MyPet's event system.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMyPetLevelDown(@NotNull MyPetLevelDownEvent event) {
        boolean preventLoss = configManager.getConfig().getBoolean(
                "level-bonuses.prevent-exp-loss", true);
        if (!preventLoss) {
            return;
        }

        MyPet myPet = event.getPet();
        double[] snapshot = expSnapshots.get(myPet.getUUID());
        if (snapshot == null) {
            return;
        }

        int savedLevel = (int) snapshot[1];
        if (event.getLevel() < savedLevel) {
            logger.log(Level.INFO,
                    "[ExpProtect] Detected uncancellable level down for pet {0}: {1} -> {2}. Restoring to level {3}.",
                    new Object[]{myPet.getUUID(), event.fromLevel(), event.getLevel(), savedLevel});
            // Delay 1 tick to run after MyPet finishes processing the level-down event
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                forceSetExp(myPet, snapshot[0], savedLevel);
                applyStats(myPet);
            }, 1L);
        }
    }

    /**
     * Tracks positive exp gains to keep snapshots up-to-date.
     * Runs at MONITOR priority (after ExpModifierListener at LOWEST has applied multiplier).
     * Only updates snapshot when exp increases, never on decrease.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMyPetExpGain(@NotNull MyPetExpEvent event) {
        if (event.getExp() <= 0) {
            return;
        }
        boolean preventLoss = configManager.getConfig().getBoolean(
                "level-bonuses.prevent-exp-loss", true);
        if (!preventLoss) {
            return;
        }
        // Schedule 1 tick later so MyPet has applied the exp change
        MyPet myPet = event.getPet();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                double currentExp = myPet.getExperience().getExp();
                int currentLevel = myPet.getExperience().getLevel();
                double[] existing = expSnapshots.get(myPet.getUUID());
                // Only update if exp/level increased (never overwrite with lower values)
                if (existing == null
                        || currentLevel > (int) existing[1]
                        || (currentLevel == (int) existing[1] && currentExp > existing[0])) {
                    expSnapshots.put(myPet.getUUID(), new double[]{currentExp, currentLevel});
                }
            } catch (Exception ignored) {}
        }, 1L);
    }

    /**
     * Updates snapshot when a pet levels up (confirmed good value).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMyPetLevelUp(@NotNull MyPetLevelUpEvent event) {
        boolean preventLoss = configManager.getConfig().getBoolean(
                "level-bonuses.prevent-exp-loss", true);
        if (!preventLoss) {
            return;
        }
        MyPet myPet = event.getPet();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                double currentExp = myPet.getExperience().getExp();
                int currentLevel = myPet.getExperience().getLevel();
                expSnapshots.put(myPet.getUUID(), new double[]{currentExp, currentLevel});
            } catch (Exception ignored) {}
        }, 1L);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onMyPetRemove(@NotNull MyPetRemoveEvent event) {
        StoredMyPet storedMyPet = event.getMyPet();

        if (storedMyPet instanceof MyPet myPet) {
            // Snapshot exp BEFORE unloading modifiers (unload may trigger side effects).
            try {
                double currentExp = myPet.getExperience().getExp();
                int currentLevel = myPet.getExperience().getLevel();
                expSnapshots.put(myPet.getUUID(), new double[]{currentExp, currentLevel});
            } catch (Exception e) {
                logger.log(Level.FINE, "Could not snapshot exp on pet remove", e);
            }

            // Remove our UpgradeComputer modifiers, then cap health to the new
            // (base) maxHealth so that MyPet saves a consistent state.
            unloadStats(myPet);
            try {
                double maxHealth = myPet.getMaxHealth();
                if (myPet.getHealth() > maxHealth) {
                    myPet.setHealth(maxHealth);
                }
            } catch (Exception e) {
                logger.log(Level.FINE, "Could not cap health on pet remove", e);
            }
        } else {
            activeModifiers.remove(storedMyPet.getUUID());
        }
    }

    /**
     * Checks the exp snapshot for a pet and restores exp if it decreased.
     * Uses reflection to directly set the exp/level fields on MyPetExperience,
     * bypassing setExp/updateExp which fire events that can interfere with restoration.
     *
     * @param myPet the pet to check and potentially restore
     */
    private void restoreExpFromSnapshot(@NotNull MyPet myPet) {
        double[] snapshot = expSnapshots.get(myPet.getUUID());
        if (snapshot == null) {
            return;
        }

        try {
            double savedExp = snapshot[0];
            int savedLevel = (int) snapshot[1];
            double currentExp = myPet.getExperience().getExp();
            int currentLevel = myPet.getExperience().getLevel();

            if (currentExp < savedExp || currentLevel < savedLevel) {
                forceSetExp(myPet, savedExp, savedLevel);
            }
            // Only remove snapshot after successful check (keep for delayed verify)
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not restore exp snapshot for pet " + myPet.getUUID(), e);
        }
    }

    /**
     * Directly sets exp, level, and maxExp fields on MyPetExperience via reflection,
     * completely bypassing MyPet's event system. Also recalculates maxExp via the
     * internal cache to maintain consistency with the restored level.
     */
    private void forceSetExp(@NotNull MyPet myPet, double exp, int level) {
        try {
            var experience = myPet.getExperience();
            Class<?> expClass = experience.getClass();

            java.lang.reflect.Field expField = expClass.getDeclaredField("exp");
            expField.setAccessible(true);
            expField.setDouble(experience, exp);

            java.lang.reflect.Field levelField = expClass.getDeclaredField("level");
            levelField.setAccessible(true);
            levelField.setInt(experience, level);

            // Also update maxExp to match the restored level, preventing internal
            // inconsistency where MyPet's cache disagrees with the level field.
            try {
                java.lang.reflect.Field cacheField = expClass.getDeclaredField("cache");
                cacheField.setAccessible(true);
                Object cache = cacheField.get(experience);
                if (cache != null) {
                    // ExperienceCache.getExpByLevel(level+1) gives the total exp for next level
                    var getExpByLevel = cache.getClass().getMethod("getExpByLevel", int.class);
                    Object nextLevelExp = getExpByLevel.invoke(cache, level + 1);
                    if (nextLevelExp instanceof Number) {
                        java.lang.reflect.Field maxExpField = expClass.getDeclaredField("maxExp");
                        maxExpField.setAccessible(true);
                        maxExpField.setDouble(experience, ((Number) nextLevelExp).doubleValue());
                    }
                }
            } catch (Exception cacheEx) {
                // Non-critical: maxExp out of sync won't cause level loss,
                // just potentially wrong exp bar display until next natural exp gain.
                logger.log(Level.FINE,
                        "[ExpProtect] Could not update maxExp cache for pet " + myPet.getUUID(), cacheEx);
            }

            logger.log(Level.INFO,
                    "[ExpProtect] Restored pet {0} to exp={1}, level={2}",
                    new Object[]{myPet.getUUID(), exp, level});
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "[ExpProtect] Reflection failed, falling back to setExp for pet " + myPet.getUUID(), e);
            // Fallback: use the event-based method
            if (expModifierListener != null) {
                expModifierListener.markRestoring(myPet.getUUID());
            }
            myPet.getExperience().setExp(exp);
        }
    }

    /**
     * Delayed verification: checks if exp dropped since the snapshot was taken
     * and restores it. Called at multiple intervals after activation.
     * Does NOT remove the snapshot — it persists for LevelDown protection
     * and is only updated when exp increases naturally.
     */
    private void verifyAndRestoreExp(@NotNull MyPet myPet) {
        double[] snapshot = expSnapshots.get(myPet.getUUID());
        if (snapshot == null) {
            return;
        }
        try {
            double savedExp = snapshot[0];
            int savedLevel = (int) snapshot[1];
            double currentExp = myPet.getExperience().getExp();
            int currentLevel = myPet.getExperience().getLevel();

            if (currentExp < savedExp || currentLevel < savedLevel) {
                forceSetExp(myPet, savedExp, savedLevel);
                // Re-apply stats since level may have changed
                applyStats(myPet);
            }
        } catch (Exception e) {
            logger.log(Level.FINE, "Delayed exp verify failed for pet " + myPet.getUUID(), e);
        }
    }

    /**
     * Catches every MyPet entity spawn (including respawns after teleport/chunk reload).
     * Applies scale override and personality attribute effects to the fresh Bukkit entity.
     * Runs 1 tick later to ensure MyPet has finished initializing the entity.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(@NotNull CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof MyPetBukkitEntity myPetBukkit)) {
            return;
        }

        MyPet myPet = myPetBukkit.getMyPet();
        // Delay 2 ticks so MyPet finishes all entity setup (including visual updates).
        // Also schedule a secondary application at 10 ticks to catch any late resets.
        for (long delay : new long[]{2L, 10L}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!myPetBukkit.isValid()) {
                    return;
                }
                applyEntityAttributes(myPet, myPetBukkit);
            }, delay);
        }
    }

    /**
     * Applies Bukkit Attribute-based effects (scale, personality knockback resist)
     * to a MyPet's Bukkit entity. Called every time the entity is (re)created.
     * Uses setBaseValue for SCALE instead of modifiers, because MyPet's custom
     * attribute system may strip or conflict with AttributeModifiers on entity recreation.
     */
    private void applyEntityAttributes(@NotNull MyPet myPet, @NotNull LivingEntity entity) {
        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            return;
        }

        // Scale: captured scale from original entity takes priority,
        // then config scale override, then no modification.
        double capturedScale = petData.capturedScale();
        double configScale = configManager.getPetScaleOverride(petData.mobType());
        // capturedScale > 0 means a non-default scale was captured at taming time
        double effectiveScale = capturedScale > 0.0 ? capturedScale : configScale;
        if (effectiveScale != 1.0) {
            AttributeInstance scaleAttr = entity.getAttribute(Attribute.SCALE);
            if (scaleAttr != null) {
                // Use setBaseValue directly instead of modifiers.
                // MyPet's custom entity may strip AttributeModifiers during internal
                // attribute map recreation, but the base value persists.
                scaleAttr.setBaseValue(effectiveScale);
            }
        }

        // Personality knockback resistance
        double knockbackResist = petData.personality().getCustomEffect("knockbackResist", 0.0);
        if (knockbackResist > 0.0) {
            AttributeInstance kbAttr = entity.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            if (kbAttr != null) {
                kbAttr.setBaseValue(knockbackResist);
            }
        }
    }

    @EventHandler
    public void onMyPetSelectSkilltree(@NotNull MyPetSelectSkilltreeEvent event) {
        // Reapply stats after skilltree change (modifiers may have been reset)
        StoredMyPet storedMyPet = event.getMyPet();
        if (storedMyPet instanceof MyPet myPet) {
            applyStats(myPet);
        }
    }

    // ─── Personality Combat Effects ─────────────────────────────

    /**
     * Handles dodge chance: when a MyPet entity is hit, if the pet's personality
     * has a dodgeChance effect, roll against it and cancel damage on success.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPetDamaged(@NotNull EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (!(victim instanceof MyPetBukkitEntity myPetBukkit)) {
            return;
        }

        MyPet myPet = myPetBukkit.getMyPet();
        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            return;
        }

        double dodgeChance = petData.personality().getCustomEffect("dodgeChance", 0.0);
        if (dodgeChance > 0.0 && ThreadLocalRandom.current().nextDouble() < dodgeChance) {
            event.setCancelled(true);

            // Visual dodge feedback
            victim.getWorld().spawnParticle(Particle.CLOUD,
                    victim.getLocation().add(0, 1, 0), 5, 0.3, 0.3, 0.3, 0.05);
            victim.getWorld().playSound(victim.getLocation(),
                    Sound.ENTITY_PHANTOM_FLAP, 0.8f, 1.5f);
        }
    }

    /**
     * Handles crit chance: when a MyPet entity attacks, if the pet's personality
     * has a critChance effect, roll against it and multiply damage on success.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPetAttack(@NotNull EntityDamageByEntityEvent event) {
        Entity attacker = event.getDamager();
        if (!(attacker instanceof MyPetBukkitEntity myPetBukkit)) {
            return;
        }

        MyPet myPet = myPetBukkit.getMyPet();
        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            return;
        }

        double critChance = petData.personality().getCustomEffect("critChance", 0.0);
        if (critChance > 0.0 && ThreadLocalRandom.current().nextDouble() < critChance) {
            double critMultiplier = 1.5;
            event.setDamage(event.getDamage() * critMultiplier);

            // Visual crit feedback
            attacker.getWorld().spawnParticle(Particle.CRIT,
                    event.getEntity().getLocation().add(0, 1, 0), 10, 0.4, 0.4, 0.4, 0.1);
            attacker.getWorld().playSound(attacker.getLocation(),
                    Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.2f);
        }
    }

    /**
     * Handles loot bonus chance: when a mob is killed by a player with an active MyPet
     * whose personality has lootBonusChance, roll to duplicate one random drop.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMobDeathLootBonus(@NotNull EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }

        MyPet myPet = getActiveMyPet(killer);
        if (myPet == null) {
            return;
        }

        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            return;
        }

        double lootBonusChance = petData.personality().getCustomEffect("lootBonusChance", 0.0);
        if (lootBonusChance > 0.0 && ThreadLocalRandom.current().nextDouble() < lootBonusChance) {
            List<ItemStack> drops = event.getDrops();
            if (!drops.isEmpty()) {
                // Duplicate one random drop
                ItemStack bonusDrop = drops.get(ThreadLocalRandom.current().nextInt(drops.size()));
                drops.add(bonusDrop.clone());
            }
        }
    }

    /**
     * Gets the active MyPet for a player, or null if none.
     */
    @Nullable
    private MyPet getActiveMyPet(@NotNull Player player) {
        try {
            var pm = MyPetApi.getPlayerManager();
            if (!pm.isMyPetPlayer(player)) {
                return null;
            }
            MyPetPlayer myPetPlayer = pm.getMyPetPlayer(player);
            return myPetPlayer.hasMyPet() ? myPetPlayer.getMyPet() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ─── Internal ────────────────────────────────────────────────

    /**
     * Applies a single stat modifier to a MyPet entity via UpgradeComputer.
     * MyPet's internal getMaxHealth() reads from LifeImpl.getLife().getValue(),
     * NOT from Bukkit entity attributes. So UpgradeComputer is the only way
     * to affect the displayed HP, Damage, etc.
     */
    private void applyStatModifier(@NotNull MyPet myPet,
                                   @NotNull String statName,
                                   double value,
                                   @NotNull List<Runnable> unloaders) {
        applyViaUpgradeComputer(myPet, statName, value, unloaders);
    }

    /**
     * Applies stat via MyPet's skill UpgradeComputer using reflection.
     * <p>
     * IMPORTANT: UpgradeComputer uses Number (base value is Integer(0)),
     * so the modifier must use Number type to avoid ClassCastException
     * when the bridge method tries to cast Integer to Double.
     * After applying, syncs entity health to the new max value.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void applyViaUpgradeComputer(@NotNull MyPet myPet,
                                          @NotNull String statName,
                                          double value,
                                          @NotNull List<Runnable> unloaders) {
        var skills = myPet.getSkills();
        var skill = skills.get(statName);
        if (skill == null) {
            logger.log(Level.FINE, "[StatsManager] Skill ''{0}'' not found on pet {1}",
                    new Object[]{statName, myPet.getUUID()});
            return;
        }

        // Use raw UpgradeModifier to avoid generic type mismatch.
        // UpgradeComputer<Number> base value is Integer(0), so modify()
        // receives Number (Integer). We must handle this without casting to Double.
        UpgradeModifier modifier = new UpgradeModifier() {
            @Override
            public Object getValue() {
                return value;
            }

            @Override
            public Object modify(Object current) {
                return ((Number) current).doubleValue() + value;
            }
        };

        // Try multiple possible getter names (MyPet internal naming varies)
        String[] getterCandidates = getUpgradeComputerGetters(statName);

        for (String getterName : getterCandidates) {
            try {
                var getterMethod = skill.getClass().getMethod(getterName);
                Object upgradeComputer = getterMethod.invoke(skill);

                var addUpgradeMethod = upgradeComputer.getClass().getMethod("addUpgrade", UpgradeModifier.class);
                addUpgradeMethod.invoke(upgradeComputer, modifier);

                logger.log(Level.FINE,
                        "[StatsManager] Applied {0} +{1} via UpgradeComputer on pet {2}",
                        new Object[]{statName, value, myPet.getUUID()});

                // Sync entity health after modifying Life
                if ("Life".equals(statName)) {
                    double newMax = myPet.getMaxHealth();
                    myPet.setHealth(newMax);
                    logger.log(Level.FINE,
                            "[StatsManager] Pet {0} new maxHealth={1}",
                            new Object[]{myPet.getUUID(), newMax});
                }

                unloaders.add(() -> {
                    try {
                        var removeMethod = upgradeComputer.getClass().getMethod("removeUpgrade", UpgradeModifier.class);
                        removeMethod.invoke(upgradeComputer, modifier);
                    } catch (Exception e) {
                        // Silently ignore removal failures
                    }
                });
                return; // Success, no need to try other getters
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "[StatsManager] Failed getter ''{0}'' for skill ''{1}'': {2}",
                        new Object[]{getterName, statName, e.getMessage()});
            }
        }

        logger.log(Level.WARNING,
                "[StatsManager] Could not apply UpgradeComputer modifier for skill ''{0}''", statName);
    }

    @NotNull
    private String[] getUpgradeComputerGetters(@NotNull String statName) {
        return switch (statName) {
            case "Life" -> new String[]{"getLife", "getHealth"};
            case "Damage" -> new String[]{"getDamage"};
            case "Speed" -> new String[]{"getSpeed"};
            default -> new String[]{"get" + statName};
        };
    }

    // Scale and personality attribute effects are now applied in onCreatureSpawn / applyEntityAttributes
    // instead of here, because MyPet recreates entity instances on teleport/chunk reload.

    // ─── Exp Snapshot Persistence ────────────────────────────────

    private static final String SNAPSHOT_FILE = "exp-snapshots.yml";

    /**
     * Saves all exp snapshots to a file so they survive server restarts.
     * Called during plugin disable. Keeps a backup file for crash recovery.
     */
    public void saveExpSnapshots() {
        // Also snapshot all currently active pets (in case onMyPetRemove hasn't fired yet)
        for (MyPet myPet : MyPetApi.getMyPetManager().getAllActiveMyPets()) {
            try {
                double currentExp = myPet.getExperience().getExp();
                int currentLevel = myPet.getExperience().getLevel();
                // Only save if this is higher than existing snapshot (protect against
                // saving an already-reduced exp value during shutdown race conditions)
                double[] existing = expSnapshots.get(myPet.getUUID());
                if (existing == null
                        || currentLevel > (int) existing[1]
                        || (currentLevel == (int) existing[1] && currentExp > existing[0])) {
                    expSnapshots.put(myPet.getUUID(), new double[]{currentExp, currentLevel});
                }
            } catch (Exception ignored) {}
        }

        if (expSnapshots.isEmpty()) {
            return;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, double[]> entry : expSnapshots.entrySet()) {
            String key = entry.getKey().toString();
            yaml.set(key + ".exp", entry.getValue()[0]);
            yaml.set(key + ".level", (int) entry.getValue()[1]);
        }

        try {
            File file = new File(plugin.getDataFolder(), SNAPSHOT_FILE);
            yaml.save(file);
            // Also save a backup for crash recovery
            File backup = new File(plugin.getDataFolder(), SNAPSHOT_FILE + ".bak");
            yaml.save(backup);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to save exp snapshots", e);
        }
    }

    /**
     * Loads exp snapshots from file. Called during plugin enable.
     * Tries the primary file first, then falls back to backup.
     * Does NOT delete files — they serve as crash recovery until next save overwrites them.
     */
    public void loadExpSnapshots() {
        File file = new File(plugin.getDataFolder(), SNAPSHOT_FILE);
        File backup = new File(plugin.getDataFolder(), SNAPSHOT_FILE + ".bak");

        // Try primary file, fall back to backup
        File source = file.exists() ? file : (backup.exists() ? backup : null);
        if (source == null) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(source);
        for (String key : yaml.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                double exp = yaml.getDouble(key + ".exp");
                int level = yaml.getInt(key + ".level");
                expSnapshots.put(uuid, new double[]{exp, level});
            } catch (Exception e) {
                logger.log(Level.FINE, "Invalid exp snapshot entry: " + key, e);
            }
        }

        logger.log(Level.INFO, "[ExpProtect] Loaded {0} exp snapshots from {1}",
                new Object[]{expSnapshots.size(), source.getName()});
    }
}
