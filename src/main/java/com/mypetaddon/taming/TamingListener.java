package com.mypetaddon.taming;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.integration.MythicMobsIntegration;
import com.mypetaddon.util.ItemMatcher;
import de.Keyle.MyPet.api.entity.MyPetBukkitEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Logger;

/**
 * Event listener that delegates taming interactions to {@link TamingManager}.
 * Handles player-entity interactions (right-click) and entity death events.
 * Supports LevelledMobs nametag entities (TextDisplay/ArmorStand) by resolving
 * the actual mob underneath.
 */
public final class TamingListener implements Listener {

    private final MyPetAddonPlugin plugin;
    private final TamingManager tamingManager;
    private final MythicMobsIntegration mythicMobsIntegration;
    private final Logger logger;

    public TamingListener(@NotNull MyPetAddonPlugin plugin,
                          @NotNull TamingManager tamingManager) {
        this.plugin = plugin;
        this.tamingManager = tamingManager;
        this.mythicMobsIntegration = plugin.getMythicMobsIntegration();
        this.logger = plugin.getLogger();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        // Only handle main hand interactions
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Entity rightClicked = event.getRightClicked();
        boolean debug = plugin.getConfigManager().getConfig().getBoolean("general.debug", false);

        if (debug) {
            logger.info("[Taming-Debug] Right-clicked: " + rightClicked.getType().name()
                    + " (class=" + rightClicked.getClass().getSimpleName()
                    + ", cancelled=" + event.isCancelled() + ")");
        }

        // Skip MythicMobs entities
        if (mythicMobsIntegration != null && mythicMobsIntegration.isMythicMob(rightClicked)) {
            if (debug) logger.info("[Taming-Debug] Skipped: MythicMob");
            return;
        }

        // Skip MyPet entities (don't interfere with MyPet clicks)
        if (rightClicked instanceof MyPetBukkitEntity) {
            if (debug) logger.info("[Taming-Debug] Skipped: MyPetBukkitEntity");
            return;
        }

        // Resolve the actual LivingEntity target
        // If clicked entity is not a LivingEntity (e.g. LevelledMobs TextDisplay nametag),
        // try to find the mob it's attached to (vehicle) or the nearest mob
        LivingEntity livingEntity = resolveLivingEntity(rightClicked);
        if (livingEntity == null) {
            if (debug) logger.info("[Taming-Debug] Skipped: not a LivingEntity and no mob found nearby");
            return;
        }

        if (debug && livingEntity != rightClicked) {
            logger.info("[Taming-Debug] Resolved nametag entity -> actual mob: "
                    + livingEntity.getType().name());
        }

        // Skip MyPet entities that were resolved from nametag
        if (livingEntity instanceof MyPetBukkitEntity) {
            if (debug) logger.info("[Taming-Debug] Skipped: resolved entity is MyPetBukkitEntity");
            return;
        }

        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();

        if (debug) {
            logger.info("[Taming-Debug] Target: " + livingEntity.getType().name()
                    + ", Item: " + mainHand.getType().name()
                    + ", hasAI=" + livingEntity.hasAI()
                    + ", invulnerable=" + livingEntity.isInvulnerable());
        }

        // Delegate to TamingManager - try animal first, then monster
        boolean handled = tamingManager.handleAnimalInteract(player, livingEntity, mainHand);
        if (!handled) {
            handled = tamingManager.handleMonsterInteract(player, livingEntity, mainHand);
        }

        if (debug) {
            logger.info("[Taming-Debug] Result: handled=" + handled);
        }

        if (handled) {
            event.setCancelled(true);
        } else {
            // If the held item is a taming item but the mob can't be tamed,
            // cancel the event to prevent vanilla mechanics from consuming it.
            // This prevents items like golden apples being wasted on hostile mobs.
            if (isTamingItem(mainHand)) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Resolves the actual LivingEntity from a clicked entity.
     * If the clicked entity is a nametag (TextDisplay, ArmorStand, etc.) used by
     * LevelledMobs or similar plugins, resolves to the actual mob underneath.
     */
    @Nullable
    private LivingEntity resolveLivingEntity(@NotNull Entity clicked) {
        // Direct hit on a LivingEntity
        if (clicked instanceof LivingEntity living) {
            return living;
        }

        // Check if this entity is riding (passenger of) a LivingEntity
        Entity vehicle = clicked.getVehicle();
        if (vehicle instanceof LivingEntity livingVehicle) {
            return livingVehicle;
        }

        // Fallback: find the nearest LivingEntity within 1.5 blocks
        // (covers cases where nametag entities are positioned above but not riding)
        LivingEntity nearest = null;
        double nearestDist = 2.25; // 1.5^2
        for (Entity nearby : clicked.getNearbyEntities(1.5, 2.0, 1.5)) {
            if (nearby instanceof LivingEntity living
                    && !(nearby instanceof Player)
                    && nearby.isValid()) {
                double dist = nearby.getLocation().distanceSquared(clicked.getLocation());
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = living;
                }
            }
        }
        return nearest;
    }

    /**
     * Checks if the given item matches any configured taming item (animal or monster).
     */
    private boolean isTamingItem(@NotNull ItemStack item) {
        if (item.isEmpty()) {
            return false;
        }
        ConfigManager cm = plugin.getConfigManager();
        for (ConfigManager.TamingItemEntry entry : cm.getTamingAnimalItems()) {
            if (ItemMatcher.matches(item, entry.itemDescriptor(), logger)) {
                return true;
            }
        }
        for (ConfigManager.TamingItemEntry entry : cm.getTamingMonsterItems()) {
            if (ItemMatcher.matches(item, entry.itemDescriptor(), logger)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onEntityDeath(@NotNull EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();

        // Skip MythicMobs entities
        if (mythicMobsIntegration != null && mythicMobsIntegration.isMythicMob(entity)) {
            return;
        }

        // Find the player who registered this entity for taming
        // (no longer requires the killing blow - any death cause triggers taming)
        Player tamingPlayer = tamingManager.findPendingTameOwner(entity);
        if (tamingPlayer == null) {
            return;
        }

        boolean handled = tamingManager.handleMonsterDeath(entity, tamingPlayer);
        if (handled) {
            // Clear drops and exp when taming succeeds
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

}
