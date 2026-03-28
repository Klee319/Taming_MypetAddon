package com.mypetaddon.taming;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.config.ConfigManager.TamingItemEntry;
import com.mypetaddon.data.EncyclopediaRepository;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.PetStats;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.integration.MythicMobsIntegration;
import com.mypetaddon.personality.Personality;
import com.mypetaddon.personality.PersonalityManager;
import com.mypetaddon.rarity.Rarity;
import com.mypetaddon.rarity.RarityManager;
import com.mypetaddon.skilltree.SkilltreeAssigner;
import com.mypetaddon.stats.StatsManager;
import com.mypetaddon.util.ItemMatcher;
import de.Keyle.MyPet.MyPetApi;
import de.Keyle.MyPet.api.WorldGroup;
import de.Keyle.MyPet.api.entity.MyPetType;
import de.Keyle.MyPet.api.player.MyPetPlayer;
import de.Keyle.MyPet.api.repository.RepositoryCallback;
import de.Keyle.MyPet.api.util.service.types.EntityConverterService;
import de.Keyle.MyPet.entity.InactiveMyPet;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the taming process for both animals and monsters.
 * Ported from legacy mypet_legacy.js with improvements including
 * rarity/personality assignment, bond initialization, and encyclopedia recording.
 */
public final class TamingManager {

    private final MyPetAddonPlugin plugin;
    private final ConfigManager configManager;
    private final RarityManager rarityManager;
    private final PersonalityManager personalityManager;
    private final PetDataCache petDataCache;
    private final EncyclopediaRepository encyclopediaRepository;
    private final MythicMobsIntegration mythicMobsIntegration;
    private final StatsManager statsManager;
    private final SkilltreeAssigner skilltreeAssigner;
    private final Logger logger;

    /** Player UUID -> pending monster tame target. */
    private final Map<UUID, PendingTame> pendingMonsterTames = new ConcurrentHashMap<>();

    /** Player UUID -> pending name assignment for a tamed monster. */
    private final Map<UUID, PendingName> pendingNames = new ConcurrentHashMap<>();

    /** Entity UUID -> taming attempt count (for max-attempts-per-mob limit). */
    private final Map<UUID, Integer> tamingAttempts = new ConcurrentHashMap<>();

    /**
     * Represents a monster that a player is attempting to tame (awaiting kill).
     */
    public record PendingTame(@NotNull LivingEntity entity, long timestamp, @Nullable ItemStack consumedItem) {}

    /**
     * Represents a tamed monster awaiting a name from the player.
     * Stores the entityType and visual info from the original entity so we can create
     * the MyPet correctly even after the original entity is dead.
     */
    public record PendingName(
            @NotNull Entity dummyEntity,
            @NotNull EntityType entityType,
            @NotNull String addonMobType,
            @NotNull Rarity rarity,
            @NotNull Personality personality,
            long timestamp,
            @Nullable Object entityInfo,
            @Nullable ItemStack consumedItem,
            double capturedScale
    ) {}

    public TamingManager(@NotNull MyPetAddonPlugin plugin,
                         @NotNull ConfigManager configManager,
                         @NotNull RarityManager rarityManager,
                         @NotNull PersonalityManager personalityManager,
                         @NotNull PetDataCache petDataCache,
                         @NotNull EncyclopediaRepository encyclopediaRepository,
                         @NotNull MythicMobsIntegration mythicMobsIntegration,
                         @NotNull StatsManager statsManager,
                         @NotNull SkilltreeAssigner skilltreeAssigner) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.rarityManager = rarityManager;
        this.personalityManager = personalityManager;
        this.petDataCache = petDataCache;
        this.encyclopediaRepository = encyclopediaRepository;
        this.mythicMobsIntegration = mythicMobsIntegration;
        this.statsManager = statsManager;
        this.skilltreeAssigner = skilltreeAssigner;
        this.logger = plugin.getLogger();

