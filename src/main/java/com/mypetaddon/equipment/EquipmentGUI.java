package com.mypetaddon.equipment;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.cache.PetDataCache;
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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Inventory GUI for managing pet equipment (3 slots: HELMET, CHEST, ACCESSORY).
 * Handles item equipping/unequipping with anti-duplication safeguards.
 */
public final class EquipmentGUI implements Listener {

    private static final String GUI_TITLE = "\u00a78\u00a7l\u30da\u30c3\u30c8\u88c5\u5099";
    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;

    private static final int SLOT_HELMET = 10;
    private static final int SLOT_CHEST = 13;
    private static final int SLOT_ACCESSORY = 16;
    private static final int SLOT_CLOSE = 22;

    /** Maps GUI inventory slot index to equipment slot name (must match EquipmentManager.VALID_SLOTS). */
    private static final Map<Integer, String> SLOT_MAP = Map.of(
            SLOT_HELMET, "HEAD",
            SLOT_CHEST, "CHEST",
            SLOT_ACCESSORY, "ACCESSORY"
    );

    private final MyPetAddonPlugin plugin;
    private final EquipmentManager equipmentManager;
    private final PetDataCache petDataCache;
    private final Logger logger;

    /** Player UUID -> session data for the currently open GUI. */
    private final Map<UUID, EquipmentSession> activeSessions = new ConcurrentHashMap<>();

    /**
     * Holds transient state for one GUI session.
     */
    private record EquipmentSession(
            @NotNull UUID addonPetId,
            int bondLevel,
            @NotNull Inventory inventory,
            @NotNull Map<String, ItemStack> originalEquipment
    ) {}

    public EquipmentGUI(@NotNull MyPetAddonPlugin plugin,
                         @NotNull EquipmentManager equipmentManager,
                         @NotNull PetDataCache petDataCache) {
        this.plugin = plugin;
        this.equipmentManager = equipmentManager;
        this.petDataCache = petDataCache;
        this.logger = plugin.getLogger();
    }

    // ─── Open GUI ───────────────────────────────────────────────

    /**
     * Opens the equipment GUI for the given player and pet.
     *
     * @param player  the player viewing the GUI
     * @param petData the pet whose equipment to manage
     */
    public void openEquipmentGUI(@NotNull Player player, @NotNull PetData petData) {
        Inventory gui = Bukkit.createInventory(null, SIZE, GUI_TITLE);
        ConfigManager cfg = plugin.getConfigManager();
        int bondLevel = petData.bondLevel();

        // Fill border with black stained glass panes
        ItemStack border = createItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < SIZE; i++) {
            gui.setItem(i, border);
        }

        // Load current equipment from DB (async caller should have ensured data is ready)
        Map<String, ItemStack> equipped = equipmentManager.getEquipment(petData.addonPetId());

        // Set each equipment slot (or locked/placeholder)
        for (Map.Entry<Integer, String> entry : SLOT_MAP.entrySet()) {
            int guiSlot = entry.getKey();
            String slotName = entry.getValue();
            int required = cfg.getEquipmentSlotRequiredBondLevel(slotName);

            if (bondLevel < required) {
                // Locked slot
                gui.setItem(guiSlot, createLockedPlaceholder(slotName, required));
            } else if (equipped.containsKey(slotName)) {
                gui.setItem(guiSlot, equipped.get(slotName));
            } else {
                gui.setItem(guiSlot, createPlaceholderForSlot(slotName));
            }
        }

        // Close button (22)
        gui.setItem(SLOT_CLOSE, createItem(Material.BARRIER, "\u00a7c\u00a7l\u9589\u3058\u308b"));

        // Store session
        activeSessions.put(player.getUniqueId(),
                new EquipmentSession(petData.addonPetId(), bondLevel, gui, Map.copyOf(equipped)));

