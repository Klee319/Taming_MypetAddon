package com.mypetaddon.command;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.util.MobNameTranslator;
import de.Keyle.MyPet.MyPetApi;
import de.Keyle.MyPet.api.entity.MyPet;
import de.Keyle.MyPet.api.player.MyPetPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GUI-based pet release confirmation.
 * Replaces MyPet's chat-click /petrelease which does not work
 * for Bedrock Edition players (via Geyser) who cannot click chat messages.
 */
public final class PetReleaseGUI implements Listener {

    private static final String GUI_TITLE = "§4§lペットを手放す";
    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;

    // Layout slots
    private static final int SLOT_PET_INFO = 13;     // center: pet info
    private static final int SLOT_CONFIRM = 11;      // left of center: confirm
    private static final int SLOT_CANCEL = 15;        // right of center: cancel

    private final MyPetAddonPlugin plugin;
    private final PetDataCache petDataCache;

    /** Player UUID -> pending release data. */
    private final Map<UUID, PendingRelease> pendingReleases = new ConcurrentHashMap<>();

    private record PendingRelease(
            @NotNull MyPet myPet,
            @NotNull Inventory inventory
    ) {}

    public PetReleaseGUI(@NotNull MyPetAddonPlugin plugin,
                          @NotNull PetDataCache petDataCache) {
        this.plugin = plugin;
        this.petDataCache = petDataCache;
    }

    /**
     * Opens the release confirmation GUI for the player's active pet.
     */
    public void openReleaseConfirmation(@NotNull Player player, @NotNull MyPet myPet) {
        Inventory gui = Bukkit.createInventory(null, SIZE, GUI_TITLE);

        // Fill background with red stained glass
        ItemStack filler = createItem(Material.RED_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < SIZE; i++) {
            gui.setItem(i, filler);
        }

        // Pet info (center)
        gui.setItem(SLOT_PET_INFO, buildPetInfoItem(myPet));

        // Confirm button (red concrete with warning)
        gui.setItem(SLOT_CONFIRM, buildConfirmItem(myPet));

        // Cancel button
        gui.setItem(SLOT_CANCEL, createItem(Material.LIME_CONCRETE, "§a§lキャンセル",
                List.of("§7クリックでキャンセルします")));

        pendingReleases.put(player.getUniqueId(), new PendingRelease(myPet, gui));
        player.openInventory(gui);
    }

    // ─── Event Handlers ──────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        PendingRelease pending = pendingReleases.get(player.getUniqueId());
        if (pending == null || !event.getInventory().equals(pending.inventory())) {
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot == SLOT_CONFIRM) {
            handleConfirm(player, pending);
        } else if (slot == SLOT_CANCEL) {
            player.closeInventory();
            pendingReleases.remove(player.getUniqueId());
            player.sendMessage("§7ペットの解放をキャンセルしました。");
        }
    }

    @EventHandler
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        PendingRelease pending = pendingReleases.get(player.getUniqueId());
        if (pending != null && event.getInventory().equals(pending.inventory())) {
            pendingReleases.remove(player.getUniqueId());
        }
    }

    // ─── Release Logic ───────────────────────────────────────────

    private void handleConfirm(@NotNull Player player, @NotNull PendingRelease pending) {
        player.closeInventory();
        pendingReleases.remove(player.getUniqueId());

        MyPet myPet = pending.myPet();
        String petName = myPet.getPetName();

        // Verify pet is still active and belongs to this player
        MyPetPlayer myPetPlayer = getMyPetPlayer(player);
        if (myPetPlayer == null || !myPetPlayer.hasMyPet()
                || !myPetPlayer.getMyPet().getUUID().equals(myPet.getUUID())) {
            player.sendMessage("§cペットが見つかりません。既に手放されているか、切り替えられています。");
            return;
        }

        // Use MyPet API to release the pet
        try {
            MyPetApi.getMyPetManager().deactivateMyPet(myPetPlayer, true);
            MyPetApi.getRepository().removeMyPet(myPet.getUUID(), null);

            player.sendMessage("§c" + petName + " §cを手放しました。さようなら…");
            player.playSound(player.getLocation(), Sound.ENTITY_WOLF_WHINE, 1.0f, 0.8f);
        } catch (Exception e) {
            plugin.getLogger().warning("[PetRelease] Failed to release pet for "
                    + player.getName() + ": " + e.getMessage());
            player.sendMessage("§cペットの解放に失敗しました。もう一度お試しください。");
        }
    }

    // ─── Item Builders ───────────────────────────────────────────

    @NotNull
    private ItemStack buildPetInfoItem(@NotNull MyPet myPet) {
        PetData petData = petDataCache.get(myPet.getUUID());

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName("§e§l" + myPet.getPetName());

        List<String> lore = new java.util.ArrayList<>();
        String mobName = MobNameTranslator.translate(myPet.getPetType().name());
        lore.add("§7種類: §f" + mobName);
        lore.add("§7レベル: §f" + myPet.getExperience().getLevel());

        if (petData != null) {
            lore.add("§7レアリティ: " + petData.rarity().getColoredName());
            String pName = petData.personality().getDisplayName();
            lore.add("§7性格: §f" + pName);
            lore.add("§7親密度: §fLv." + petData.bondLevel());
        }

        lore.add("");
        lore.add("§c§lこのペットを手放しますか？");
        lore.add("§c§l取り消すことはできません！");

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @NotNull
    private ItemStack buildConfirmItem(@NotNull MyPet myPet) {
        ItemStack item = new ItemStack(Material.RED_CONCRETE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName("§c§l手放す");
        meta.setLore(List.of(
                "§7" + myPet.getPetName() + " を完全に手放します",
                "",
                "§c§l⚠ この操作は取り消せません！",
                "§cレベル・ステータス・装備が全て失われます"
        ));

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
    private ItemStack createItem(@NotNull Material material, @NotNull String displayName,
                                  @NotNull List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    @Nullable
    private MyPetPlayer getMyPetPlayer(@NotNull Player player) {
        try {
            var pm = MyPetApi.getPlayerManager();
            if (!pm.isMyPetPlayer(player)) {
                return null;
            }
            return pm.getMyPetPlayer(player);
        } catch (Exception e) {
            return null;
        }
    }
}
