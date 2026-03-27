package com.mypetaddon.equipment;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.stats.StatsManager;
import de.Keyle.MyPet.MyPetApi;
import de.Keyle.MyPet.api.entity.MyPet;
import de.Keyle.MyPet.api.player.MyPetPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Listens for equipment GUI close events and triggers stat recalculation
 * when equipment changes are detected.
 */
public final class EquipmentListener implements Listener {

    private static final String GUI_TITLE = "\u00a78\u00a7l\u30da\u30c3\u30c8\u88c5\u5099";

    private final MyPetAddonPlugin plugin;
    private final PetDataCache petDataCache;
    private final StatsManager statsManager;
    private final Logger logger;

    public EquipmentListener(@NotNull MyPetAddonPlugin plugin,
                              @NotNull PetDataCache petDataCache,
                              @NotNull StatsManager statsManager) {
        this.plugin = plugin;
        this.petDataCache = petDataCache;
        this.statsManager = statsManager;
        this.logger = plugin.getLogger();
    }

    /**
     * When the equipment GUI is closed, schedule a stat recalculation
     * for the player's active pet on the next tick.
     * This runs after EquipmentGUI's close handler has persisted the changes.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEquipmentGUIClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        String title = event.getView().getTitle();
        if (!GUI_TITLE.equals(title)) {
            return;
        }

        // Recalculate stats on next tick (equipment cache is already updated synchronously)
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (!player.isOnline()) {
                    return;
                }
                if (!MyPetApi.getPlayerManager().isMyPetPlayer(player)) {
                    return;
                }

                MyPetPlayer myPetPlayer = MyPetApi.getPlayerManager().getMyPetPlayer(player);
                if (!myPetPlayer.hasMyPet()) {
                    return;
                }

                MyPet myPet = myPetPlayer.getMyPet();
                PetData petData = petDataCache.get(myPet.getUUID());
                if (petData == null) {
                    return;
                }

                statsManager.applyStats(myPet);
                logger.fine("[Equipment] Recalculated stats for pet " + petData.addonPetId()
                        + " after equipment change.");
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "[Equipment] Failed to recalculate stats after equipment change", e);
            }
        });
    }
}