        player.openInventory(gui);
    }

    // ─── Event Handlers ─────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        EquipmentSession session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        // Verify this is the correct inventory
        if (!event.getInventory().equals(session.inventory())) {
            return;
        }

        int rawSlot = event.getRawSlot();

        // Allow normal interaction with the player's own inventory (bottom)
        // so they can pick up items onto the cursor for equipping
        if (rawSlot >= SIZE) {
            return;
        }

        // Cancel clicks in the GUI (top inventory) to prevent direct item manipulation
        event.setCancelled(true);

        // Ignore clicks outside valid range
        if (rawSlot < 0) {
            return;
        }

        // Close button
        if (rawSlot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }

        // Check if this is an equipment slot
        String slotName = SLOT_MAP.get(rawSlot);
        if (slotName == null) {
            // Clicked a border slot; do nothing
            return;
        }

        handleEquipmentSlotClick(player, session, rawSlot, slotName);
    }

    @EventHandler
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        EquipmentSession session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        if (!event.getInventory().equals(session.inventory())) {
            return;
        }

        activeSessions.remove(player.getUniqueId());

        // Save current equipment state async
        UUID addonPetId = session.addonPetId();
        Inventory gui = session.inventory();

        // Snapshot what's currently in the GUI slots
        Map<String, ItemStack> currentItems = new java.util.LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : SLOT_MAP.entrySet()) {
            ItemStack guiItem = gui.getItem(entry.getKey());
            if (guiItem != null && !isPlaceholder(guiItem) && !isBorder(guiItem) && !isLocked(guiItem)) {
                currentItems.put(entry.getValue(), guiItem.clone());
            }
        }

        // Persist changes on async thread
        Map<String, ItemStack> originalEquipment = session.originalEquipment();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Remove slots that were unequipped
                for (String slot : EquipmentManager.VALID_SLOTS) {
                    boolean wasEquipped = originalEquipment.containsKey(slot);
                    boolean isEquipped = currentItems.containsKey(slot);

                    if (wasEquipped && !isEquipped) {
                        // Item was removed during session (already returned to player)
                        // Just delete from DB
                        equipmentManager.unequipItem(addonPetId, slot);
                    } else if (isEquipped) {
                        // Equip or update the item
                        equipmentManager.equipItem(addonPetId, slot, currentItems.get(slot));
                    }
                }
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "[Equipment] Failed to save equipment on GUI close for pet " + addonPetId, e);
            }
        });
    }

    // ─── Slot Click Logic ───────────────────────────────────────

    /**
     * Handles a click on an equipment slot.
     * If the slot has a real item, unequip it to the player's inventory.
     * If the slot is a placeholder and the player has a cursor item, equip it.
     */
    private void handleEquipmentSlotClick(@NotNull Player player,
                                           @NotNull EquipmentSession session,
                                           int guiSlot,
                                           @NotNull String slotName) {
        // Check bond level requirement
        int required = plugin.getConfigManager().getEquipmentSlotRequiredBondLevel(slotName);
        if (session.bondLevel() < required) {
            player.sendMessage("\u00a7c\u00a7l\u203c \u00a7c\u89aa\u5bc6\u5ea6Lv." + required
                    + " \u304c\u5fc5\u8981\u3067\u3059\u3002");
            return;
        }

        Inventory gui = session.inventory();
        ItemStack slotItem = gui.getItem(guiSlot);
        ItemStack cursorItem = player.getItemOnCursor();

        boolean slotIsEmpty = slotItem == null || isPlaceholder(slotItem) || isBorder(slotItem) || isLocked(slotItem);
        boolean hasCursor = cursorItem != null && cursorItem.getType() != Material.AIR;

        if (!slotIsEmpty && !hasCursor) {
            // Unequip: move item to player inventory
            ItemStack toReturn = slotItem.clone();
            gui.setItem(guiSlot, createPlaceholderForSlot(slotName));

            // Give item back to player
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(toReturn);
            if (!overflow.isEmpty()) {
                // Drop overflow at player's feet
                for (ItemStack dropped : overflow.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), dropped);
                }
            }

            player.sendMessage("\u00a77\u88c5\u5099\u3092\u5916\u3057\u307e\u3057\u305f\u3002");

        } else if (slotIsEmpty && hasCursor) {
            // Equip: place cursor item into the slot
            ItemStack toEquip = cursorItem.clone();
            toEquip.setAmount(1);

            gui.setItem(guiSlot, toEquip);

            // Reduce cursor by 1
            if (cursorItem.getAmount() > 1) {
                ItemStack remaining = cursorItem.clone();
                remaining.setAmount(cursorItem.getAmount() - 1);
                player.setItemOnCursor(remaining);
            } else {
                player.setItemOnCursor(null);
            }

            player.sendMessage("\u00a7a\u88c5\u5099\u3057\u307e\u3057\u305f\uff01");

        } else if (!slotIsEmpty && hasCursor) {
            // Swap: return current item, place cursor item
            ItemStack toReturn = slotItem.clone();
            ItemStack toEquip = cursorItem.clone();
            toEquip.setAmount(1);

            gui.setItem(guiSlot, toEquip);

            // Return old item + remaining cursor to player
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(toReturn);
            for (ItemStack dropped : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), dropped);
            }

            if (cursorItem.getAmount() > 1) {
                ItemStack remaining = cursorItem.clone();
                remaining.setAmount(cursorItem.getAmount() - 1);
                player.setItemOnCursor(remaining);
            } else {
                player.setItemOnCursor(null);
            }

            player.sendMessage("\u00a7a\u88c5\u5099\u3092\u5165\u308c\u66ff\u3048\u307e\u3057\u305f\uff01");
        }
    }

    // ─── Item Builders ──────────────────────────────────────────

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
     * Creates a placeholder item with a name and a single lore line.
     */
    @NotNull
    private ItemStack createPlaceholder(@NotNull Material material,
                                         @NotNull String displayName,
                                         @NotNull String loreLine) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            meta.setLore(List.of(loreLine));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Creates the appropriate placeholder for a given slot name.
     */
    @NotNull
    private ItemStack createPlaceholderForSlot(@NotNull String slotName) {
        String displayName = switch (slotName) {
            case "HEAD" -> "\u00a77\u30d8\u30eb\u30e1\u30c3\u30c8\u30b9\u30ed\u30c3\u30c8";
            case "CHEST" -> "\u00a77\u30c1\u30a7\u30b9\u30c8\u30b9\u30ed\u30c3\u30c8";
            case "ACCESSORY" -> "\u00a77\u30a2\u30af\u30bb\u30b5\u30ea\u30fc\u30b9\u30ed\u30c3\u30c8";
            default -> "\u00a77\u30b9\u30ed\u30c3\u30c8";
        };
        return createPlaceholder(Material.GRAY_STAINED_GLASS_PANE, displayName,
                "\u00a78\u30a2\u30a4\u30c6\u30e0\u3092\u30af\u30ea\u30c3\u30af\u3057\u3066\u88c5\u5099");
    }

    /**
     * Creates a locked slot indicator showing the required bond level.
     */
    @NotNull
    private ItemStack createLockedPlaceholder(@NotNull String slotName, int requiredLevel) {
        String displayName = switch (slotName) {
            case "HEAD" -> "\u00a77\u30d8\u30eb\u30e1\u30c3\u30c8\u30b9\u30ed\u30c3\u30c8";
            case "CHEST" -> "\u00a77\u30c1\u30a7\u30b9\u30c8\u30b9\u30ed\u30c3\u30c8";
            case "ACCESSORY" -> "\u00a77\u30a2\u30af\u30bb\u30b5\u30ea\u30fc\u30b9\u30ed\u30c3\u30c8";
            default -> "\u00a77\u30b9\u30ed\u30c3\u30c8";
        };
        String bondLabel = switch (requiredLevel) {
            case 1 -> "\u521d\u5bfe\u9762";
            case 2 -> "\u77e5\u4eba";
            case 3 -> "\u4ef2\u9593";
            case 4 -> "\u4fe1\u983c";
            case 5 -> "\u9b42\u306e\u7d46";
            default -> "Lv." + requiredLevel;
        };
        ItemStack item = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("\u00a7c\u00a7l\ud83d\udd12 " + displayName);
            meta.setLore(List.of(
                    "\u00a7c\u30ed\u30c3\u30af\u4e2d",
                    "",
                    "\u00a77\u89e3\u653e\u6761\u4ef6: \u00a7e\u89aa\u5bc6\u5ea6 " + bondLabel
                            + " \u00a77(Lv." + requiredLevel + ")"
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Checks if an ItemStack is a locked placeholder (red stained glass pane).
     */
    private boolean isLocked(@NotNull ItemStack item) {
        return item.getType() == Material.RED_STAINED_GLASS_PANE;
    }

    /**
     * Checks if an ItemStack is a placeholder (gray stained glass pane with lore).
     */
    private boolean isPlaceholder(@NotNull ItemStack item) {
        if (item.getType() != Material.GRAY_STAINED_GLASS_PANE) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasLore();
    }

    /**
     * Checks if an ItemStack is a border pane (black stained glass pane with display name " ").
     */
    private boolean isBorder(@NotNull ItemStack item) {
        if (item.getType() != Material.BLACK_STAINED_GLASS_PANE) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && " ".equals(meta.getDisplayName());
    }
}
