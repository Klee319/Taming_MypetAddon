package com.mypetaddon.skill;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.rarity.Rarity;
import com.mypetaddon.util.SoundUtil;
import de.Keyle.MyPet.MyPetApi;
import de.Keyle.MyPet.api.entity.MyPet;
import de.Keyle.MyPet.api.entity.MyPetBukkitEntity;
import de.Keyle.MyPet.api.player.MyPetPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages and executes pet-specific skills.
 * Skills are configured per mob type in config under "pet-skills.&lt;mobType&gt;.&lt;skillId&gt;".
 */
public final class PetSkillManager {

    private final MyPetAddonPlugin plugin;
    private final ConfigManager configManager;
    private final PetDataCache petDataCache;
    private final CooldownTracker cooldownTracker;
    private final Logger logger;

    public PetSkillManager(@NotNull MyPetAddonPlugin plugin,
                           @NotNull ConfigManager configManager,
                           @NotNull PetDataCache petDataCache,
                           @NotNull CooldownTracker cooldownTracker) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.petDataCache = petDataCache;
        this.cooldownTracker = cooldownTracker;
        this.logger = plugin.getLogger();
    }

    /**
     * Executes a skill for the player's active pet.
     *
     * @param player  the pet owner
     * @param skillId the skill to execute
     * @return true if the skill was executed successfully
     */
    public boolean executeSkill(@NotNull Player player, @NotNull String skillId) {
        // 1. Get player's active MyPet
        MyPet myPet = getActiveMyPet(player);
        if (myPet == null) {
            player.sendMessage("§cYou don't have an active pet!");
            return false;
        }

        // 2. Get PetData from cache
        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            player.sendMessage("§cYour pet has no addon data!");
            return false;
        }

        // 3. Check rarity has skill slots
        Rarity rarity = petData.rarity();
        if (rarity.getSkillSlots() <= 0) {
            player.sendMessage("§cYour pet's rarity (" + rarity.getColoredName()
                    + "§c) does not support skills!");
            return false;
        }

        // 4. Check mob type has this skill in config
        String mobType = petData.mobType();
        ConfigurationSection skillConfig = getSkillConfig(mobType, skillId);
        if (skillConfig == null) {
            player.sendMessage("§cSkill '" + skillId + "' is not available for " + mobType + "!");
            return false;
        }

        // 5. Check required rarity
        Rarity requiredRarity = parseRequiredRarity(skillConfig);
        if (requiredRarity != null && rarity.ordinal() < requiredRarity.ordinal()) {
            player.sendMessage("§cThis skill requires " + requiredRarity.getColoredName()
                    + "§c rarity or higher!");
            return false;
        }

        // 6. Check cooldown
        int cooldownSeconds = skillConfig.getInt("cooldown", 30);
        if (cooldownTracker.isOnCooldown(petData.addonPetId(), skillId, cooldownSeconds)) {
            int remaining = cooldownTracker.getRemainingCooldown(
                    petData.addonPetId(), skillId, cooldownSeconds);
            player.sendMessage("§cSkill on cooldown! " + remaining + "s remaining.");
            return false;
        }

        // 7. Execute the skill effect
        boolean success = executeSkillEffect(skillId, myPet, player, skillConfig);
        if (!success) {
            player.sendMessage("§cFailed to execute skill '" + skillId + "'!");
            return false;
        }

        // 8. Set cooldown
        cooldownTracker.setCooldown(petData.addonPetId(), skillId);

        // 9. Play skill sound
        String soundKey = "skill-" + skillId;
        SoundUtil.playSound(player, soundKey, configManager);

        // 10. Send usage message
        String displayName = skillConfig.getString("display-name", skillId);
        player.sendMessage("§a" + myPet.getPetName() + " used §e" + displayName + "§a!");

        return true;
    }

    /**
     * Returns available skills for a pet based on its mob type and rarity.
     *
     * @param petData the pet's data
     * @return list of available skill info, or empty list if none
     */
    @NotNull
    public List<SkillInfo> getAvailableSkills(@NotNull PetData petData) {
        String mobType = petData.mobType();
        ConfigurationSection mobSkills = configManager.getConfig()
                .getConfigurationSection("pet-skills." + mobType);

        if (mobSkills == null) {
            return Collections.emptyList();
        }

        List<SkillInfo> skills = new ArrayList<>();
        Rarity petRarity = petData.rarity();

        for (String skillId : mobSkills.getKeys(false)) {
            ConfigurationSection skillSection = mobSkills.getConfigurationSection(skillId);
            if (skillSection == null) {
                continue;
            }

            String displayName = skillSection.getString("display-name", skillId);
            String description = skillSection.getString("description", "");
            int cooldown = skillSection.getInt("cooldown", 30);
            Rarity requiredRarity = parseRequiredRarity(skillSection);

            boolean isAvailable = requiredRarity == null
                    || petRarity.ordinal() >= requiredRarity.ordinal();

            skills.add(new SkillInfo(
                    skillId, displayName, description, cooldown,
                    requiredRarity != null ? requiredRarity : Rarity.COMMON,
                    isAvailable
            ));
        }

        return Collections.unmodifiableList(skills);
    }

    // ─── Skill Effect Implementations ───────────────────────────

    /**
     * Dispatches to the correct skill effect handler by skillId.
     */
    private boolean executeSkillEffect(@NotNull String skillId, @NotNull MyPet myPet,
                                       @NotNull Player owner,
                                       @NotNull ConfigurationSection skillConfig) {
        return switch (skillId) {
            case "self-destruct" -> selfDestruct(myPet, owner, skillConfig);
            case "howl" -> howl(myPet, owner, skillConfig);
            case "teleport-sync" -> teleportSync(myPet, owner, skillConfig);
            case "fire-rain" -> fireRain(myPet, owner, skillConfig);
            default -> {
                logger.warning("[Skill] Unknown skill effect: " + skillId);
                yield false;
            }
        };
    }

    /**
     * Self-destruct: Explodes the pet, dealing area damage at the cost of self-HP.
     */
    private boolean selfDestruct(@NotNull MyPet myPet, @NotNull Player owner,
                                 @NotNull ConfigurationSection skillConfig) {
        Location petLocation = myPet.getLocation().orElse(owner.getLocation());
        double radius = skillConfig.getDouble("radius", 5.0);
        double damage = skillConfig.getDouble("damage", 10.0);
        double selfDamagePercent = skillConfig.getDouble("self-damage-percent", 0.3);

        // Spawn explosion particle
        if (petLocation.getWorld() != null) {
            petLocation.getWorld().spawnParticle(Particle.EXPLOSION, petLocation, 3,
                    0.5, 0.5, 0.5, 0.0);
        }

        // Damage nearby entities (excluding owner)
        for (Entity entity : petLocation.getWorld().getNearbyEntities(
                petLocation, radius, radius, radius)) {
            if (entity.equals(owner)) {
                continue;
            }
            if (entity instanceof MyPetBukkitEntity) {
                continue;
            }
            if (entity instanceof LivingEntity target) {
                target.damage(damage, owner);
            }
        }

        // Apply self-damage to pet
        MyPetBukkitEntity petEntity = myPet.getEntity().orElse(null);
        if (petEntity != null) {
            double maxHp = myPet.getMaxHealth();
            double selfDamage = maxHp * selfDamagePercent;
            petEntity.damage(selfDamage);
        }

        // Play explosion sound
        SoundUtil.playSound(petLocation, "skill-self-destruct", configManager);
        return true;
    }

    /**
     * Howl: Applies a debuff (e.g. SLOW) to hostile entities in range.
     */
    private boolean howl(@NotNull MyPet myPet, @NotNull Player owner,
                         @NotNull ConfigurationSection skillConfig) {
        Location petLocation = myPet.getLocation().orElse(owner.getLocation());
        double radius = skillConfig.getDouble("radius", 8.0);
        String effectName = skillConfig.getString("effect", "SLOW");
        int durationTicks = skillConfig.getInt("duration", 100);
        int amplifier = skillConfig.getInt("amplifier", 1);

        PotionEffectType effectType = PotionEffectType.getByKey(
                org.bukkit.NamespacedKey.minecraft(effectName.toLowerCase()));
        if (effectType == null) {
            logger.warning("[Skill] Unknown potion effect: " + effectName);
            return false;
        }

        // Apply effect to hostile entities within radius
        if (petLocation.getWorld() != null) {
            for (Entity entity : petLocation.getWorld().getNearbyEntities(
                    petLocation, radius, radius, radius)) {
                if (entity instanceof Monster monster) {
                    monster.addPotionEffect(new PotionEffect(
                            effectType, durationTicks, amplifier, false, true));
                }
            }

            // Spawn note particles
            petLocation.getWorld().spawnParticle(Particle.NOTE, petLocation.add(0, 1, 0),
                    8, 0.5, 0.3, 0.5, 0.0);
        }

        // Play wolf howl sound
        SoundUtil.playSound(petLocation, "skill-howl", configManager);
        return true;
    }

    /**
     * Teleport-sync: Teleports the pet to the owner's location if within range.
     */
    private boolean teleportSync(@NotNull MyPet myPet, @NotNull Player owner,
                                 @NotNull ConfigurationSection skillConfig) {
        double range = skillConfig.getDouble("range", 50.0);

        Location petLocation = myPet.getLocation().orElse(null);
        Location ownerLocation = owner.getLocation();

        if (petLocation == null) {
            return false;
        }

        // Check if pet and owner are in the same world
        if (!petLocation.getWorld().equals(ownerLocation.getWorld())) {
            owner.sendMessage("§cYour pet is in a different world!");
            return false;
        }

        // Check distance
        double distance = petLocation.distance(ownerLocation);
        if (distance > range) {
            owner.sendMessage("§cYour pet is too far away! (Max range: " + (int) range + " blocks)");
            return false;
        }

        // Spawn ender particles at old location
        if (petLocation.getWorld() != null) {
            petLocation.getWorld().spawnParticle(Particle.PORTAL, petLocation,
                    30, 0.5, 1.0, 0.5, 0.0);
        }

        // Teleport pet to owner
        MyPetBukkitEntity petEntity = myPet.getEntity().orElse(null);
        if (petEntity != null) {
            petEntity.teleport(ownerLocation);
        }

        // Spawn ender particles at new location
        if (ownerLocation.getWorld() != null) {
            ownerLocation.getWorld().spawnParticle(Particle.PORTAL, ownerLocation,
                    30, 0.5, 1.0, 0.5, 0.0);
        }

        // Play enderman teleport sound
        SoundUtil.playSound(ownerLocation, "skill-teleport-sync", configManager);
        return true;
    }

    /**
     * Fire-rain: Rains fire down on an area, damaging and igniting enemies.
     */
    private boolean fireRain(@NotNull MyPet myPet, @NotNull Player owner,
                             @NotNull ConfigurationSection skillConfig) {
        Location petLocation = myPet.getLocation().orElse(owner.getLocation());
        double radius = skillConfig.getDouble("radius", 6.0);
        double damage = skillConfig.getDouble("damage", 6.0);
        int fireDurationTicks = skillConfig.getInt("fire-duration", 60);

        if (petLocation.getWorld() == null) {
            return false;
        }

        // Immediate damage and ignite
        for (Entity entity : petLocation.getWorld().getNearbyEntities(
                petLocation, radius, radius, radius)) {
            if (entity.equals(owner)) {
                continue;
            }
            if (entity instanceof MyPetBukkitEntity) {
                continue;
            }
            if (entity instanceof LivingEntity target) {
                target.damage(damage, owner);
                target.setFireTicks(fireDurationTicks);
            }
        }

        // Schedule particle effects over the fire duration
        int particleIntervals = Math.max(1, fireDurationTicks / 10);
        for (int i = 0; i < particleIntervals; i++) {
            long delay = i * 10L;
            Location effectLocation = petLocation.clone();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (effectLocation.getWorld() != null) {
                    effectLocation.getWorld().spawnParticle(Particle.LAVA, effectLocation,
                            15, radius * 0.5, 2.0, radius * 0.5, 0.0);
                    effectLocation.getWorld().spawnParticle(Particle.FLAME,
                            effectLocation.clone().add(0, 3, 0),
                            20, radius * 0.5, 0.5, radius * 0.5, 0.02);
                }
            }, delay);
        }

        // Play fire sound
        SoundUtil.playSound(petLocation, "skill-fire-rain", configManager);
        return true;
    }

    // ─── Internal Helpers ───────────────────────────────────────

    /**
     * Gets the config section for a specific skill of a mob type.
     */
    @Nullable
    private ConfigurationSection getSkillConfig(@NotNull String mobType, @NotNull String skillId) {
        return configManager.getConfig()
                .getConfigurationSection("pet-skills." + mobType + "." + skillId);
    }

    /**
     * Parses the required rarity from a skill config section.
     */
    @Nullable
    private Rarity parseRequiredRarity(@NotNull ConfigurationSection skillConfig) {
        String rarityName = skillConfig.getString("required-rarity");
        if (rarityName == null || rarityName.isEmpty()) {
            return null;
        }
        try {
            return Rarity.fromString(rarityName);
        } catch (IllegalArgumentException e) {
            logger.warning("[Skill] Unknown required-rarity: " + rarityName);
            return null;
        }
    }

    /**
     * Gets the active MyPet for a player.
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
            logger.warning("[Skill] Failed to get active MyPet for " + player.getName()
                    + ": " + e.getMessage());
            return null;
        }
    }

    // ─── Inner Records ──────────────────────────────────────────

    /**
     * Immutable snapshot of a skill's information for display purposes.
     */
    public record SkillInfo(
            @NotNull String skillId,
            @NotNull String displayName,
            @NotNull String description,
            int cooldown,
            @NotNull Rarity requiredRarity,
            boolean isAvailable
    ) {}
}
