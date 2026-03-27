package com.mypetaddon.evolution;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.PetStats;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.evolution.EvolutionManager.BranchCheck;
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
 * GUI for pet evolution: branch selection (when multiple branches) and confirmation.
 * Supports branching evolution paths where each branch shows conditions,
 * stat bonuses, and fixed skilltree info.
 */
public final class EvolutionGUI implements Listener {

    // ─── GUI Titles (used for identification) ────────────────────
    private static final String SELECTION_TITLE = "§5§l進化先を選択";
    private static final String CONFIRM_TITLE = "§5§l進化確認";

    // ─── Selection GUI Layout (4 rows = 36 slots) ────────────────
    private static final int SELECTION_ROWS = 4;
    private static final int SELECTION_SIZE = SELECTION_ROWS * 9;
    private static final int SELECTION_PET_SLOT = 4;       // top center: current pet
    private static final int SELECTION_CLOSE_SLOT = 31;    // bottom center: close button
    // Branch slots (centered in row 2, slots 9-17)
    private static final int[][] BRANCH_SLOTS = {
            {13},                   // 1 branch
            {11, 15},              // 2 branches
            {10, 13, 16},          // 3 branches
            {10, 12, 14, 16},      // 4 branches
    };

    // ─── Confirmation GUI Layout (3 rows = 27 slots) ─────────────
    private static final int CONFIRM_ROWS = 3;
    private static final int CONFIRM_SIZE = CONFIRM_ROWS * 9;
    private static final int SLOT_CURRENT_PET = 10;
    private static final int SLOT_ARROW = 13;
    private static final int SLOT_EVOLVED_PET = 16;
    private static final int SLOT_CONFIRM = 12;
    private static final int SLOT_CANCEL = 14;

    private final MyPetAddonPlugin plugin;
    private final EvolutionManager evolutionManager;
    private final PetDataCache petDataCache;

    /** Player UUID -> pending branch selection data. */
    private final Map<UUID, PendingSelection> pendingSelections = new ConcurrentHashMap<>();
    /** Player UUID -> pending confirmation data. */
    private final Map<UUID, PendingConfirmation> pendingConfirmations = new ConcurrentHashMap<>();

    private record PendingSelection(
            @NotNull MyPet myPet,
            @NotNull List<BranchCheck> branches,
            @NotNull Inventory inventory
    ) {}

    private record PendingConfirmation(
            @NotNull MyPet myPet,
            @NotNull BranchCheck selectedBranch,
            @NotNull Inventory inventory
    ) {}

    public EvolutionGUI(@NotNull MyPetAddonPlugin plugin,
                        @NotNull EvolutionManager evolutionManager,
                        @NotNull PetDataCache petDataCache) {
        this.plugin = plugin;
        this.evolutionManager = evolutionManager;
        this.petDataCache = petDataCache;
    }

    // ─── Branch Selection GUI ────────────────────────────────────

    /**
     * Opens the branch selection GUI showing all available evolution paths.
     *
     * @param player   the player
     * @param myPet    the pet to evolve
     * @param branches all branch checks (eligible and ineligible)
     */
    public void openBranchSelection(@NotNull Player player,
                                    @NotNull MyPet myPet,
                                    @NotNull List<BranchCheck> branches) {
        Inventory gui = Bukkit.createInventory(null, SELECTION_SIZE, SELECTION_TITLE);

        // Fill background
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < SELECTION_SIZE; i++) {
            gui.setItem(i, filler);
        }

        // Current pet info (top center)
        PetData petData = petDataCache.get(myPet.getUUID());
        PetStats petStats = petData != null ? petDataCache.getStats(petData.addonPetId()) : null;
        if (petData != null && petStats != null) {
            gui.setItem(SELECTION_PET_SLOT, buildCurrentPetItem(myPet, petData, petStats));
        }

        // Branch items
        int branchCount = Math.min(branches.size(), 4);
        int[] slots = BRANCH_SLOTS[branchCount - 1];
        for (int i = 0; i < branchCount; i++) {
            BranchCheck check = branches.get(i);
            gui.setItem(slots[i], buildBranchItem(check, myPet));
        }

        // Close button
        gui.setItem(SELECTION_CLOSE_SLOT, createItem(Material.BARRIER, "§c§l閉じる"));