        // Schedule periodic cleanup of stale pending tames (every 30 seconds)
        long cleanupIntervalTicks = 30L * 20L; // 30 seconds
        Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupStalePendingTames,
                cleanupIntervalTicks, cleanupIntervalTicks);
    }

    /**
     * Handles a player right-clicking an animal with the taming item.
     * If conditions are met, the animal is consumed and a MyPet is created immediately.
     *
     * @param player   the interacting player
     * @param entity   the target animal
     * @param mainHand the item in the player's main hand
     * @return true if the interaction was handled (event should be cancelled)
     */
    public boolean handleAnimalInteract(@NotNull Player player,
                                        @NotNull LivingEntity entity,
                                        @NotNull ItemStack mainHand) {
        // Check entity is an animal
        if (!isAnimal(entity.getType())) {
            return false;
        }

        // Skip our own dummy entities (AI disabled + invulnerable)
        if (!entity.hasAI() && entity.isInvulnerable()) {
            return false;
        }

        // Only one pending name per player
        if (pendingNames.containsKey(player.getUniqueId())) {
            return false;
        }

        // Block if another player is already taming this entity
        if (isEntityClaimedByOther(entity, player.getUniqueId())) {
            player.sendMessage("§c他のプレイヤーがこのモブをテイム中です。");
            return true;
        }

        // Check max attempts per mob
        int maxAttempts = configManager.getMaxAttemptsPerMob();
        if (maxAttempts > 0) {
            int attempts = tamingAttempts.getOrDefault(entity.getUniqueId(), 0);
            if (attempts >= maxAttempts) {
                player.sendMessage("§cこのモブにはこれ以上テイムを試行できません。（" + maxAttempts + "/" + maxAttempts + "回）");
                return true;
            }
        }

        // Find matching taming item from config list
        List<TamingItemEntry> items = configManager.getTamingAnimalItems();
        TamingItemEntry matched = findMatchingItem(mainHand, items);
        if (matched == null) {
            return false;
        }

        // Save a copy of the consumed item before consuming (for refund on failure)
        ItemStack consumedItemCopy = null;
        if (matched.consume()) {
            consumedItemCopy = mainHand.asOne();
            mainHand.setAmount(mainHand.getAmount() - 1);
            // Force inventory update next tick to prevent other plugins from reverting item consumption
            Bukkit.getScheduler().runTask(plugin, () -> player.updateInventory());
        }

        // Increment attempt counter
        if (maxAttempts > 0) {
            tamingAttempts.merge(entity.getUniqueId(), 1, Integer::sum);
        }

        // Check success rate
        if (matched.successRate() < 1.0
                && ThreadLocalRandom.current().nextDouble() >= matched.successRate()) {
            int current = tamingAttempts.getOrDefault(entity.getUniqueId(), 0);
            int pct = (int) (matched.successRate() * 100);
            String attemptMsg = maxAttempts > 0 ? " §7(" + current + "/" + maxAttempts + "回)" : "";
            player.sendMessage("§cテイミングに失敗しました... (成功率: §f" + pct + "%§c)" + attemptMsg);
            return true;
        }

        // Taming succeeded — clear attempt counter
        tamingAttempts.remove(entity.getUniqueId());

        // Visual/audio feedback
        entity.getWorld().spawnParticle(Particle.HEART, entity.getLocation(),
                15, 1.0, 1.0, 1.0, 0.3);
        entity.getWorld().playSound(entity.getLocation(),
                Sound.ENTITY_PLAYER_LEVELUP, 2.0f, 1.0f);

        // Roll rarity and personality
        Rarity rarity = rarityManager.rollRarity(entity, player);
        Personality personality = personalityManager.rollPersonality();

        // Capture entity visual info and scale before disabling AI
        Object entityInfo = captureEntityInfo(entity);
        double capturedScale = captureScale(entity);

        // Make the entity a "dummy" (stop moving, invulnerable) while waiting for name
        entity.setAI(false);
        entity.setInvulnerable(true);

        // Store in pending names for /pettame command (include consumed item for refund)
        String addonMobType = resolveAddonMobType(entity);
        pendingNames.put(player.getUniqueId(), new PendingName(
                entity, entity.getType(), addonMobType, rarity, personality,
                System.currentTimeMillis(), entityInfo, consumedItemCopy, capturedScale));

        // Notify player
        player.sendMessage("§a仲間にできそうだ！ "
                + rarity.getColoredName() + " §7[" + personality.getDisplayName() + "]");
        player.sendMessage("§a/pettame <名前> で仲間にします（"
                + (configManager.getTamingTimeout() / 1000L) + "秒後キャンセルされます）");

        // Schedule timeout
        long timeoutTicks = (configManager.getTamingTimeout() / 1000L) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingName stillPending = pendingNames.get(player.getUniqueId());
            if (stillPending != null && stillPending.dummyEntity().equals(entity)) {
                // Restore the entity to normal state
                entity.setAI(true);
                entity.setInvulnerable(false);
                pendingNames.remove(player.getUniqueId());
                if (player.isOnline()) {
                    player.sendMessage("§c名前をつける時間が過ぎました。仲間にできませんでした。");
                }
            }
        }, timeoutTicks);

        return true;
    }

    /**
     * Handles a player right-clicking a monster with the taming item.
     * Registers the monster as a pending tame target (player must kill it).
     *
     * @param player   the interacting player
     * @param entity   the target monster
     * @param mainHand the item in the player's main hand
     * @return true if the interaction was handled (event should be cancelled)
     */
    public boolean handleMonsterInteract(@NotNull Player player,
                                         @NotNull LivingEntity entity,
                                         @NotNull ItemStack mainHand) {
        // Check entity is a monster/hostile
        if (!isMonster(entity.getType())) {
            return false;
        }

        // Only one pending tame per player
        if (pendingMonsterTames.containsKey(player.getUniqueId())) {
            return false;
        }

        // Skip our own dummy entities (AI disabled + invulnerable)
        if (!entity.hasAI() && entity.isInvulnerable()) {
            return false;
        }

        // Block if another player is already taming this entity
        if (isEntityClaimedByOther(entity, player.getUniqueId())) {
            player.sendMessage("§c他のプレイヤーがこのモブをテイム中です。");
            return true;
        }

        // Check max attempts per mob
        int maxAttempts = configManager.getMaxAttemptsPerMob();
        if (maxAttempts > 0) {
            int attempts = tamingAttempts.getOrDefault(entity.getUniqueId(), 0);
            if (attempts >= maxAttempts) {
                player.sendMessage("§cこのモブにはこれ以上テイムを試行できません。（" + maxAttempts + "/" + maxAttempts + "回）");
                return true;
            }
        }

        // Find matching taming item from config list
        List<TamingItemEntry> items = configManager.getTamingMonsterItems();
        TamingItemEntry matched = findMatchingItem(mainHand, items);
        if (matched == null) {
            return false;
        }

        // Save a copy of the consumed item before consuming (for refund on failure)
        ItemStack consumedItemCopy = null;
        if (matched.consume()) {
            consumedItemCopy = mainHand.asOne();
            mainHand.setAmount(mainHand.getAmount() - 1);
            // Force inventory update next tick to prevent other plugins from reverting item consumption
            Bukkit.getScheduler().runTask(plugin, () -> player.updateInventory());
        }

        // Increment attempt counter
        if (maxAttempts > 0) {
            tamingAttempts.merge(entity.getUniqueId(), 1, Integer::sum);
        }

        // Check success rate (for monster, rate check happens at tame start)
        if (matched.successRate() < 1.0
                && ThreadLocalRandom.current().nextDouble() >= matched.successRate()) {
            int current = tamingAttempts.getOrDefault(entity.getUniqueId(), 0);
            int pct = (int) (matched.successRate() * 100);
            String attemptMsg = maxAttempts > 0 ? " §7(" + current + "/" + maxAttempts + "回)" : "";
            player.sendMessage("§cテイミングに失敗しました... (成功率: §f" + pct + "%§c)" + attemptMsg);
            return true;
        }

        // Taming succeeded — clear attempt counter
        tamingAttempts.remove(entity.getUniqueId());

        // Register pending tame (include consumed item for refund)
        pendingMonsterTames.put(player.getUniqueId(),
                new PendingTame(entity, System.currentTimeMillis(), consumedItemCopy));

        player.sendMessage("§eこの敵を倒して仲間にしよう！");

        return true;
    }

    /**
     * Handles a monster death event. If the monster was a pending tame target
     * for the killer, spawns a dummy entity and enters the naming phase.
     *
     * @param entity the dying entity
     * @param killer the player who killed it (may be null)
     * @return true if the death was handled as a tame event
     */
    public boolean handleMonsterDeath(@NotNull LivingEntity entity, @NotNull Player tamingPlayer) {
        UUID playerUuid = tamingPlayer.getUniqueId();
        PendingTame pending = pendingMonsterTames.get(playerUuid);

        if (pending == null || !pending.entity().equals(entity)) {
            return false;
        }

        // Remove from pending tames
        pendingMonsterTames.remove(playerUuid);

        // Capture entity visual info (color, size, etc.) and scale BEFORE it despawns
        Object entityInfo = captureEntityInfo(entity);
        double capturedScale = captureScale(entity);

        // Spawn dummy entity (AI off, invulnerable) and copy original entity's properties
        Entity dummyEntity = entity.getWorld().spawnEntity(
                entity.getLocation(), entity.getType());
        if (dummyEntity instanceof LivingEntity livingDummy) {
            livingDummy.setAI(false);
            livingDummy.setInvulnerable(true);
            copyEntityProperties(entity, livingDummy);
            if (capturedScale > 0.0) {
                AttributeInstance scaleAttr = livingDummy.getAttribute(Attribute.SCALE);
                if (scaleAttr != null) {
                    scaleAttr.setBaseValue(capturedScale);
                }
            }
        }

        // Visual/audio feedback
        entity.getWorld().spawnParticle(Particle.HEART, entity.getLocation(),
                15, 1.0, 1.0, 1.0, 0.3);
        entity.getWorld().playSound(entity.getLocation(),
                Sound.ENTITY_PLAYER_LEVELUP, 2.0f, 1.0f);

        // Roll rarity and personality
        Rarity rarity = rarityManager.rollRarity(entity, tamingPlayer);
        Personality personality = personalityManager.rollPersonality();

        // Store in pending names (capture entityType, visual info, and consumed item from original entity)
        String addonMobType = resolveAddonMobType(entity);
        pendingNames.put(playerUuid, new PendingName(
                dummyEntity, entity.getType(), addonMobType, rarity, personality,
                System.currentTimeMillis(), entityInfo, pending.consumedItem(), capturedScale));

        // Notify player
        tamingPlayer.sendMessage("§a敵が仲間になりたいようだ！ "
                + rarity.getColoredName() + " §7[" + personality.getDisplayName() + "]");
        tamingPlayer.sendMessage("§a/pettame <名前> で仲間にします（"
                + (configManager.getTamingTimeout() / 1000L) + "秒後デスポーンします）");

        // Schedule timeout
        long timeoutTicks = (configManager.getTamingTimeout() / 1000L) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingName stillPending = pendingNames.get(playerUuid);
            if (stillPending != null && stillPending.dummyEntity().equals(dummyEntity)) {
                dummyEntity.remove();
                pendingNames.remove(playerUuid);
                if (tamingPlayer.isOnline()) {
                    tamingPlayer.sendMessage("§c名前をつける時間が過ぎました。仲間にできませんでした。");
                }
            }
        }, timeoutTicks);

        return true;
    }

    /**
     * Handles the /pettame command for naming a pending tamed creature.
     * Works for both animals (entity still alive as dummy) and monsters (entity dead, using type).
     *
     * @param player the player assigning the name
     * @param name   the desired pet name
     * @return true if a pet was successfully named and created
     */
    public boolean handlePetName(@NotNull Player player, @NotNull String name) {
        // Validate name
        if (name.isEmpty() || name.length() > 32) {
            player.sendMessage("§c名前は1～32文字で指定してください。");
            return false;
        }

        PendingName pending = pendingNames.remove(player.getUniqueId());
        if (pending == null) {
            player.sendMessage("§c仲間にできるモブが存在しません。");
            return false;
        }

        Entity dummyEntity = pending.dummyEntity();
        // Use addon-internal mob type (e.g. CHARGED_CREEPER) for config lookups
        String mobType = pending.addonMobType();
        InactiveMyPet inactiveMyPet;

        // Always prefer captured entityInfo (from the original entity) over the dummy's
        // live state. For monster taming, the dummy is a newly spawned entity that may
        // randomly differ from the original (e.g. baby zombie spawning at 5% chance).
        // For animal taming, the dummy IS the original, but using captured info is equally
        // correct and more consistent.
        if (pending.entityInfo() != null) {
            inactiveMyPet = createMyPetByType(player, pending.entityType(), name, pending.entityInfo());
        } else if (dummyEntity.isValid() && dummyEntity instanceof LivingEntity livingDummy) {
            // Fallback: no captured info available, read from live entity
            inactiveMyPet = createMyPetFromEntity(player, livingDummy, name);
        } else {
            inactiveMyPet = createMyPetByType(player, pending.entityType(), name, null);
        }
        // Clean up the dummy entity
        if (dummyEntity.isValid()) {
            dummyEntity.remove();
        }

        if (inactiveMyPet == null) {
            player.sendMessage("§cペットの作成に失敗しました。このモブはMyPetに対応していない可能性があります。");
            // Refund consumed taming item
            refundConsumedItem(player, pending.consumedItem());
            return false;
        }

        // Assign skill tree based on config rules
        skilltreeAssigner.assignOnTame(inactiveMyPet, pending.rarity(), mobType);

        // Create addon data
        UUID addonPetId = UUID.randomUUID();

        PetData petData = new PetData(
                addonPetId, inactiveMyPet.getUUID(), player.getUniqueId(),
                mobType, pending.rarity(), pending.personality(),
                1, 0, 0, System.currentTimeMillis() / 1000L, null, pending.capturedScale());
        PetStats petStats = statsManager.createBaseStats(petData);

        // Save to cache BEFORE activation (applyStats reads from cache)
        petDataCache.put(petData, petStats);

        // Record in encyclopedia (async to avoid blocking main thread with JDBC)
        UUID playerUuid = player.getUniqueId();
        String finalMobType = mobType;
        com.mypetaddon.rarity.Rarity finalRarity = pending.rarity();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                encyclopediaRepository.recordTame(playerUuid, finalMobType, finalRarity));

        // NOW register and activate — triggers onMyPetActivated → applyStats()
        // which can now find the cached PetData/PetStats
        registerAndActivate(inactiveMyPet);

        // Cleanup any remaining pending tame entries for this player
        pendingMonsterTames.remove(player.getUniqueId());

        player.sendMessage("§a§l仲間になった！");

        return true;
    }

    /**
     * Creates a MyPet blueprint from an entity WITHOUT registering/activating.
     * Call {@link #registerAndActivate(InactiveMyPet)} separately after cache data is prepared.
     *
     * @param player  the owning player
     * @param entity  the source entity (used for type and stored info)
     * @param petName the name for the pet
     * @return the created InactiveMyPet, or null on failure
     */
    @Nullable
    public InactiveMyPet createMyPetFromEntity(@NotNull Player player,
                                                @NotNull Entity entity,
                                                @NotNull String petName) {
        try {
            var pm = MyPetApi.getPlayerManager();
            MyPetPlayer myPetPlayer = pm.isMyPetPlayer(player)
                    ? pm.getMyPetPlayer(player)
                    : pm.registerMyPetPlayer(player);

            InactiveMyPet inactiveMyPet = new InactiveMyPet(myPetPlayer);

            MyPetType myPetType = MyPetType.byEntityTypeName(entity.getType().name());
            inactiveMyPet.setPetType(myPetType);
            inactiveMyPet.setPetName(petName);

            WorldGroup wg = WorldGroup.getGroupByWorld(player.getWorld());
            inactiveMyPet.setWorldGroup(wg.getName());
            inactiveMyPet.getOwner().setMyPetForWorldGroup(wg, inactiveMyPet.getUUID());

            // Convert entity info (equipment, colors, etc.)
            MyPetApi.getServiceManager()
                    .getService(EntityConverterService.class)
                    .ifPresent(service -> inactiveMyPet.setInfo(service.convertEntity((LivingEntity) entity)));

            return inactiveMyPet;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to create MyPet for player " + player.getName(), e);
            return null;
        }
    }

    /**
     * Creates a MyPet blueprint from an EntityType WITHOUT registering/activating.
     * Call {@link #registerAndActivate(InactiveMyPet)} separately after cache data is prepared.
     *
     * @param player     the owning player
     * @param entityType the entity type for the pet
     * @param petName    the name for the pet
     * @param entityInfo captured visual info from the original entity (may be null)
     * @return the created InactiveMyPet, or null on failure
     */
    @Nullable
    public InactiveMyPet createMyPetByType(@NotNull Player player,
                                            @NotNull EntityType entityType,
                                            @NotNull String petName,
                                            @Nullable Object entityInfo) {
        try {
            var pm = MyPetApi.getPlayerManager();
            MyPetPlayer myPetPlayer = pm.isMyPetPlayer(player)
                    ? pm.getMyPetPlayer(player)
                    : pm.registerMyPetPlayer(player);

            InactiveMyPet inactiveMyPet = new InactiveMyPet(myPetPlayer);

            MyPetType myPetType = MyPetType.byEntityTypeName(entityType.name());
            inactiveMyPet.setPetType(myPetType);
            inactiveMyPet.setPetName(petName);

            // Apply captured entity visual info (color, size, equipment, etc.)
            if (entityInfo instanceof de.Keyle.MyPet.util.nbt.TagCompound tagCompound) {
                inactiveMyPet.setInfo(tagCompound);
            }

            WorldGroup wg = WorldGroup.getGroupByWorld(player.getWorld());
            inactiveMyPet.setWorldGroup(wg.getName());
            inactiveMyPet.getOwner().setMyPetForWorldGroup(wg, inactiveMyPet.getUUID());

            return inactiveMyPet;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to create MyPet by type for player " + player.getName(), e);
            return null;
        }
    }

    /**
     * Registers an InactiveMyPet with the MyPet repository and activates it.
     * Must be called AFTER all addon data (PetData, PetStats) is cached,
     * because activation triggers onMyPetActivated → applyStats() which
     * reads from the cache.
     */
    public void registerAndActivate(@NotNull InactiveMyPet inactiveMyPet) {
        MyPetApi.getRepository().addMyPet(inactiveMyPet, new RepositoryCallback<Boolean>() {
            @Override
            public void callback(Boolean value) {
                var activePet = MyPetApi.getMyPetManager()
                        .activateMyPet(inactiveMyPet)
                        .orElse(null);
                if (activePet != null) {
                    activePet.createEntity();
                }
            }
        });
    }

    /**
     * Refunds a consumed taming item to the player's inventory.
     * If inventory is full, drops the item at the player's location.
     */
    private void refundConsumedItem(@NotNull Player player, @Nullable ItemStack consumedItem) {
        if (consumedItem == null) {
            return;
        }
        var leftover = player.getInventory().addItem(consumedItem);
        if (!leftover.isEmpty()) {
            // Inventory full — drop at player's feet
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
        player.sendMessage("§7テイミングアイテムを返却しました。");
    }

    /**
     * Captures visual entity info (color, size, equipment, etc.) via MyPet's EntityConverterService.
     * Works on dying entities (EntityDeathEvent) as entity properties are still accessible.
     */
    @Nullable
    private Object captureEntityInfo(@NotNull LivingEntity entity) {
        try {
            var serviceOpt = MyPetApi.getServiceManager()
                    .getService(EntityConverterService.class);
            if (serviceOpt.isPresent()) {
                Object result = serviceOpt.get().convertEntity(entity);
                if (result != null) {
                    logger.fine("[Taming] Captured entity info for " + entity.getType().name()
                            + ": " + result);
                    return result;
                }
            }
        } catch (Exception e) {
            logger.warning("[Taming] Failed to capture entity info for "
                    + entity.getType().name() + ": " + e.getMessage());
        }
        logger.warning("[Taming] entityInfo is null for " + entity.getType().name()
                + " (dead=" + entity.isDead() + ")");
        return null;
    }

    /**
     * Copies key visual properties from the original entity to a dummy entity.
     * Ensures the dummy looks identical to the original during the naming phase.
     * This covers properties that spawnEntity(type) doesn't preserve.
     */
    private void copyEntityProperties(@NotNull LivingEntity original, @NotNull LivingEntity dummy) {
        try {
            // Creeper: charged/powered state
            if (original instanceof Creeper origCreeper && dummy instanceof Creeper dummyCreeper) {
                dummyCreeper.setPowered(origCreeper.isPowered());
            }
            // Zombie: baby state
            if (original instanceof Zombie origZombie && dummy instanceof Zombie dummyZombie) {
                dummyZombie.setBaby(origZombie.isBaby());
            }
            // Sheep: wool color
            if (original instanceof Sheep origSheep && dummy instanceof Sheep dummySheep) {
                dummySheep.setColor(origSheep.getColor());
            }
            // Slime/MagmaCube: size
            if (original instanceof Slime origSlime && dummy instanceof Slime dummySlime) {
                dummySlime.setSize(origSlime.getSize());
            }
            // Wolf: collar color, angry
            if (original instanceof Wolf origWolf && dummy instanceof Wolf dummyWolf) {
                dummyWolf.setCollarColor(origWolf.getCollarColor());
                dummyWolf.setAngry(origWolf.isAngry());
            }
            // Ageable: baby state (covers animals like Cow, Pig, etc.)
            if (original instanceof Ageable origAge && dummy instanceof Ageable dummyAge) {
                if (!origAge.isAdult()) {
                    dummyAge.setBaby();
                }
            }
            // Custom name (if any)
            if (original.getCustomName() != null) {
                dummy.setCustomName(original.getCustomName());
                dummy.setCustomNameVisible(original.isCustomNameVisible());
            }
        } catch (Exception e) {
            logger.fine("[Taming] Could not copy some entity properties: " + e.getMessage());
        }
    }

    /**
     * Captures the effective scale of an entity from Attribute.SCALE.
     * Returns 0.0 if scale is default (1.0) or unavailable, meaning "no custom scale".
     */
    private double captureScale(@NotNull LivingEntity entity) {
        try {
            AttributeInstance scaleAttr = entity.getAttribute(Attribute.SCALE);
            if (scaleAttr != null) {
                double value = scaleAttr.getValue();
                // Only record non-default scale (default is 1.0)
                if (Math.abs(value - 1.0) > 1e-6) {
                    return value;
                }
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    /**
     * Resolves the addon-internal mob type for an entity.
     * Handles special variants that are treated as distinct types
     * (e.g. charged creeper → CHARGED_CREEPER).
     */
    @NotNull
    private String resolveAddonMobType(@NotNull LivingEntity entity) {
        String baseType = entity.getType().name();
        // Charged Creeper is treated as a separate mob type
        if (entity instanceof Creeper creeper && creeper.isPowered()) {
            return "CHARGED_CREEPER";
        }
        return baseType;
    }

    /**
     * Checks whether an entity type is considered an animal (non-hostile) for taming purposes.
     * Uses the Enemy interface (Paper 1.19+) to correctly exclude hostile mobs like
     * Slime, Ghast, Phantom, MagmaCube which don't extend Monster.
     * Also verifies the type is supported by MyPet and not excluded in config.
     */
    public boolean isAnimal(@NotNull EntityType type) {
        if (!isSupportedByMyPet(type)) {
            return false;
        }
        try {
            Class<?> entityClass = type.getEntityClass();
            if (entityClass == null) {
                return false;
            }
            return LivingEntity.class.isAssignableFrom(entityClass)
                    && !Enemy.class.isAssignableFrom(entityClass)
                    && !Monster.class.isAssignableFrom(entityClass)
                    && !org.bukkit.entity.Player.class.isAssignableFrom(entityClass);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks whether an entity type is considered a monster for taming purposes.
     * Uses Enemy interface (Paper 1.19+) to catch Slime, Ghast, Phantom, etc.
     * that don't extend Monster but are hostile.
     * Also verifies the type is supported by MyPet and not excluded in config.
     */
    public boolean isMonster(@NotNull EntityType type) {
        if (!isSupportedByMyPet(type)) {
            return false;
        }
        try {
            Class<?> entityClass = type.getEntityClass();
            if (entityClass == null) {
                return false;
            }
            return Monster.class.isAssignableFrom(entityClass)
                    || Enemy.class.isAssignableFrom(entityClass);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks whether an entity type is supported by MyPet and not excluded in config.
     * MyPetType.byEntityTypeName throws MyPetTypeNotFoundException for unsupported types,
     * and also checks Minecraft version compatibility.
     */
    private boolean isSupportedByMyPet(@NotNull EntityType type) {
        if (configManager.getTamingExcludedTypes().contains(type.name())) {
            return false;
        }
        try {
            MyPetType.byEntityTypeName(type.name());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns the pending names map (for command handler access).
     */
    @NotNull
    public Map<UUID, PendingName> getPendingNames() {
        return pendingNames;
    }

    // ─── Internal ────────────────────────────────────────────────

    /**
     * Checks whether the given entity is already claimed by another player
     * (pending monster tame or pending name assignment).
     */
    private boolean isEntityClaimedByOther(@NotNull Entity entity, @NotNull UUID playerUuid) {
        for (Map.Entry<UUID, PendingTame> entry : pendingMonsterTames.entrySet()) {
            if (!entry.getKey().equals(playerUuid) && entry.getValue().entity().equals(entity)) {
                return true;
            }
        }
        for (Map.Entry<UUID, PendingName> entry : pendingNames.entrySet()) {
            if (!entry.getKey().equals(playerUuid) && entry.getValue().dummyEntity().equals(entity)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the first matching taming item entry for the given hand item.
     *
     * @param handItem the item the player is holding
     * @param entries  the list of taming item entries from config
     * @return the matching entry, or null if none match
     */
    @Nullable
    private TamingItemEntry findMatchingItem(@NotNull ItemStack handItem,
                                             @NotNull List<TamingItemEntry> entries) {
        for (TamingItemEntry entry : entries) {
            if (ItemMatcher.matches(handItem, entry.itemDescriptor(), logger)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Removes stale pending tame entries older than the configured timeout.
     * Also removes entries whose target entity is dead or removed.
     */
    private void cleanupStalePendingTames() {
        long now = System.currentTimeMillis();
        long timeoutMs = configManager.getTamingTimeout();

        pendingMonsterTames.entrySet().removeIf(entry -> {
            PendingTame pending = entry.getValue();
            boolean isStale = (now - pending.timestamp()) > timeoutMs;
            boolean isDead = pending.entity().isDead() || !pending.entity().isValid();
            return isStale || isDead;
        });

        // Clean up taming attempts for dead/despawned entities
        tamingAttempts.keySet().removeIf(uuid -> {
            Entity entity = org.bukkit.Bukkit.getServer().getEntity(uuid);
            return entity == null || entity.isDead() || !entity.isValid();
        });
    }

    /**
     * Finds the player who registered a pending tame for the given entity.
     * Used to allow taming regardless of death cause (player kill, pet kill, fall, etc.).
     *
     * @param entity the dying entity
     * @return the player who used a taming item on this entity, or null if not pending
     */
    @Nullable
    public Player findPendingTameOwner(@NotNull LivingEntity entity) {
        for (Map.Entry<UUID, PendingTame> entry : pendingMonsterTames.entrySet()) {
            if (entry.getValue().entity().equals(entity)) {
                return Bukkit.getPlayer(entry.getKey());
            }
        }
        return null;
    }

    /**
     * Cleans up any pending tame entry that references the given entity,
     * regardless of which player registered it.
     */
    private void cleanupPendingTameForEntity(@NotNull LivingEntity entity) {
        pendingMonsterTames.entrySet().removeIf(
                entry -> entry.getValue().entity().equals(entity));
    }
}
