package com.mypetaddon.evolution;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.PetStats;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.stats.StatsManager;
import com.mypetaddon.util.SoundUtil;
import de.Keyle.MyPet.MyPetApi;
import de.Keyle.MyPet.api.WorldGroup;
import de.Keyle.MyPet.api.entity.MyPet;
import de.Keyle.MyPet.api.entity.MyPetType;
import de.Keyle.MyPet.api.player.MyPetPlayer;
import de.Keyle.MyPet.api.repository.RepositoryCallback;
import de.Keyle.MyPet.entity.InactiveMyPet;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Manages pet evolution with branching paths and fixed skilltree assignment.
 * Supports multiple evolution targets per mob type, each with independent
 * conditions, stat bonuses, and a predetermined skilltree.
 */
public final class EvolutionManager {

    private final MyPetAddonPlugin plugin;
    private final ConfigManager configManager;
    private final PetDataCache petDataCache;
    private final StatsManager statsManager;
    private final Logger logger;

    public EvolutionManager(@NotNull MyPetAddonPlugin plugin,
                            @NotNull ConfigManager configManager,
                            @NotNull PetDataCache petDataCache,
                            @NotNull StatsManager statsManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.petDataCache = petDataCache;
        this.statsManager = statsManager;
        this.logger = plugin.getLogger();
    }

    // ─── Branch Checking ─────────────────────────────────────────

    /**
     * Checks all evolution branches for a pet and returns eligibility info for each.
     *
     * @param myPet the active MyPet to check
     * @return list of branch checks (empty if no evolutions exist for this mob type)
     */
    @NotNull
    public List<BranchCheck> checkBranches(@NotNull MyPet myPet) {
        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            return Collections.emptyList();
        }

        List<EvolutionBranch> branches = getEvolutionBranches(petData.mobType());
        if (branches.isEmpty()) {
            return Collections.emptyList();
        }

