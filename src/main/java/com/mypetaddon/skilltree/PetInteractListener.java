package com.mypetaddon.skilltree;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.config.ConfigManager.RerollItemEntry;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.PetStats;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.rarity.Rarity;
import com.mypetaddon.stats.StatsManager;
import com.mypetaddon.util.ItemMatcher;
import de.Keyle.MyPet.api.entity.MyPet;
import de.Keyle.MyPet.api.entity.MyPetBukkitEntity;
import de.Keyle.MyPet.api.skill.skilltree.Skilltree;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Handles player right-click on their own MyPet for reroll item interactions.
 * Ported from legacy onClickedPet() — supports skill tree reroll, stat reroll,
 * and stat+1 enhancement items.
 */
public final class PetInteractListener implements Listener {

    private final MyPetAddonPlugin plugin;
    private final ConfigManager configManager;
    private final PetDataCache petDataCache;
    private final StatsManager statsManager;
    private final SkilltreeAssigner skilltreeAssigner;
    private final Logger logger;

    public PetInteractListener(@NotNull MyPetAddonPlugin plugin,
                               @NotNull ConfigManager configManager,
                               @NotNull PetDataCache petDataCache,
                               @NotNull StatsManager statsManager,
                               @NotNull SkilltreeAssigner skilltreeAssigner) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.petDataCache = petDataCache;
        this.statsManager = statsManager;
        this.skilltreeAssigner = skilltreeAssigner;
        this.logger = plugin.getLogger();
    }

    @EventHandler
    public void onPlayerInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        if (!(event.getRightClicked() instanceof MyPetBukkitEntity myPetEntity)) {
            return;
        }

        MyPet myPet = myPetEntity.getMyPet();
        Player player = event.getPlayer();

        // Only the owner can use reroll items
        if (!myPet.getOwner().getPlayer().equals(player)) {
            return;
        }

        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            return;
        }

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand.isEmpty()) {
            return;
        }

        // Find matching reroll item
        List<RerollItemEntry> rerollItems = configManager.getRerollItems();
        RerollItemEntry matched = findMatchingRerollItem(mainHand, rerollItems);
        if (matched == null) {
            return;
        }

        event.setCancelled(true);

        // Consume item if configured
        if (matched.consume()) {
            mainHand.setAmount(mainHand.getAmount() - 1);
        }

        // Check success rate
        if (matched.successRate() < 1.0
                && ThreadLocalRandom.current().nextDouble() >= matched.successRate()) {
            int pct = (int) (matched.successRate() * 100);
            player.sendMessage("§c失敗しました... (成功率: §f" + pct + "%§c)");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        // Dispatch by type
        switch (matched.type()) {
            case "skilltree" -> handleSkilltreeReroll(player, myPet, petData);
            case "reroll" -> handleStatReroll(player, myPet, petData);
            case "upgrade" -> handleRarityUpgrade(player, myPet, petData);
            case "stat-add" -> handleStatAdd(player, myPet, petData);
            default -> player.sendMessage("§c不明なリロールタイプ: " + matched.type());
        }
    }

    /**
     * Rerolls the pet's skill tree using SkilltreeAssigner.
     */
    private void handleSkilltreeReroll(@NotNull Player player,
                                       @NotNull MyPet myPet,
                                       @NotNull PetData petData) {
        Skilltree newTree = skilltreeAssigner.reroll(myPet, petData.rarity(), petData.mobType());
        if (newTree == null) {
            player.sendMessage("§c利用可能なスキルツリーがありません。");
            return;
        }

        String displayName = newTree.hasDisplayName() ? newTree.getDisplayName() : newTree.getName();
        player.sendMessage("§eスキルツリー §f" + displayName + " §eが選択されました！");
        playSuccessEffect(player);
    }

    /**
     * Rerolls the pet's base stats (re-randomizes within configured ranges).
     */
    private void handleStatReroll(@NotNull Player player,
                                  @NotNull MyPet myPet,
                                  @NotNull PetData petData) {
        // Preserve existing upgradedValues (evolution bonuses, stat-add enhancements)
        PetStats existingStats = petDataCache.getStats(petData.addonPetId());
        PetStats rerolled = statsManager.createBaseStats(petData);
        PetStats newStats = existingStats != null
                ? new PetStats(rerolled.addonPetId(), rerolled.baseValues(), existingStats.upgradedValues())
                : rerolled;
        petDataCache.put(petData, newStats);
        statsManager.applyStats(myPet);

        player.sendMessage("§e基礎ステータスが再抽選されました！");
        playSuccessEffect(player);
    }

    /**
     * Upgrades the pet's rarity by one tier.
     */
    private void handleRarityUpgrade(@NotNull Player player,
                                      @NotNull MyPet myPet,
                                      @NotNull PetData petData) {
        Rarity current = petData.rarity();
        Rarity upgraded = current.upgrade();

        if (upgraded == current) {
            player.sendMessage("§c既に最高レアリティです！");
            return;
        }

        PetData updatedData = petData.withRarity(upgraded);
        PetStats existingStats = petDataCache.getStats(petData.addonPetId());
        if (existingStats != null) {
            petDataCache.put(updatedData, existingStats);
        }
        statsManager.applyStats(myPet);

        player.sendMessage("§6レアリティが " + current.getColoredName()
                + " §6から " + upgraded.getColoredName() + " §6にアップグレードしました！");
        playSuccessEffect(player);
    }

    /**
     * Adds +1 to a random upgradeable stat.
     */
    private void handleStatAdd(@NotNull Player player,
                               @NotNull MyPet myPet,
                               @NotNull PetData petData) {
        PetStats petStats = petDataCache.getStats(petData.addonPetId());
        if (petStats == null) {
            player.sendMessage("§cステータスデータが見つかりません。");
            return;
        }

        // Find stats that can be upgraded (base+upgraded < config max)
        var ranges = configManager.getPetBaseValues(petData.mobType());
        if (ranges.isEmpty()) {
            player.sendMessage("§cこのペットの基礎値が定義されていません。");
            return;
        }

        List<String> upgradeable = new java.util.ArrayList<>();
        for (var entry : ranges.entrySet()) {
            String statName = entry.getKey();
            double maxVal = entry.getValue()[1]; // max from config range
            double current = petStats.baseValues().getOrDefault(statName, 0.0)
                    + petStats.upgradedValues().getOrDefault(statName, 0.0);
            if (current < maxVal) {
                upgradeable.add(statName);
            }
        }

        if (upgradeable.isEmpty()) {
            player.sendMessage("§c既に最大まで強化されています！");
            return;
        }

        // Pick random stat and add +1
        String selected = upgradeable.get(ThreadLocalRandom.current().nextInt(upgradeable.size()));
        double currentUpgraded = petStats.upgradedValues().getOrDefault(selected, 0.0);
        PetStats updated = petStats.withUpgradedStat(selected, currentUpgraded + 1.0);

        petDataCache.put(petData, updated);
        statsManager.applyStats(myPet);

        player.sendMessage("§e" + selected + " の基礎ステータスを強化しました！");
        playSuccessEffect(player);
    }

    @Nullable
    private RerollItemEntry findMatchingRerollItem(@NotNull ItemStack handItem,
                                                   @NotNull List<RerollItemEntry> entries) {
        for (RerollItemEntry entry : entries) {
            if (ItemMatcher.matches(handItem, entry.itemDescriptor(), logger)) {
                return entry;
            }
        }
        return null;
    }

    private void playSuccessEffect(@NotNull Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 2.0f);
        player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                player.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.0);
    }
}
