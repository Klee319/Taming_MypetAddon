package com.mypetaddon.encyclopedia;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.EncyclopediaRepository.EncyclopediaEntry;
import com.mypetaddon.rarity.Rarity;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.mypetaddon.util.MobNameTranslator;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Paginated inventory GUI for the encyclopedia (Pokedex) system.
 * Shows all tameable mobs and taming status per player.
 */
public final class EncyclopediaGUI implements Listener {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9; // 54 slots
    private static final int ENTRIES_PER_PAGE = 45; // rows 1-5
    private static final int SLOT_PREV = 45;
    private static final int SLOT_COMPLETION = 48;
    private static final int SLOT_CLOSE = 49;
    private static final int SLOT_NEXT = 53;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault());

    private final MyPetAddonPlugin plugin;
    private final EncyclopediaManager manager;
    private final ConfigManager configManager;

    /** Tracks open GUIs: player UUID -> session data. */
    private final Map<UUID, GuiSession> openSessions = new ConcurrentHashMap<>();

    private record GuiSession(@NotNull Inventory inventory, int page, int maxPages) {}

    public EncyclopediaGUI(@NotNull MyPetAddonPlugin plugin,
                           @NotNull EncyclopediaManager manager,
                           @NotNull ConfigManager configManager) {
        this.plugin = plugin;
        this.manager = manager;
        this.configManager = configManager;
    }

    // ─── Open Encyclopedia ──────────────────────────────────────

    /**
     * Opens the encyclopedia GUI for a player at the given page.
     * Data is loaded asynchronously, then the GUI is populated on the main thread.
     */
    public void openEncyclopedia(@NotNull Player player, int page) {
        UUID uuid = player.getUniqueId();

        // Load data async
        manager.getPlayerEntries(uuid).thenAcceptAsync(entries -> {
            double completionPercent = calculateCompletionPercent(entries);
            List<String> allTypes = manager.getTameableTypes();

            // Build display items on async thread
            List<DisplayEntry> displayEntries = buildDisplayEntries(allTypes, entries);

            // Sort according to config
            sortEntries(displayEntries);

            int maxPages = Math.max(1, (int) Math.ceil((double) displayEntries.size() / ENTRIES_PER_PAGE));
            int safePage = Math.max(1, Math.min(page, maxPages));

            // Switch to main thread for inventory operations
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                populateAndOpen(player, displayEntries, safePage, maxPages, completionPercent);
            });
        });
    }

    /**
     * Creates the inventory, populates items, and opens it for the player.
     * Must be called on the main thread.
     */
    private void populateAndOpen(@NotNull Player player,
                                 @NotNull List<DisplayEntry> displayEntries,
                                 int page, int maxPages, double completionPercent) {
        String title = "§8§lペット図鑑 §7- Page " + page + "/" + maxPages;
        Inventory gui = Bukkit.createInventory(null, SIZE, title);

        // Fill bottom row with glass panes
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = ENTRIES_PER_PAGE; i < SIZE; i++) {
            gui.setItem(i, filler);
        }

        // Populate mob entries for this page
        int startIndex = (page - 1) * ENTRIES_PER_PAGE;
        int endIndex = Math.min(startIndex + ENTRIES_PER_PAGE, displayEntries.size());

        for (int i = startIndex; i < endIndex; i++) {
            gui.setItem(i - startIndex, displayEntries.get(i).toItemStack());
        }

        // Navigation buttons
        if (page > 1) {
            gui.setItem(SLOT_PREV, createItem(Material.ARROW, "§e← 前のページ"));
        }
        if (page < maxPages) {
            gui.setItem(SLOT_NEXT, createItem(Material.ARROW, "§e次のページ →"));
        }

        // Completion info
        gui.setItem(SLOT_COMPLETION, buildCompletionItem(completionPercent, displayEntries.size()));

        // Close button
        gui.setItem(SLOT_CLOSE, createItem(Material.BARRIER, "§c閉じる"));

        // Track session
        openSessions.put(player.getUniqueId(), new GuiSession(gui, page, maxPages));

        player.openInventory(gui);

        // Check and grant rewards on main thread
        manager.checkAndGrantRewards(player, completionPercent);
    }

    // ─── Event Handlers ─────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        GuiSession session = openSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        if (!event.getInventory().equals(session.inventory())) {
            return;
        }

        // Cancel all clicks (view-only GUI)
        event.setCancelled(true);

        int slot = event.getRawSlot();

        if (slot == SLOT_PREV && session.page() > 1) {
            player.closeInventory();
            openEncyclopedia(player, session.page() - 1);
        } else if (slot == SLOT_NEXT && session.page() < session.maxPages()) {
            player.closeInventory();
            openEncyclopedia(player, session.page() + 1);
        } else if (slot == SLOT_CLOSE) {
            player.closeInventory();
        }
    }

    @EventHandler
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        GuiSession session = openSessions.get(player.getUniqueId());
        if (session != null && event.getInventory().equals(session.inventory())) {
            openSessions.remove(player.getUniqueId());
        }
    }

    // ─── Display Entry Building ─────────────────────────────────

    /**
     * Combines all tameable types with player entries to build display data.
     */
    @NotNull
    private List<DisplayEntry> buildDisplayEntries(@NotNull List<String> allTypes,
                                                   @NotNull List<EncyclopediaEntry> playerEntries) {
        Map<String, EncyclopediaEntry> entryMap = playerEntries.stream()
                .collect(Collectors.toMap(EncyclopediaEntry::mobType, Function.identity()));

        List<DisplayEntry> result = new ArrayList<>(allTypes.size());
        for (String type : allTypes) {
            EncyclopediaEntry entry = entryMap.get(type);
            result.add(new DisplayEntry(type, entry));
        }
        return result;
    }

    /**
     * Sorts display entries based on the configured sort mode.
     */
    private void sortEntries(@NotNull List<DisplayEntry> entries) {
        String sortMode = configManager.getString("encyclopedia.gui.sort", "NAME").toUpperCase(Locale.ROOT);

        switch (sortMode) {
            case "RARITY", "BY_RARITY" -> entries.sort(
                    Comparator
                            // Tamed first
                            .comparing((DisplayEntry e) -> !e.isTamed())
                            // Then by highest rarity descending
                            .thenComparing(e -> e.isTamed() ? -e.entry().highestRarity().ordinal() : 0)
                            // Then alphabetical
                            .thenComparing(DisplayEntry::mobType)
            );
            case "TAME_COUNT", "BY_TAME_COUNT" -> entries.sort(
                    Comparator
                            // Most tamed first
                            .comparingInt((DisplayEntry e) -> e.isTamed() ? -e.entry().tameCount() : 0)
                            // Then alphabetical
                            .thenComparing(DisplayEntry::mobType)
            );
            default -> // NAME / ALPHABETICAL: sort by mob type name
                    entries.sort(Comparator.comparing(DisplayEntry::mobType));
        }
    }

    /**
     * Calculates completion percentage from entries vs total tameable types.
     */
    private double calculateCompletionPercent(@NotNull List<EncyclopediaEntry> entries) {
        int totalTypes = manager.getTameableTypes().size();
        if (totalTypes <= 0) {
            return 0.0;
        }
        return (entries.size() * 100.0) / totalTypes;
    }

    // ─── Item Builders ──────────────────────────────────────────

    /**
     * Builds the completion info item (book).
     */
    @NotNull
    private ItemStack buildCompletionItem(double completionPercent, int totalDisplayed) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§e§l図鑑情報");

            List<String> lore = new ArrayList<>();
            lore.add("§7コンプリート率: §f" + String.format("%.1f", completionPercent) + "%%");
            lore.add("§7登録種類数: §f" + totalDisplayed);
            lore.add("");

            if (completionPercent >= 100.0) {
                lore.add("§6§l全種コンプリート！");
            } else {
                int nextThreshold = getNextThreshold(completionPercent);
                if (nextThreshold > 0) {
                    lore.add("§7次の報酬: §f" + nextThreshold + "%%達成");
                }
            }

            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Returns the next reward threshold the player hasn't reached yet.
     */
    private int getNextThreshold(double currentPercent) {
        int[] thresholds = {25, 50, 75, 100};
        for (int t : thresholds) {
            if (currentPercent < t) {
                return t;
            }
        }
        return 0;
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

    // ─── Mob Type -> Spawn Egg Mapping ──────────────────────────

    /**
     * Maps a mob type name to the corresponding spawn egg Material.
     * Falls back to GHAST_SPAWN_EGG for unknown types.
     */
    @NotNull
    private static Material mobTypeToSpawnEgg(@NotNull String mobType) {
        String eggName = mobType.toUpperCase(Locale.ROOT) + "_SPAWN_EGG";
        try {
            return Material.valueOf(eggName);
        } catch (IllegalArgumentException e) {
            return Material.GHAST_SPAWN_EGG;
        }
    }

    // ─── Inner Display Entry ────────────────────────────────────

    /**
     * Combines a mob type with its optional encyclopedia entry for display purposes.
     */
    private record DisplayEntry(@NotNull String mobType, @Nullable EncyclopediaEntry entry) {

        boolean isTamed() {
            return entry != null;
        }

        /**
         * Builds the ItemStack representation for this entry.
         */
        @NotNull
        ItemStack toItemStack() {
            if (isTamed()) {
                return buildTamedItem();
            }
            return buildUntamedItem();
        }

        @NotNull
        private ItemStack buildTamedItem() {
            Material material = mobTypeToSpawnEgg(mobType);
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();

            if (meta != null) {
                Rarity rarity = entry.highestRarity();
                meta.setDisplayName("§a" + MobNameTranslator.translate(mobType) + " " + rarity.getColoredName());

                List<String> lore = new ArrayList<>();
                lore.add("§7テイム回数: §f" + entry.tameCount() + "x");
                lore.add("§7最高レアリティ: " + rarity.getColoredName());
                lore.add("§7初テイム日: §f" + formatTimestamp(entry.firstTamedAt()));

                meta.setLore(lore);

                // Add enchant glow
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

                item.setItemMeta(meta);
            }
            return item;
        }

        @NotNull
        private ItemStack buildUntamedItem() {
            ItemStack item = new ItemStack(Material.GRAY_DYE);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§8???");
                meta.setLore(List.of("§7未テイム"));
                item.setItemMeta(meta);
            }
            return item;
        }

        @NotNull
        private static String formatTimestamp(long epochSeconds) {
            return DATE_FMT.format(Instant.ofEpochSecond(epochSeconds));
        }
    }
}
