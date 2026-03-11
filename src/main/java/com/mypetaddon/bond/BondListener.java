package com.mypetaddon.bond;

import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.integration.MythicMobsIntegration;
import de.Keyle.MyPet.MyPetApi;
import de.Keyle.MyPet.api.entity.MyPet;
import de.Keyle.MyPet.api.entity.MyPetBukkitEntity;
import de.Keyle.MyPet.api.player.MyPetPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.Bukkit;
import org.bukkit.inventory.EquipmentSlot;
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
     * Awards combat-kill bond EXP when a player's pet is involved in a kill.
     * Checks if the killer has an active MyPet nearby.
     */
    @EventHandler
    public void onEntityDeath(@NotNull EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();

        // Skip MythicMobs (they have their own systems)
        if (mythicMobsIntegration.isMythicMob(victim)) {
            return;
        }

        Player killer = victim.getKiller();
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
        MyPet myPet = myPetBukkit.getMyPet();

        // Only the owner gets bond EXP
        if (!myPet.getOwner().getPlayer().equals(player)) {
            return;
        }

        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            return;
        }

        // Award feeding bond EXP
        bondManager.addBondExp(petData.addonPetId(), "feeding",
                getBondGainFromConfig("feeding"), petData.personality());
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

    // ─── Internal ────────────────────────────────────────────────

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
