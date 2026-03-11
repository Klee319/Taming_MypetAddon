package com.mypetaddon.taming;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.integration.MythicMobsIntegration;
import de.Keyle.MyPet.api.entity.MyPetBukkitEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Event listener that delegates taming interactions to {@link TamingManager}.
 * Handles player-entity interactions (right-click) and entity death events.
 */
public final class TamingListener implements Listener {

    private final MyPetAddonPlugin plugin;
    private final TamingManager tamingManager;
    private final MythicMobsIntegration mythicMobsIntegration;

    public TamingListener(@NotNull MyPetAddonPlugin plugin,
                          @NotNull TamingManager tamingManager) {
        this.plugin = plugin;
        this.tamingManager = tamingManager;
        this.mythicMobsIntegration = resolveMythicMobs(plugin);
    }

    @EventHandler
    public void onPlayerInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        // Only handle main hand interactions
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Entity rightClicked = event.getRightClicked();

        // Skip MythicMobs entities
        if (mythicMobsIntegration != null && mythicMobsIntegration.isMythicMob(rightClicked)) {
            return;
        }

        // Skip MyPet entities (don't interfere with MyPet clicks)
        if (rightClicked instanceof MyPetBukkitEntity) {
            return;
        }

        // Only handle living entities
        if (!(rightClicked instanceof LivingEntity livingEntity)) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();

        // Delegate to TamingManager - try animal first, then monster
        boolean handled = tamingManager.handleAnimalInteract(player, livingEntity, mainHand);
        if (!handled) {
            handled = tamingManager.handleMonsterInteract(player, livingEntity, mainHand);
        }

        if (handled) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDeath(@NotNull EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();

        // Skip MythicMobs entities
        if (mythicMobsIntegration != null && mythicMobsIntegration.isMythicMob(entity)) {
            return;
        }

        // Only process if killed by a player
        Player killer = entity.getKiller();
        if (killer == null) {
            return;
        }

        boolean handled = tamingManager.handleMonsterDeath(entity, killer);
        if (handled) {
            // Clear drops and exp when taming succeeds
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    // ─── Internal ────────────────────────────────────────────────

    /**
     * Resolves the MythicMobs integration from the plugin's private field.
     * The plugin class declares the field but does not expose a public getter.
     */
    @Nullable
    private static MythicMobsIntegration resolveMythicMobs(@NotNull MyPetAddonPlugin plugin) {
        try {
            var field = MyPetAddonPlugin.class.getDeclaredField("mythicMobsIntegration");
            field.setAccessible(true);
            return (MythicMobsIntegration) field.get(plugin);
        } catch (Exception e) {
            return null;
        }
    }
}
