package com.mypetaddon.evolution;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.PetStats;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.evolution.EvolutionManager.EvolutionCheck;
import de.Keyle.MyPet.api.entity.MyPet;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple confirmation GUI displayed before evolving a pet.
 * Shows current pet info on the left, an arrow in the middle,
 * and the evolved form on the right with stat bonuses.
 */
public final class EvolutionGUI implements Listener {

    private static final String GUI_TITLE = "§5§l進化確認";
    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;

    private static final int SLOT_CURRENT_PET = 10;
    private static final int SLOT_ARROW = 13;
    private static final int SLOT_EVOLVED_PET = 16;
    private static final int SLOT_CONFIRM = 12;
    private static final int SLOT_CANCEL = 14;

    private final MyPetAddonPlugin plugin;
    private final EvolutionManager evolutionManager;
    private final PetDataCache petDataCache;

    /** Player UUID -> data needed for evolution on confirm click. */
    private final Map<UUID, PendingEvolution> pendingEvolutions = new ConcurrentHashMap<>();

    /**
     * Holds the data associated with an open evolution GUI.
     */
    private record PendingEvolution(
            @NotNull MyPet myPet,
            @NotNull EvolutionCheck check,
            @NotNull Inventory inventory
    ) {}

    public EvolutionGUI(@NotNull MyPetAddonPlugin plugin,
                        @NotNull EvolutionManager evolutionManager,
                        @NotNull PetDataCache petDataCache) {
        this.plugin = plugin;
        this.evolutionManager = evolutionManager;
        this.petDataCache = petDataCache;
    }

    // ─── Open GUI ───────────────────────────────────────────────

    /**
     * Opens the evolution confirmation GUI for a player.
     *
     * @param player the player viewing the GUI
     * @param myPet  the MyPet being evolved
     * @param check  the evolution check result (must be canEvolve == true)
     */
    public void openConfirmation(@NotNull Player player,
                                 @NotNull MyPet myPet,
                                 @NotNull EvolutionCheck check) {
        Inventory gui = Bukkit.createInventory(null, SIZE, GUI_TITLE);

        // Fill background with gray glass panes
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < SIZE; i++) {
            gui.setItem(i, filler);
        }

        // Current pet info (left side)
        PetData petData = petDataCache.get(myPet.getUUID());
        PetStats petStats = petData != null
                ? petDataCache.getStats(petData.addonPetId()) : null;

        gui.setItem(SLOT_CURRENT_PET, buildCurrentPetItem(myPet, petData, petStats));

        // Arrow (middle)
        gui.setItem(SLOT_ARROW, createArrowItem());

        // Evolved form (right side)
        gui.setItem(SLOT_EVOLVED_PET, buildEvolvedPetItem(check, myPet));

        // Confirm button (green)
        gui.setItem(SLOT_CONFIRM, createItem(Material.LIME_CONCRETE, "§a§l進化する"));

        // Cancel button (red)
        gui.setItem(SLOT_CANCEL, createItem(Material.RED_CONCRETE, "§c§lキャンセル"));

        // Store pending data
        pendingEvolutions.put(player.getUniqueId(),
                new PendingEvolution(myPet, check, gui));

