package com.mypetaddon.taming;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.EncyclopediaRepository;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.PetStats;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.integration.MythicMobsIntegration;
import com.mypetaddon.personality.Personality;
import com.mypetaddon.personality.PersonalityManager;
import com.mypetaddon.rarity.Rarity;
import com.mypetaddon.rarity.RarityManager;
import com.mypetaddon.stats.StatsManager;
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
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Logger logger;

    /** Player UUID -> pending monster tame target. */
    private final Map<UUID, PendingTame> pendingMonsterTames = new ConcurrentHashMap<>();

    /** Player UUID -> pending name assignment for a tamed monster. */
    private final Map<UUID, PendingName> pendingNames = new ConcurrentHashMap<>();

    /**
     * Represents a monster that a player is attempting to tame (awaiting kill).
     */
    public record PendingTame(@NotNull LivingEntity entity, long timestamp) {}

    /**
     * Represents a tamed monster awaiting a name from the player.
     */
    public record PendingName(
            @NotNull Entity dummyEntity,
            @NotNull LivingEntity originalEntity,
            @NotNull Rarity rarity,
            @NotNull Personality personality,
            long timestamp
    ) {}

    public TamingManager(@NotNull MyPetAddonPlugin plugin,
                         @NotNull ConfigManager configManager,
                         @NotNull RarityManager rarityManager,
                         @NotNull PersonalityManager personalityManager,
                         @NotNull PetDataCache petDataCache,
                         @NotNull EncyclopediaRepository encyclopediaRepository,
                         @NotNull MythicMobsIntegration mythicMobsIntegration,
                         @NotNull StatsManager statsManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.rarityManager = rarityManager;
        this.personalityManager = personalityManager;
        this.petDataCache = petDataCache;
        this.encyclopediaRepository = encyclopediaRepository;
        this.mythicMobsIntegration = mythicMobsIntegration;
        this.statsManager = statsManager;
        this.logger = plugin.getLogger();
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
        // Check taming item
        String itemName = configManager.getTamingAnimalItem();
        Material required = Material.matchMaterial(itemName);
        if (required == null || mainHand.getType() != required) {
            return false;
        }

        // Check entity is an animal
        if (!isAnimal(entity.getType())) {
            return false;
        }

        // Skip entities with AI disabled (already being tamed or is a dummy)
        if (!entity.hasAI()) {
            return false;
        }

        // Consume one item
        mainHand.setAmount(mainHand.getAmount() - 1);

        // Visual/audio feedback
        entity.getWorld().spawnParticle(Particle.HEART, entity.getLocation(),
                15, 1.0, 1.0, 1.0, 0.3);
        entity.getWorld().playSound(entity.getLocation(),
                Sound.ENTITY_PLAYER_LEVELUP, 2.0f, 1.0f);

        // Roll rarity and personality
        Rarity rarity = rarityManager.rollRarity(entity, player);
        Personality personality = personalityManager.rollPersonality();

        // Create MyPet (uses entity type name as default pet name for animals)
        String petName = entity.getType().name();
        InactiveMyPet inactiveMyPet = createMyPetFromEntity(player, entity, petName);
        if (inactiveMyPet == null) {
            player.sendMessage("§cペットの作成に失敗しました。");
            return true;
        }

        // Remove the original entity
        entity.remove();

        // Create addon data
        UUID addonPetId = UUID.randomUUID();
        String mobType = entity.getType().name();

        PetData petData = new PetData(
                addonPetId, inactiveMyPet.getUUID(), player.getUniqueId(),
                mobType, rarity, personality,
                1, 0, 0, System.currentTimeMillis() / 1000L, null
        );
        PetStats petStats = statsManager.createBaseStats(petData);

        // Save to cache
        petDataCache.put(petData, petStats);

        // Record in encyclopedia
        encyclopediaRepository.recordTame(player.getUniqueId(), mobType, rarity);

        player.sendMessage("§a§l能力を開放した！ "
                + rarity.getColoredName() + " §7[" + personality.getDisplayName() + "]");

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
        // Check taming item
        String itemName = configManager.getTamingMonsterItem();
        Material required = Material.matchMaterial(itemName);
        if (required == null || mainHand.getType() != required) {
            return false;
        }

        // Check entity is a monster/hostile
        if (!isMonster(entity.getType())) {
            return false;
        }

        // Only one pending tame per player
        if (pendingMonsterTames.containsKey(player.getUniqueId())) {
            return false;
        }

        // Skip entities with AI disabled
        if (!entity.hasAI()) {
            return false;
        }

        // Consume one item
        mainHand.setAmount(mainHand.getAmount() - 1);

        // Register pending tame
        pendingMonsterTames.put(player.getUniqueId(),
                new PendingTame(entity, System.currentTimeMillis()));

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
    public boolean handleMonsterDeath(@NotNull LivingEntity entity, @Nullable Player killer) {
        if (killer == null) {
            // If a pending tame entity died without a player kill, clean up
            cleanupPendingTameForEntity(entity);
            return false;
        }

        UUID killerUuid = killer.getUniqueId();
        PendingTame pending = pendingMonsterTames.get(killerUuid);

        if (pending == null || !pending.entity().equals(entity)) {
            // Clean up if this entity was pending for another player who didn't kill it
            cleanupPendingTameForEntity(entity);
            return false;
        }

        // Remove from pending tames
        pendingMonsterTames.remove(killerUuid);

        // Spawn dummy entity (AI off, invulnerable)
        Entity dummyEntity = entity.getWorld().spawnEntity(
                entity.getLocation(), entity.getType());
        if (dummyEntity instanceof LivingEntity livingDummy) {
            livingDummy.setAI(false);
            livingDummy.setInvulnerable(true);
        }

        // Visual/audio feedback
        entity.getWorld().spawnParticle(Particle.HEART, entity.getLocation(),
                15, 1.0, 1.0, 1.0, 0.3);
        entity.getWorld().playSound(entity.getLocation(),
                Sound.ENTITY_PLAYER_LEVELUP, 2.0f, 1.0f);

        // Roll rarity and personality
        Rarity rarity = rarityManager.rollRarity(entity, killer);
        Personality personality = personalityManager.rollPersonality();

        // Store in pending names
        pendingNames.put(killerUuid, new PendingName(
                dummyEntity, entity, rarity, personality, System.currentTimeMillis()));

        // Notify player
        killer.sendMessage("§a敵が仲間になりたいようだ！ "
                + rarity.getColoredName() + " §7[" + personality.getDisplayName() + "]");
        killer.sendMessage("§a/named <名前> で仲間にします（"
                + (configManager.getTamingTimeout() / 1000L) + "秒後デスポーンします）");

        // Schedule timeout
        long timeoutTicks = (configManager.getTamingTimeout() / 1000L) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingName stillPending = pendingNames.get(killerUuid);
            if (stillPending != null && stillPending.dummyEntity().equals(dummyEntity)) {
                dummyEntity.remove();
                pendingNames.remove(killerUuid);
                if (killer.isOnline()) {
                    killer.sendMessage("§c名前をつける時間が過ぎました。仲間にできませんでした。");
                }
            }
        }, timeoutTicks);

        return true;
    }

    /**
     * Handles the /named command for naming a pending tamed monster.
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
            player.sendMessage("§c仲間にできる敵が存在しません。");
            return false;
        }

        // Remove dummy entity
        pending.dummyEntity().remove();

        // Create MyPet
        InactiveMyPet inactiveMyPet = createMyPetFromEntity(
                player, pending.dummyEntity(), name);
        if (inactiveMyPet == null) {
            player.sendMessage("§cペットの作成に失敗しました。");
            return false;
        }

        // Create addon data
        UUID addonPetId = UUID.randomUUID();
        String mobType = pending.originalEntity().getType().name();

        PetData petData = new PetData(
                addonPetId, inactiveMyPet.getUUID(), player.getUniqueId(),
                mobType, pending.rarity(), pending.personality(),
                1, 0, 0, System.currentTimeMillis() / 1000L, null
        );
        PetStats petStats = statsManager.createBaseStats(petData);

        // Save to cache
        petDataCache.put(petData, petStats);

        // Record in encyclopedia
        encyclopediaRepository.recordTame(player.getUniqueId(), mobType, pending.rarity());

        // Cleanup any remaining pending tame entries for this player
        pendingMonsterTames.remove(player.getUniqueId());

        player.sendMessage("§a§l敵が仲間になった！");

        return true;
    }

    /**
     * Creates a MyPet from an entity using the MyPet API.
     * Ported from legacy createMyPet() function.
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
            // Get or register MyPetPlayer
            var pm = MyPetApi.getPlayerManager();
            MyPetPlayer myPetPlayer = pm.isMyPetPlayer(player)
                    ? pm.getMyPetPlayer(player)
                    : pm.registerMyPetPlayer(player);

            // Create InactiveMyPet blueprint
            InactiveMyPet inactiveMyPet = new InactiveMyPet(myPetPlayer);

            // Set mob type
            MyPetType myPetType = MyPetType.byEntityTypeName(entity.getType().name());
            inactiveMyPet.setPetType(myPetType);

            // Set pet name
            inactiveMyPet.setPetName(petName);

            // Set world group
            WorldGroup wg = WorldGroup.getGroupByWorld(player.getWorld());
            inactiveMyPet.setWorldGroup(wg.getName());
            inactiveMyPet.getOwner().setMyPetForWorldGroup(wg, inactiveMyPet.getUUID());

            // Convert entity info (equipment, colors, etc.)
            MyPetApi.getServiceManager()
                    .getService(EntityConverterService.class)
                    .ifPresent(service -> inactiveMyPet.setInfo(service.convertEntity((LivingEntity) entity)));

            // Register with MyPet repository and activate
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

            return inactiveMyPet;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to create MyPet for player " + player.getName(), e);
            return null;
        }
    }

    /**
     * Checks whether an entity type is considered an animal for taming purposes.
     */
    public boolean isAnimal(@NotNull EntityType type) {
        try {
            Class<?> entityClass = type.getEntityClass();
            return entityClass != null && Animals.class.isAssignableFrom(entityClass);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks whether an entity type is considered a monster for taming purposes.
     */
    public boolean isMonster(@NotNull EntityType type) {
        try {
            Class<?> entityClass = type.getEntityClass();
            return entityClass != null && Monster.class.isAssignableFrom(entityClass);
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
     * Cleans up any pending tame entry that references the given entity,
     * regardless of which player registered it.
     */
    private void cleanupPendingTameForEntity(@NotNull LivingEntity entity) {
        pendingMonsterTames.entrySet().removeIf(
                entry -> entry.getValue().entity().equals(entity));
    }
}
