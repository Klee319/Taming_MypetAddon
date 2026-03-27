package com.mypetaddon.bond;

import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.integration.MythicMobsIntegration;
import de.Keyle.MyPet.MyPetApi;
import de.Keyle.MyPet.api.entity.MyPet;
import de.Keyle.MyPet.api.entity.MyPetBukkitEntity;
import de.Keyle.MyPet.api.entity.StoredMyPet;
import de.Keyle.MyPet.api.event.MyPetRemoveEvent;
import de.Keyle.MyPet.api.player.MyPetPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Event listener for bond experience gain and loss.
 * Delegates to {@link BondManager} for debounced EXP updates.
 */
public final class BondListener implements Listener {

    private final BondManager bondManager;
    private final PetDataCache petDataCache;
    private final MythicMobsIntegration mythicMobsIntegration;
    private final ConfigManager configManager;
    private final JavaPlugin plugin;

    public BondListener(@NotNull BondManager bondManager,
                        @NotNull PetDataCache petDataCache,
                        @NotNull MythicMobsIntegration mythicMobsIntegration,
                        @NotNull ConfigManager configManager,
                        @NotNull JavaPlugin plugin) {
        this.bondManager = bondManager;
        this.petDataCache = petDataCache;
        this.mythicMobsIntegration = mythicMobsIntegration;
        this.configManager = configManager;
        this.plugin = plugin;
    }

    /**
     * Awards combat-kill bond EXP when a player kills a mob.
     * Supports direct kills, projectile kills, and pet kills by tracing
     * the damage source back to the owning player.
     */
    @EventHandler
    public void onEntityDeath(@NotNull EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();

        // Skip MythicMobs (they have their own systems)
        if (mythicMobsIntegration.isMythicMob(victim)) {
            return;
        }

        // Resolve the player responsible for the kill
        // Priority: getKiller() > projectile shooter > pet owner
        Player killer = resolveKiller(victim);
        if (killer == null) {
            return;
        }

        // Check if the killer has an active MyPet
        MyPet myPet = getActiveMyPet(killer);
        if (myPet == null) {
            return;
        }

        // Look up addon pet data
        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            return;
        }

        // Award combat-kill bond EXP
        bondManager.addBondExp(petData.addonPetId(), "combat-kill",
                getBondGainFromConfig("combat-kill"), petData.personality());