        pendingSelections.put(player.getUniqueId(), new PendingSelection(myPet, branches, gui));
        player.openInventory(gui);
    }

    // ─── Confirmation GUI ────────────────────────────────────────

    /**
     * Opens the confirmation GUI for a specific evolution branch.
     *
     * @param player the player
     * @param myPet  the pet to evolve
     * @param check  the selected branch check (must be eligible)
     */
    public void openConfirmation(@NotNull Player player,
                                 @NotNull MyPet myPet,
                                 @NotNull BranchCheck check) {
        Inventory gui = Bukkit.createInventory(null, CONFIRM_SIZE, CONFIRM_TITLE);

        // Fill background
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < CONFIRM_SIZE; i++) {
            gui.setItem(i, filler);
        }

        // Current pet info (left)
        PetData petData = petDataCache.get(myPet.getUUID());
        PetStats petStats = petData != null ? petDataCache.getStats(petData.addonPetId()) : null;
        if (petData == null || petStats == null) {
            player.sendMessage("§cペットデータの取得に失敗しました。");
            return;
        }

        gui.setItem(SLOT_CURRENT_PET, buildCurrentPetItem(myPet, petData, petStats));
        gui.setItem(SLOT_ARROW, createItem(Material.ARROW, "§e§l→ 進化 →"));
        gui.setItem(SLOT_EVOLVED_PET, buildEvolvedPetItem(check, myPet));
        gui.setItem(SLOT_CONFIRM, createItem(Material.LIME_CONCRETE, "§a§l進化する"));
        gui.setItem(SLOT_CANCEL, createItem(Material.RED_CONCRETE, "§c§lキャンセル"));

        pendingConfirmations.put(player.getUniqueId(),
                new PendingConfirmation(myPet, check, gui));
        player.openInventory(gui);
    }

    // ─── Event Handlers ──────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        UUID playerId = player.getUniqueId();

        // Check selection GUI
        PendingSelection selection = pendingSelections.get(playerId);
        if (selection != null && event.getInventory().equals(selection.inventory())) {
            event.setCancelled(true);
            handleSelectionClick(player, selection, event.getRawSlot());
            return;
        }

        // Check confirmation GUI
        PendingConfirmation confirmation = pendingConfirmations.get(playerId);
        if (confirmation != null && event.getInventory().equals(confirmation.inventory())) {
            event.setCancelled(true);
            handleConfirmationClick(player, confirmation, event.getRawSlot());
        }
    }

    private void handleSelectionClick(@NotNull Player player,
                                      @NotNull PendingSelection selection,
                                      int slot) {
        // Close button
        if (slot == SELECTION_CLOSE_SLOT) {
            player.closeInventory();
            pendingSelections.remove(player.getUniqueId());
            player.sendMessage("§7進化選択を閉じました。");
            return;
        }

        // Find which branch was clicked
        int branchCount = Math.min(selection.branches().size(), 4);
        int[] slots = BRANCH_SLOTS[branchCount - 1];
        for (int i = 0; i < slots.length; i++) {
            if (slot == slots[i] && i < selection.branches().size()) {
                BranchCheck clicked = selection.branches().get(i);
                if (!clicked.eligible()) {
                    player.sendMessage("§c" + clicked.reason());
                    return;
                }

                // Close selection and open confirmation
                player.closeInventory();
                pendingSelections.remove(player.getUniqueId());

                Bukkit.getScheduler().runTask(plugin, () ->
                        openConfirmation(player, selection.myPet(), clicked));
                return;
            }
        }
    }

    private void handleConfirmationClick(@NotNull Player player,
                                         @NotNull PendingConfirmation confirmation,
                                         int slot) {
        if (slot == SLOT_CONFIRM) {
            player.closeInventory();
            pendingConfirmations.remove(player.getUniqueId());

            BranchCheck check = confirmation.selectedBranch();
            Bukkit.getScheduler().runTask(plugin, () ->
                    evolutionManager.evolve(player, confirmation.myPet(), check.branch()));

        } else if (slot == SLOT_CANCEL) {
            player.closeInventory();
            pendingConfirmations.remove(player.getUniqueId());
            player.sendMessage("§7進化をキャンセルしました。");
        }
    }

    @EventHandler
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        UUID playerId = player.getUniqueId();

        PendingSelection sel = pendingSelections.get(playerId);
        if (sel != null && event.getInventory().equals(sel.inventory())) {
            pendingSelections.remove(playerId);
        }

        PendingConfirmation conf = pendingConfirmations.get(playerId);
        if (conf != null && event.getInventory().equals(conf.inventory())) {
            pendingConfirmations.remove(playerId);
        }
    }

    // ─── Item Builders ───────────────────────────────────────────

    /**
     * Builds an item representing a branch option in the selection GUI.
     */
    @NotNull
    private ItemStack buildBranchItem(@NotNull BranchCheck check, @NotNull MyPet myPet) {
        EvolutionBranch branch = check.branch();
        Material headMaterial = check.eligible()
                ? mobTypeToSkull(branch.target())
                : Material.BARRIER;

        ItemStack item = new ItemStack(headMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        String statusColor = check.eligible() ? "§a" : "§c";
        meta.setDisplayName(statusColor + "§l" + branch.displayName());

        List<String> lore = new ArrayList<>();
        lore.add("§7進化先: §f" + branch.target());
        lore.add("");

        // Conditions
        lore.add("§6条件:");
        lore.add(conditionLine("レベル", "Lv." + branch.minLevel(),
                myPet.getExperience().getLevel() >= branch.minLevel()));

        PetData petData = petDataCache.get(myPet.getUUID());
        int currentBond = petData != null ? petData.bondLevel() : 0;
        lore.add(conditionLine("絆レベル", String.valueOf(branch.minBondLevel()),
                currentBond >= branch.minBondLevel()));

        if (!branch.requiredBiome().isEmpty()) {
            lore.add(conditionLine("バイオーム", branch.requiredBiome(), false)); // Can't check here easily
        }

        if (!branch.requiredItem().isEmpty()) {
            lore.add(conditionLine("アイテム",
                    branch.requiredItem() + " x" + branch.requiredItemAmount(), false));
        }

        // Stat bonuses
        if (!branch.statBonus().isEmpty()) {
            lore.add("");
            lore.add("§6ステータスボーナス:");
            for (Map.Entry<String, Double> bonus : branch.statBonus().entrySet()) {
                double percent = (bonus.getValue() - 1.0) * 100.0;
                lore.add("§7  " + bonus.getKey() + ": §a+" + String.format("%.0f%%", percent));
            }
        }

        // Fixed skilltree
        if (branch.fixedSkilltree() != null && !branch.fixedSkilltree().isEmpty()) {
            lore.add("");
            lore.add("§b固有スキル: §f" + branch.fixedSkilltree());
        }

        // Status message
        lore.add("");
        if (check.eligible()) {
            lore.add("§a§lクリックで選択");
        } else {
            lore.add("§c" + check.reason());
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @NotNull
    private String conditionLine(@NotNull String label, @NotNull String value, boolean met) {
        String icon = met ? "§a✔" : "§c✘";
        return "§7  " + icon + " §7" + label + ": §f" + value;
    }

    @NotNull
    private ItemStack buildCurrentPetItem(@NotNull MyPet myPet,
                                          @NotNull PetData petData,
                                          @NotNull PetStats petStats) {
        Material headMaterial = mobTypeToSkull(petData.mobType());
        ItemStack item = new ItemStack(headMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName("§e§l現在のペット");
        List<String> lore = new ArrayList<>();
        lore.add("§7名前: §f" + myPet.getPetName());
        lore.add("§7種類: §f" + petData.mobType());
        lore.add("§7レアリティ: " + petData.rarity().getColoredName());
        lore.add("§7レベル: §f" + myPet.getExperience().getLevel());

        lore.add("");
        lore.add("§6§lステータス:");
        for (Map.Entry<String, Double> entry : petStats.baseValues().entrySet()) {
            double effective = petStats.getEffective(entry.getKey());
            String statDisplay = ("Life".equals(entry.getKey()) || "Damage".equals(entry.getKey()))
                    ? String.format("%.0f", effective) : String.format("%.1f", effective);
            String jaName = switch (entry.getKey()) {
                case "Life" -> "体力";
                case "Damage" -> "攻撃力";
                case "Speed" -> "速度";
                default -> entry.getKey();
            };
            lore.add("§7  " + jaName + ": §f" + statDisplay);
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @NotNull
    private ItemStack buildEvolvedPetItem(@NotNull BranchCheck check, @NotNull MyPet myPet) {
        EvolutionBranch branch = check.branch();
        Material headMaterial = mobTypeToSkull(branch.target());
        ItemStack item = new ItemStack(headMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName("§a§l" + branch.displayName());
        List<String> lore = new ArrayList<>();
        lore.add("§7名前: §f" + myPet.getPetName());
        lore.add("§7種類: §a" + branch.target());
        lore.add("");

        if (!branch.statBonus().isEmpty()) {
            lore.add("§6§lステータスボーナス:");
            for (Map.Entry<String, Double> bonus : branch.statBonus().entrySet()) {
                double percent = (bonus.getValue() - 1.0) * 100.0;
                lore.add("§7  " + bonus.getKey() + ": §a+" + String.format("%.0f%%", percent));
            }
        }

        if (branch.fixedSkilltree() != null && !branch.fixedSkilltree().isEmpty()) {
            lore.add("");
            lore.add("§b§l固有スキル: §f" + branch.fixedSkilltree());
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

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

    @NotNull
    private Material mobTypeToSkull(@NotNull String mobType) {
        return switch (mobType.toUpperCase()) {
            case "ZOMBIE", "ZOMBIE_VILLAGER", "HUSK", "DROWNED" -> Material.ZOMBIE_HEAD;
            case "SKELETON", "STRAY", "BOGGED" -> Material.SKELETON_SKULL;
            case "WITHER_SKELETON" -> Material.WITHER_SKELETON_SKULL;
            case "CREEPER" -> Material.CREEPER_HEAD;
            case "ENDER_DRAGON" -> Material.DRAGON_HEAD;
            case "PIGLIN", "PIGLIN_BRUTE", "ZOMBIFIED_PIGLIN" -> Material.PIGLIN_HEAD;
            case "ENDERMITE", "SILVERFISH" -> Material.PLAYER_HEAD;
            case "SPIDER", "CAVE_SPIDER" -> Material.FERMENTED_SPIDER_EYE;
            case "HORSE", "ZOMBIE_HORSE", "SKELETON_HORSE" -> Material.SADDLE;
            case "CAMEL" -> Material.SADDLE;
            case "SLIME", "MAGMA_CUBE" -> Material.SLIME_BALL;
            case "HOGLIN", "ZOGLIN" -> Material.PORKCHOP;
            case "PILLAGER", "VINDICATOR", "EVOKER" -> Material.CROSSBOW;
            default -> Material.PLAYER_HEAD;
        };
    }
}
