package com.mypetaddon.stats;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.PetStats;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.personality.Personality;
import de.Keyle.MyPet.MyPetApi;
import de.Keyle.MyPet.api.entity.MyPet;
import de.Keyle.MyPet.api.entity.StoredMyPet;
import de.Keyle.MyPet.api.event.MyPetActivatedEvent;
import de.Keyle.MyPet.api.event.MyPetRemoveEvent;
import de.Keyle.MyPet.api.event.MyPetSelectSkilltreeEvent;
import de.Keyle.MyPet.api.skill.modifier.UpgradeModifier;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    /** MyPet UUID -> list of unload functions for applied modifiers. */
    private final Map<UUID, List<Runnable>> activeModifiers = new ConcurrentHashMap<>();

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
        java.util.Set<String> allStatNames = new java.util.LinkedHashSet<>(petStats.baseValues().keySet());
        allStatNames.addAll(petStats.upgradedValues().keySet());
        for (String statName : allStatNames) {
            double finalValue = modifierPipeline.calculate(statName, petData, petStats);

            try {
                applyStatModifier(myPet, statName, finalValue, unloaders);
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "Failed to apply stat '" + statName + "' to MyPet " + myPet.getUUID(), e);
            }
        }

        // Apply personality custom effects
        applyPersonalityEffects(myPet, petData.personality(), unloaders);
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
     */
    public void applyAllActivePets() {
        for (MyPet myPet : MyPetApi.getMyPetManager().getAllActiveMyPets()) {
            try {
                applyStats(myPet);
            } catch (Exception e) {
                logger.log(Level.SEVERE,
                        "Failed to apply stats to active MyPet " + myPet.getUUID(), e);
            }
        }
    }

    /**
     * Unloads all active modifiers. Called during plugin disable.
     */
    public void unloadAll() {
        for (Map.Entry<UUID, List<Runnable>> entry : activeModifiers.entrySet()) {
            for (Runnable unloader : entry.getValue()) {
                try {
                    unloader.run();
                } catch (Exception e) {
                    logger.log(Level.FINE, "Error unloading modifier on shutdown", e);
                }
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

            // Weighted random: bias toward lower values (similar to legacy randomRange2)
            double roll = random.nextDouble();
            double biasedRoll = roll * Math.min(1.0, roll * 2.0 + roll * roll);
            double value = Math.floor(min + (max - min) * biasedRoll);
            baseValues.put(statName, value);
        }

        return new PetStats(petData.addonPetId(), Map.copyOf(baseValues), Map.of());
    }

    // ─── MyPet Event Listeners ──────────────────────────────────

    @EventHandler
    public void onMyPetActivated(@NotNull MyPetActivatedEvent event) {
        applyStats(event.getMyPet());
    }

    @EventHandler
    public void onMyPetRemove(@NotNull MyPetRemoveEvent event) {
        StoredMyPet storedMyPet = event.getMyPet();
        if (storedMyPet instanceof MyPet myPet) {
            unloadStats(myPet);
        }
        activeModifiers.remove(storedMyPet.getUUID());
    }

    @EventHandler
    public void onMyPetSelectSkilltree(@NotNull MyPetSelectSkilltreeEvent event) {
        // Reapply stats after skilltree change (modifiers may have been reset)
        StoredMyPet storedMyPet = event.getMyPet();
        if (storedMyPet instanceof MyPet myPet) {
            applyStats(myPet);
        }
    }

    // ─── Internal ────────────────────────────────────────────────

    /**
     * Applies a single stat modifier to a MyPet's skill upgrade computer.
     * Follows the legacy pattern of creating a custom UpgradeModifier.
     */
    private void applyStatModifier(@NotNull MyPet myPet,
                                   @NotNull String statName,
                                   double value,
                                   @NotNull List<Runnable> unloaders) {
        var skills = myPet.getSkills();
        var skill = skills.get(statName);
        if (skill == null) {
            return;
        }

        // Create UpgradeModifier that adds the calculated value
        UpgradeModifier<Double> modifier = new UpgradeModifier<>() {
            @Override
            public Double getValue() {
                return 0.0;
            }

            @Override
            public Double modify(Double current) {
                return current + value;
            }
        };

        // Get the upgrade computer via reflection (same pattern as legacy)
        try {
            var getterMethod = skill.getClass().getMethod("get" + statName);
            Object upgradeComputer = getterMethod.invoke(skill);

            var addUpgradeMethod = upgradeComputer.getClass().getMethod("addUpgrade", UpgradeModifier.class);
            addUpgradeMethod.invoke(upgradeComputer, modifier);

            // Store unloader
            unloaders.add(() -> {
                try {
                    var removeMethod = upgradeComputer.getClass().getMethod("removeUpgrade", UpgradeModifier.class);
                    removeMethod.invoke(upgradeComputer, modifier);
                } catch (Exception e) {
                    // Silently ignore removal failures
                }
            });
        } catch (Exception e) {
            logger.log(Level.FINE,
                    "Could not apply modifier for skill '" + statName + "': " + e.getMessage());
        }
    }

    /**
     * Applies personality-specific custom effects to a MyPet.
     * For example, AGILE personality applies a speed boost via Attribute.
     */
    private void applyPersonalityEffects(@NotNull MyPet myPet,
                                         @NotNull Personality personality,
                                         @NotNull List<Runnable> unloaders) {
        Map<String, Double> effects = personality.getCustomEffects();
        if (effects.isEmpty()) {
            return;
        }

        // Apply speed modifier if present
        Double speedMod = effects.get("speed");
        if (speedMod != null) {
            myPet.getEntity().ifPresent(bukkitEntity -> {
                if (bukkitEntity instanceof LivingEntity livingEntity) {
                    AttributeInstance speedAttr = livingEntity.getAttribute(Attribute.MOVEMENT_SPEED);
                    if (speedAttr != null) {
                        AttributeModifier attrMod = new AttributeModifier(
                                NamespacedKey.fromString("mypetaddon:personality_speed"),
                                speedMod - 1.0,
                                AttributeModifier.Operation.ADD_SCALAR);
                        speedAttr.addModifier(attrMod);

                        unloaders.add(() -> {
                            try {
                                speedAttr.removeModifier(attrMod);
                            } catch (Exception e) {
                                // Entity may no longer exist
                            }
                        });
                    }
                }
            });
        }

        // Apply knockback resistance if present (STURDY personality)
        Double knockbackResist = effects.get("knockbackResist");
        if (knockbackResist != null) {
            myPet.getEntity().ifPresent(bukkitEntity -> {
                if (bukkitEntity instanceof LivingEntity livingEntity) {
                    AttributeInstance kbAttr = livingEntity.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
                    if (kbAttr != null) {
                        AttributeModifier attrMod = new AttributeModifier(
                                NamespacedKey.fromString("mypetaddon:personality_knockback"),
                                knockbackResist - 1.0,
                                AttributeModifier.Operation.ADD_SCALAR);
                        kbAttr.addModifier(attrMod);

                        unloaders.add(() -> {
                            try {
                                kbAttr.removeModifier(attrMod);
                            } catch (Exception e) {
                                // Entity may no longer exist
                            }
                        });
                    }
                }
            });
        }
    }
}