        // Grant MyPet EXP for indirect kills (pet kills, environmental, etc.)
        // getKiller() already handles direct + projectile kills for MyPet's built-in EXP,
        // but pet-assisted kills may not trigger it
        if (victim.getKiller() == null) {
            try {
                double expValue = event.getDroppedExp();
                if (expValue > 0) {
                    myPet.getExperience().addExp(expValue);
                }
            } catch (Exception e) {
                // MyPet API may not support addExp - silently ignore
            }
        }
    }

    /**
     * Awards feeding bond EXP when a player right-clicks their MyPet entity.
     * Only triggers for MyPetBukkitEntity interactions on HAND.
     */
    @EventHandler
    public void onPlayerInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Entity rightClicked = event.getRightClicked();
        if (!(rightClicked instanceof MyPetBukkitEntity myPetBukkit)) {
            return;
        }

        Player player = event.getPlayer();

        // Only award feeding bond EXP when the player is actually holding an item.
        // Empty-hand right-clicks are not feeding — they are just interaction/petting.
        ItemStack handItem = player.getInventory().getItemInMainHand();
        if (handItem.getType() == Material.AIR) {
            return;
        }

        MyPet myPet = myPetBukkit.getMyPet();

        // Only the owner gets bond EXP
        Player owner = myPet.getOwner().getPlayer();
        if (owner == null || !owner.equals(player)) {
            return;
        }

        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            return;
        }

        // Store the item count before MyPet processes the feeding.
        // After 1 tick, check if the item was consumed (MyPet ate it).
        int itemCountBefore = handItem.getAmount();
        Material itemType = handItem.getType();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            ItemStack currentItem = player.getInventory().getItemInMainHand();
            // Item was consumed if: type changed to AIR, or same type but amount decreased
            boolean consumed = currentItem.getType() == Material.AIR
                    || (currentItem.getType() == itemType && currentItem.getAmount() < itemCountBefore);
            if (!consumed) {
                return;
            }

            // Award feeding bond EXP — only when MyPet actually ate the item
            bondManager.addBondExp(petData.addonPetId(), "feeding",
                    getBondGainFromConfig("feeding"), petData.personality());
        });
    }

    /**
     * Applies daily decay on login and awards login-summon bond EXP.
     */
    @EventHandler
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Preload pet data asynchronously to avoid sync DB hits on cache misses
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            petDataCache.preloadForPlayer(player.getUniqueId());

            // After preload, apply bond logic on the main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }

                MyPet myPet = getActiveMyPet(player);
                if (myPet == null) {
                    return;
                }

                PetData petData = petDataCache.get(myPet.getUUID());
                if (petData == null) {
                    return;
                }

                // Apply daily decay based on last login time
                long lastPlayed = player.getLastPlayed();
                if (lastPlayed > 0) {
                    bondManager.applyDailyDecay(petData.addonPetId(), lastPlayed);
                }

                // Award login-summon bond EXP
                bondManager.addBondExp(petData.addonPetId(), "login-summon",
                        getBondGainFromConfig("login-summon"), petData.personality());
            });
        });
    }

    /**
     * Applies bond penalty when a MyPet dies.
     * MyPet handles death internally (cancels EntityDeathEvent), so we use
     * MyPetRemoveEvent with Source.Death to detect pet death reliably.
     */
    @EventHandler
    public void onPetDeath(@NotNull MyPetRemoveEvent event) {
        if (event.getSource() != MyPetRemoveEvent.Source.Death) {
            return;
        }

        StoredMyPet storedPet = event.getMyPet();
        PetData petData = petDataCache.get(storedPet.getUUID());
        if (petData == null) {
            return;
        }

        int deathPenalty = configManager.getBondLoss("pet-death");
        if (deathPenalty <= 0) {
            return;
        }

        bondManager.applyDeathPenalty(petData.addonPetId(), deathPenalty);

        Player owner = event.getPlayer();
        if (owner != null && owner.isOnline()) {
            String msg = configManager.getString("messages.bond-pet-death",
                    "§c%pet_name% が倒されました。絆が §f%bond_loss% §c減少しました。");
            msg = msg.replace("%pet_name%", storedPet.getPetName())
                     .replace("%bond_loss%", String.valueOf(deathPenalty));
            owner.sendMessage(msg);
        }
    }

    // ─── Internal ────────────────────────────────────────────────

    /**
     * Resolves the player responsible for killing an entity.
     * Handles direct kills, projectile kills (arrows, tridents, potions),
     * and MyPet entity kills (traces back to pet owner).
     */
    @Nullable
    private Player resolveKiller(@NotNull LivingEntity victim) {
        // 1. Direct player kill (includes projectile kills in vanilla)
        Player killer = victim.getKiller();
        if (killer != null) {
            return killer;
        }

        // 2. Trace last damage cause for pet kills and other indirect damage
        if (victim.getLastDamageCause() instanceof EntityDamageByEntityEvent damageEvent) {
            Entity damager = damageEvent.getDamager();

            // Pet entity killed the mob -> credit the pet owner
            if (damager instanceof MyPetBukkitEntity myPetBukkit) {
                try {
                    return myPetBukkit.getMyPet().getOwner().getPlayer();
                } catch (Exception e) {
                    return null;
                }
            }

            // Projectile with player shooter (fallback if getKiller() missed it)
            if (damager instanceof Projectile projectile
                    && projectile.getShooter() instanceof Player shooter) {
                return shooter;
            }
        }

        return null;
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

    /**
     * Reads the bond gain amount from config for a given source.
     * Returns 0 if not configured.
     */
    private int getBondGainFromConfig(@NotNull String source) {
        return configManager.getBondGain(source);
    }
}