        player.openInventory(gui);
    }

    // ─── Event Handlers ─────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        PendingEvolution pending = pendingEvolutions.get(player.getUniqueId());
        if (pending == null) {
            return;
        }

        // Verify this is the correct inventory
        if (!event.getInventory().equals(pending.inventory())) {
            return;
        }

        // Cancel all clicks to prevent item theft
        event.setCancelled(true);

        int slot = event.getRawSlot();

        if (slot == SLOT_CONFIRM) {
            // Close GUI and execute evolution
            player.closeInventory();
            pendingEvolutions.remove(player.getUniqueId());

            // Execute on next tick to avoid inventory close issues
            Bukkit.getScheduler().runTask(plugin, () ->
                    evolutionManager.evolve(player, pending.myPet()));

        } else if (slot == SLOT_CANCEL) {
            player.closeInventory();
            pendingEvolutions.remove(player.getUniqueId());
            player.sendMessage("§7進化をキャンセルしました。");
        }
    }

    @EventHandler
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        PendingEvolution pending = pendingEvolutions.get(player.getUniqueId());
        if (pending != null && event.getInventory().equals(pending.inventory())) {
            pendingEvolutions.remove(player.getUniqueId());
        }
    }

    // ─── Item Builders ──────────────────────────────────────────

    /**
     * Builds the item representing the current pet.
     */
    @NotNull
    private ItemStack buildCurrentPetItem(@NotNull MyPet myPet,
                                          @NotNull PetData petData,
                                          @NotNull PetStats petStats) {
        Material headMaterial = mobTypeToSkull(petData != null ? petData.mobType() : "UNKNOWN");
        ItemStack item = new ItemStack(headMaterial);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName("§e§l現在のペット");

            List<String> lore = new ArrayList<>();
            lore.add("§7名前: §f" + myPet.getPetName());

            if (petData != null) {
                lore.add("§7種類: §f" + petData.mobType());
                lore.add("§7レアリティ: " + petData.rarity().getColoredName());
            }

            lore.add("§7レベル: §f" + myPet.getExperience().getLevel());

            if (petStats != null) {
                lore.add("");
                lore.add("§6§lステータス:");
                for (Map.Entry<String, Double> entry : petStats.baseValues().entrySet()) {
                    double effective = petStats.getEffective(entry.getKey());
                    lore.add("§7  " + entry.getKey() + ": §f" + String.format("%.1f", effective));
                }
            }

            meta.setLore(lore);
            item.setItemMeta(meta);
        }

        return item;
    }

    /**
     * Builds the item representing the evolved pet form.
     */
    @NotNull
    private ItemStack buildEvolvedPetItem(@NotNull EvolutionCheck check,
                                          @NotNull MyPet myPet) {
        Material headMaterial = mobTypeToSkull(check.targetType());
        ItemStack item = new ItemStack(headMaterial);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName("§a§l進化後のペット");

            List<String> lore = new ArrayList<>();
            lore.add("§7名前: §f" + myPet.getPetName());
            lore.add("§7種類: §a" + check.targetType());
            lore.add("");

            if (!check.statBonus().isEmpty()) {
                lore.add("§6§lステータスボーナス:");
                for (Map.Entry<String, Double> bonus : check.statBonus().entrySet()) {
                    double percent = (bonus.getValue() - 1.0) * 100.0;
                    lore.add("§7  " + bonus.getKey() + ": §a+"
                            + String.format("%.0f%%", percent));
                }
            } else {
                lore.add("§7ステータスボーナスなし");
            }

            meta.setLore(lore);
            item.setItemMeta(meta);
        }

        return item;
    }

    /**
     * Creates the arrow item for the middle slot.
     */
    @NotNull
    private ItemStack createArrowItem() {
        return createItem(Material.ARROW, "§e§l→ 進化 →");
    }

    /**
     * Creates a simple named ItemStack.
     */
    @NotNull
    private ItemStack createItem(@NotNull Material material, @NotNull String displayName) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Maps a mob type name to a representative Material for display.
     * Falls back to a generic skeleton skull for unknown types.
     */
    @NotNull
    private Material mobTypeToSkull(@NotNull String mobType) {
        return switch (mobType.toUpperCase()) {
            case "ZOMBIE", "ZOMBIE_VILLAGER" -> Material.ZOMBIE_HEAD;
            case "SKELETON", "STRAY" -> Material.SKELETON_SKULL;
            case "CREEPER" -> Material.CREEPER_HEAD;
            case "ENDER_DRAGON" -> Material.DRAGON_HEAD;
            case "PIGLIN", "PIGLIN_BRUTE", "ZOMBIFIED_PIGLIN" -> Material.PIGLIN_HEAD;
            case "WITHER_SKELETON" -> Material.WITHER_SKELETON_SKULL;
            default -> Material.PLAYER_HEAD;
        };
    }
}