        List<BranchCheck> checks = new ArrayList<>();
        for (EvolutionBranch branch : branches) {
            checks.add(checkSingleBranch(myPet, petData, branch));
        }
        return Collections.unmodifiableList(checks);
    }

    /**
     * Checks a single branch's eligibility for a pet.
     */
    @NotNull
    private BranchCheck checkSingleBranch(@NotNull MyPet myPet,
                                           @NotNull PetData petData,
                                           @NotNull EvolutionBranch branch) {
        // Check min-level
        int currentLevel = myPet.getExperience().getLevel();
        if (currentLevel < branch.minLevel()) {
            return new BranchCheck(branch, false,
                    "レベルが足りません。必要: Lv." + branch.minLevel() + " (現在: Lv." + currentLevel + ")");
        }

        // Check min-bond-level
        if (petData.bondLevel() < branch.minBondLevel()) {
            return new BranchCheck(branch, false,
                    "絆レベルが足りません。必要: " + branch.minBondLevel()
                            + " (現在: " + petData.bondLevel() + ")");
        }

        // Check required-biome
        if (!branch.requiredBiome().isEmpty()) {
            Player owner = Bukkit.getPlayer(petData.ownerUuid());
            if (owner == null) {
                return new BranchCheck(branch, false, "オーナーがオンラインではありません。");
            }
            String currentBiome = owner.getLocation().getBlock().getBiome().getKey().getKey().toUpperCase();
            String requiredBiome = branch.requiredBiome().toUpperCase();
            // Match exact biome or biome group (e.g. "OCEAN" matches "DEEP_OCEAN", "SNOWY" matches "SNOWY_PLAINS")
            // Split biome into words and check if required biome is one of them, or exact match
            if (!currentBiome.equals(requiredBiome) && !biomeGroupMatch(currentBiome, requiredBiome)) {
                return new BranchCheck(branch, false,
                        "バイオームが一致しません。必要: " + branch.requiredBiome());
            }
        }

        // Check required-item
        if (!branch.requiredItem().isEmpty()) {
            Material requiredMaterial = Material.matchMaterial(branch.requiredItem());
            if (requiredMaterial == null) {
                return new BranchCheck(branch, false,
                        "進化アイテム設定が不正です: " + branch.requiredItem());
            }

            Player owner = Bukkit.getPlayer(petData.ownerUuid());
            if (owner == null) {
                return new BranchCheck(branch, false, "オーナーがオンラインではありません。");
            }

            int playerCount = countItem(owner, requiredMaterial);
            if (playerCount < branch.requiredItemAmount()) {
                return new BranchCheck(branch, false,
                        requiredMaterial.name() + " x" + branch.requiredItemAmount()
                                + " が必要です (所持: " + playerCount + ")");
            }
        }

        return new BranchCheck(branch, true, "進化可能です。");
    }

    // ─── Evolution Execution ─────────────────────────────────────

    /**
     * Executes the full evolution procedure for a specific branch.
     *
     * @param player the owning player
     * @param myPet  the active MyPet to evolve
     * @param branch the selected evolution branch
     * @return true if evolution was initiated (async completion)
     */
    public boolean evolve(@NotNull Player player,
                          @NotNull MyPet myPet,
                          @NotNull EvolutionBranch branch) {
        // ── Step 1: PRE-CHECK ──
        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            player.sendMessage("§cペットデータが見つかりません。");
            return false;
        }

        BranchCheck check = checkSingleBranch(myPet, petData, branch);
        if (!check.eligible()) {
            player.sendMessage("§c" + check.reason());
            return false;
        }

        String targetType = branch.target();

        // ── Step 2: BACKUP ──
        PetStats backupStats = petDataCache.getStats(petData.addonPetId());
        UUID backupMypetUuid = myPet.getUUID();
        UUID addonPetId = petData.addonPetId();
        String originalName = myPet.getPetName();
        double backupExp = myPet.getExperience().getExp();
        de.Keyle.MyPet.api.skill.skilltree.Skilltree skilltreeObj = myPet.getSkilltree();
        String backupSkilltree = skilltreeObj != null ? skilltreeObj.getName() : null;
        String fromType = petData.mobType();

        try {
            // ── Step 3: REMOVE OLD MYPET ──
            MyPetPlayer myPetPlayer = MyPetApi.getPlayerManager().getMyPetPlayer(player);
            MyPetApi.getMyPetManager().deactivateMyPet(myPetPlayer, true);

            // Steps 4-5 inside removal callback to avoid race conditions
            MyPetApi.getRepository().removeMyPet(backupMypetUuid, new RepositoryCallback<>() {
                @Override
                public void callback(Boolean removed) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (removed == null || !removed) {
                            logger.severe("[Evolution] Failed to remove old MyPet "
                                    + backupMypetUuid + ", triggering rollback");
                            rollback(player, myPetPlayer, petData, backupStats,
                                    backupMypetUuid, originalName, backupExp,
                                    backupSkilltree, null);
                            player.sendMessage("§c進化に失敗しました。データをロールバックしました。");
                            return;
                        }

                        try {
                            // Consume required item AFTER successful removal to prevent item loss on failure
                            consumeRequiredItem(player, branch);
                            // ── Step 4: CREATE NEW MYPET ──
                            InactiveMyPet newInactiveMyPet = new InactiveMyPet(myPetPlayer);
                            MyPetType newPetType = MyPetType.byEntityTypeName(targetType);
                            newInactiveMyPet.setPetType(newPetType);
                            newInactiveMyPet.setPetName(originalName);

                            WorldGroup wg = WorldGroup.getGroupByWorld(player.getWorld());
                            newInactiveMyPet.setWorldGroup(wg.getName());
                            newInactiveMyPet.getOwner().setMyPetForWorldGroup(
                                    wg, newInactiveMyPet.getUUID());

                            newInactiveMyPet.setExp(backupExp);

                            // Apply fixed skilltree from evolution branch
                            String fixedTree = branch.fixedSkilltree();
                            if (fixedTree != null && !fixedTree.isEmpty()) {
                                de.Keyle.MyPet.api.skill.skilltree.Skilltree st =
                                        MyPetApi.getSkilltreeManager().getSkilltree(fixedTree);
                                if (st != null) {
                                    newInactiveMyPet.setSkilltree(st);
                                } else {
                                    logger.warning("[Evolution] Fixed skilltree '" + fixedTree
                                            + "' not found, falling back to previous: " + backupSkilltree);
                                    applyFallbackSkilltree(newInactiveMyPet, backupSkilltree);
                                }
                            } else {
                                applyFallbackSkilltree(newInactiveMyPet, backupSkilltree);
                            }

                            // Register with MyPet repository and activate
                            UUID newMypetUuid = newInactiveMyPet.getUUID();
                            MyPetApi.getRepository().addMyPet(newInactiveMyPet,
                                    new RepositoryCallback<Boolean>() {
                                @Override
                                public void callback(Boolean value) {
                                    // Ensure entity creation runs on the main thread
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        var activePet = MyPetApi.getMyPetManager()
                                                .activateMyPet(newInactiveMyPet)
                                                .orElse(null);
                                        if (activePet != null) {
                                            activePet.createEntity();
                                        }
                                    });
                                }
                            });

                            // ── Step 5: UPDATE ADDON DATA ──
                            PetData updatedData = petData
                                    .withMypetUuid(newMypetUuid)
                                    .withMobType(targetType);

                            PetStats updatedStats = backupStats != null ? backupStats
                                    : new PetStats(addonPetId, Map.of(), Map.of());
                            for (Map.Entry<String, Double> bonus : branch.statBonus().entrySet()) {
                                double baseValue = updatedStats.baseValues()
                                        .getOrDefault(bonus.getKey(), 0.0);
                                double currentUpgraded = updatedStats.upgradedValues()
                                        .getOrDefault(bonus.getKey(), 0.0);
                                double evolutionBonus = baseValue * (bonus.getValue() - 1.0);
                                updatedStats = updatedStats.withUpgradedStat(
                                        bonus.getKey(), currentUpgraded + evolutionBonus);
                            }

                            petDataCache.invalidate(backupMypetUuid);
                            petDataCache.put(updatedData, updatedStats);

                            saveEvolutionHistory(addonPetId, fromType, targetType,
                                    backupMypetUuid, newMypetUuid);

                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                MyPet activePet = MyPetApi.getMyPetManager().getMyPet(player);
                                if (activePet != null) {
                                    statsManager.applyStats(activePet);
                                }
                            }, 5L);

                            SoundUtil.playSound(player, "evolution-success", configManager);
                            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING,
                                    player.getLocation().add(0, 1, 0), 50, 1.0, 1.5, 1.0, 0.3);

                            String skilltreeMsg = "";
                            if (fixedTree != null && !fixedTree.isEmpty()) {
                                skilltreeMsg = " §7(固有スキル: §b" + fixedTree + "§7)";
                            }
                            player.sendMessage("§a§l進化成功！ §f" + originalName
                                    + " §7が §e" + fromType + " §7から §a" + targetType
                                    + " §7に進化しました！" + skilltreeMsg);

                        } catch (Exception e) {
                            logger.log(Level.SEVERE, "Evolution failed for pet " + addonPetId
                                    + " during creation, attempting rollback", e);
                            // Restore consumed items before rollback
                            restoreRequiredItem(player, branch);
                            rollback(player, myPetPlayer, petData, backupStats,
                                    backupMypetUuid, originalName, backupExp,
                                    backupSkilltree, null);
                            player.sendMessage("§c進化に失敗しました。データをロールバックしました。");
                        }
                    });
                }
            });

            return true;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Evolution failed for pet " + addonPetId
                    + ", attempting rollback", e);
            rollback(player, myPetPlayer(player), petData, backupStats,
                    backupMypetUuid, originalName, backupExp,
                    backupSkilltree, null);
            player.sendMessage("§c進化に失敗しました。データをロールバックしました。");
            return false;
        }
    }

    // ─── Config Access ───────────────────────────────────────────

    /**
     * Returns all evolution branches for a mob type from config.
     */
    @NotNull
    public List<EvolutionBranch> getEvolutionBranches(@NotNull String mobType) {
        ConfigurationSection branchesSection = configManager.getConfig()
                .getConfigurationSection("evolutions." + mobType + ".branches");
        if (branchesSection == null) {
            return Collections.emptyList();
        }

        List<EvolutionBranch> result = new ArrayList<>();
        for (String branchKey : branchesSection.getKeys(false)) {
            ConfigurationSection branch = branchesSection.getConfigurationSection(branchKey);
            if (branch == null) {
                continue;
            }

            String target = branch.getString("target", "");
            if (target.isEmpty()) {
                continue;
            }

            String displayName = branch.getString("display-name", target);
            String fixedSkilltree = branch.getString("fixed-skilltree", null);
            int minLevel = branch.getInt("conditions.min-level", 1);
            int minBondLevel = branch.getInt("conditions.min-bond-level", 0);
            String requiredBiome = branch.getString("conditions.required-biome", "");
            String requiredItem = branch.getString("conditions.required-item", "");
            int requiredItemAmount = branch.getInt("conditions.required-item-amount", 1);

            Map<String, Double> statBonus = new LinkedHashMap<>();
            ConfigurationSection bonusSection = branch.getConfigurationSection("stat-bonus");
            if (bonusSection != null) {
                for (String statName : bonusSection.getKeys(false)) {
                    statBonus.put(statName, bonusSection.getDouble(statName, 1.0));
                }
            }

            result.add(new EvolutionBranch(branchKey, displayName, target, fixedSkilltree,
                    minLevel, minBondLevel, requiredBiome, requiredItem,
                    requiredItemAmount, statBonus));
        }
        return Collections.unmodifiableList(result);
    }

    // ─── Evolution History ───────────────────────────────────────

    /**
     * Records an evolution event in the evolution_history table.
     */
    public void saveEvolutionHistory(@NotNull UUID addonPetId,
                                     @NotNull String fromType,
                                     @NotNull String toType,
                                     @NotNull UUID fromMypetUuid,
                                     @NotNull UUID toMypetUuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "INSERT INTO evolution_history "
                    + "(addon_pet_id, from_type, to_type, from_mypet_uuid, to_mypet_uuid) "
                    + "VALUES (?, ?, ?, ?, ?)";

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                ps.setString(1, addonPetId.toString());
                ps.setString(2, fromType);
                ps.setString(3, toType);
                ps.setString(4, fromMypetUuid.toString());
                ps.setString(5, toMypetUuid.toString());
                ps.executeUpdate();

            } catch (SQLException e) {
                logger.log(Level.SEVERE,
                        "Failed to save evolution history for pet: " + addonPetId, e);
            }
        });
    }

    // ─── Inner Records ───────────────────────────────────────────

    /**
     * Result of checking a single evolution branch's eligibility.
     *
     * @param branch   the evolution branch being checked
     * @param eligible whether the pet meets all conditions for this branch
     * @param reason   human-readable reason (Japanese)
     */
    public record BranchCheck(
            @NotNull EvolutionBranch branch,
            boolean eligible,
            @NotNull String reason
    ) {}

    // ─── Internal Helpers ────────────────────────────────────────

    /**
     * Applies a fallback skilltree (the pet's previous one) if available.
     */
    private void applyFallbackSkilltree(@NotNull InactiveMyPet pet, String skilltreeName) {
        if (skilltreeName != null) {
            de.Keyle.MyPet.api.skill.skilltree.Skilltree st =
                    MyPetApi.getSkilltreeManager().getSkilltree(skilltreeName);
            if (st != null) {
                pet.setSkilltree(st);
            }
        }
    }

    /**
     * Restores the required evolution item to the player's inventory on rollback.
     */
    private void restoreRequiredItem(@NotNull Player player, @NotNull EvolutionBranch branch) {
        if (branch.requiredItem().isEmpty()) {
            return;
        }

        Material material = Material.matchMaterial(branch.requiredItem());
        if (material == null) {
            return;
        }

        ItemStack restoreStack = new ItemStack(material, branch.requiredItemAmount());
        var leftover = player.getInventory().addItem(restoreStack);
        if (!leftover.isEmpty()) {
            // Drop items at player's feet if inventory is full
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }

        logger.info("[Evolution] Restored " + branch.requiredItemAmount() + "x "
                + material.name() + " to " + player.getName() + " during rollback");
    }

    /**
     * Consumes the required evolution item from the player's inventory.
     */
    private void consumeRequiredItem(@NotNull Player player, @NotNull EvolutionBranch branch) {
        if (branch.requiredItem().isEmpty()) {
            return;
        }

        Material material = Material.matchMaterial(branch.requiredItem());
        if (material == null) {
            return;
        }

        int remaining = branch.requiredItemAmount();
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() == material) {
                int take = Math.min(item.getAmount(), remaining);
                item.setAmount(item.getAmount() - take);
                if (item.getAmount() <= 0) {
                    player.getInventory().setItem(i, null);
                }
                remaining -= take;
            }
        }
    }

    /**
     * Counts the total number of items of the given material in a player's inventory.
     */
    private int countItem(@NotNull Player player, @NotNull Material material) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /**
     * Checks if a biome matches a biome group keyword.
     * Splits the biome key into words by underscore and checks if any word
     * matches the required group. E.g. "DEEP_OCEAN" matches group "OCEAN",
     * "SNOWY_PLAINS" matches group "SNOWY", "NETHER_WASTES" matches group "NETHER".
     */
    private boolean biomeGroupMatch(@NotNull String biomeKey, @NotNull String group) {
        for (String part : biomeKey.split("_")) {
            if (part.equals(group)) {
                return true;
            }
        }
        return false;
    }

    @NotNull
    private MyPetPlayer myPetPlayer(@NotNull Player player) {
        return MyPetApi.getPlayerManager().getMyPetPlayer(player);
    }

    /**
     * Restores the old MyPet during a rollback by recreating it and updating the cache.
     */
    private void restoreOldPet(@NotNull Player player,
                               @NotNull MyPetPlayer myPetPlayer,
                               @NotNull PetData backupData,
                               @Nullable PetStats backupStats,
                               @NotNull UUID backupMypetUuid,
                               @NotNull String originalName,
                               double backupExp,
                               String backupSkilltree) {
        try {
            InactiveMyPet restoredPet = new InactiveMyPet(myPetPlayer);
            MyPetType oldType = MyPetType.byEntityTypeName(backupData.mobType());
            restoredPet.setPetType(oldType);
            restoredPet.setPetName(originalName);

            WorldGroup wg = WorldGroup.getGroupByWorld(player.getWorld());
            restoredPet.setWorldGroup(wg.getName());
            restoredPet.getOwner().setMyPetForWorldGroup(wg, restoredPet.getUUID());
            restoredPet.setExp(backupExp);

            applyFallbackSkilltree(restoredPet, backupSkilltree);

            MyPetApi.getRepository().addMyPet(restoredPet, new RepositoryCallback<Boolean>() {
                @Override
                public void callback(Boolean value) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        var activePet = MyPetApi.getMyPetManager()
                                .activateMyPet(restoredPet).orElse(null);
                        if (activePet != null) {
                            activePet.createEntity();
                        }
                    });
                }
            });

            PetData restoredData = backupData.withMypetUuid(restoredPet.getUUID());
            petDataCache.invalidate(backupMypetUuid);
            if (backupStats != null) {
                petDataCache.put(restoredData, backupStats);
            }

            logger.info("[Evolution] Rollback completed for pet: " + backupData.addonPetId());

        } catch (Exception rollbackError) {
            logger.log(Level.SEVERE,
                    "[Evolution] CRITICAL: Rollback restoration also failed for pet: "
                            + backupData.addonPetId(), rollbackError);
        }
    }

    /**
     * Attempts to rollback a failed evolution.
     */
    private void rollback(@NotNull Player player,
                          @NotNull MyPetPlayer myPetPlayer,
                          @NotNull PetData backupData,
                          @Nullable PetStats backupStats,
                          @NotNull UUID backupMypetUuid,
                          @NotNull String originalName,
                          double backupExp,
                          String backupSkilltree,
                          InactiveMyPet failedNewPet) {
        try {
            if (failedNewPet != null) {
                MyPetApi.getMyPetManager().deactivateMyPet(myPetPlayer, false);
                MyPetApi.getRepository().removeMyPet(failedNewPet.getUUID(),
                        new RepositoryCallback<>() {
                            @Override
                            public void callback(Boolean value) {
                                Bukkit.getScheduler().runTask(plugin, () ->
                                        restoreOldPet(player, myPetPlayer, backupData, backupStats,
                                                backupMypetUuid, originalName, backupExp, backupSkilltree));
                            }
                        });
            } else {
                restoreOldPet(player, myPetPlayer, backupData, backupStats,
                        backupMypetUuid, originalName, backupExp, backupSkilltree);
            }
        } catch (Exception rollbackError) {
            logger.log(Level.SEVERE,
                    "[Evolution] CRITICAL: Rollback also failed for pet: "
                            + backupData.addonPetId(), rollbackError);
        }
    }
}
