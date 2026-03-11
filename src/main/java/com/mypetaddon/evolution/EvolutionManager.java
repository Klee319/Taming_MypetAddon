package com.mypetaddon.evolution;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.DatabaseManager;
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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages pet evolution with safe MyPet recreation and rollback.
 * Follows a 6-step safety procedure: pre-check, backup, remove old,
 * create new, update addon data, and rollback on failure.
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

    // ─── Evolution Check ────────────────────────────────────────

    /**
     * Checks whether a MyPet is eligible for evolution.
     *
     * @param myPet the active MyPet to check
     * @return an EvolutionCheck with eligibility info and stat bonuses
     */
    @NotNull
    public EvolutionCheck canEvolve(@NotNull MyPet myPet) {
        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            return EvolutionCheck.fail("このペットのアドオンデータが見つかりません。");
        }

        String mobType = petData.mobType();
        Optional<String> targetOpt = getEvolutionTarget(mobType);
        if (targetOpt.isEmpty()) {
            return EvolutionCheck.fail("このペットには進化先がありません。");
        }

        String targetType = targetOpt.get();
        ConfigurationSection evoSection = configManager.getConfig()
                .getConfigurationSection("evolutions." + mobType);
        if (evoSection == null) {
            return EvolutionCheck.fail("進化設定が見つかりません。");
        }

        // Check min-level condition
        int minLevel = evoSection.getInt("conditions.min-level", 1);
        if (myPet.getExperience().getLevel() < minLevel) {
            return EvolutionCheck.fail(
                    "レベルが足りません。必要レベル: " + minLevel
                            + " (現在: " + myPet.getExperience().getLevel() + ")");
        }

        // Check min-bond-level condition
        int minBondLevel = evoSection.getInt("conditions.min-bond-level", 0);
        if (petData.bondLevel() < minBondLevel) {
            return EvolutionCheck.fail(
                    "絆レベルが足りません。必要絆レベル: " + minBondLevel
                            + " (現在: " + petData.bondLevel() + ")");
        }

        // Check required-item condition
        String requiredItemName = evoSection.getString("conditions.required-item", "");
        if (!requiredItemName.isEmpty()) {
            Material requiredMaterial = Material.matchMaterial(requiredItemName);
            if (requiredMaterial == null) {
                return EvolutionCheck.fail("進化に必要なアイテム設定が不正です: " + requiredItemName);
            }

            Player owner = Bukkit.getPlayer(petData.ownerUuid());
            if (owner == null) {
                return EvolutionCheck.fail("オーナーがオンラインではありません。");
            }

            if (!owner.getInventory().contains(requiredMaterial)) {
                return EvolutionCheck.fail(
                        "進化に必要なアイテムが足りません: " + requiredMaterial.name());
            }
        }

        // Parse stat bonuses
        Map<String, Double> statBonus = parseStatBonus(evoSection);

        return new EvolutionCheck(true, "進化可能です。", targetType, statBonus);
    }

    // ─── Evolution Execution ────────────────────────────────────

    /**
     * Executes the full 6-step evolution procedure.
     *
     * @param player the owning player
     * @param myPet  the active MyPet to evolve
     * @return true if evolution succeeded
     */
    public boolean evolve(@NotNull Player player, @NotNull MyPet myPet) {
        // ── Step 1: PRE-CHECK ──
        EvolutionCheck check = canEvolve(myPet);
        if (!check.canEvolve()) {
            player.sendMessage("§c" + check.reason());
            return false;
        }

        String targetType = check.targetType();

        // ── Step 2: BACKUP ──
        PetData backupData = petDataCache.get(myPet.getUUID());
        if (backupData == null) {
            player.sendMessage("§cペットデータのバックアップに失敗しました。");
            return false;
        }

        PetStats backupStats = petDataCache.getStats(backupData.addonPetId());
        UUID backupMypetUuid = myPet.getUUID();
        UUID addonPetId = backupData.addonPetId();
        String originalName = myPet.getPetName();
        double backupExp = myPet.getExperience().getExp();
        de.Keyle.MyPet.api.skill.skilltree.Skilltree skilltreeObj = myPet.getSkilltree();
        String backupSkilltree = skilltreeObj != null
                ? skilltreeObj.getName() : null;
        String fromType = backupData.mobType();

        InactiveMyPet newInactiveMyPet = null;

        try {
            // ── Step 3: REMOVE OLD MYPET ──
            MyPetPlayer myPetPlayer = MyPetApi.getPlayerManager().getMyPetPlayer(player);
            MyPetApi.getMyPetManager().deactivateMyPet(myPetPlayer, true);

            MyPetApi.getRepository().removeMyPet(backupMypetUuid, new RepositoryCallback<>() {
                @Override
                public void callback(Boolean value) {
                    // Old MyPet removed from repository
                }
            });

            // ── Step 4: CREATE NEW MYPET ──
            newInactiveMyPet = new InactiveMyPet(myPetPlayer);

            MyPetType newPetType = MyPetType.byEntityTypeName(targetType);
            newInactiveMyPet.setPetType(newPetType);
            newInactiveMyPet.setPetName(originalName);

            WorldGroup wg = WorldGroup.getGroupByWorld(player.getWorld());
            newInactiveMyPet.setWorldGroup(wg.getName());
            newInactiveMyPet.getOwner().setMyPetForWorldGroup(wg, newInactiveMyPet.getUUID());

            // InactiveMyPet stores raw exp (no getExperience()), set directly
            newInactiveMyPet.setExp(backupExp);

            if (backupSkilltree != null) {
                de.Keyle.MyPet.api.skill.skilltree.Skilltree st =
                        MyPetApi.getSkilltreeManager().getSkilltree(backupSkilltree);
                if (st != null) {
                    newInactiveMyPet.setSkilltree(st);
                }
            }

            // Register with MyPet repository and activate
            UUID newMypetUuid = newInactiveMyPet.getUUID();
            InactiveMyPet finalNewInactive = newInactiveMyPet;
            MyPetApi.getRepository().addMyPet(finalNewInactive, new RepositoryCallback<Boolean>() {
                @Override
                public void callback(Boolean value) {
                    var activePet = MyPetApi.getMyPetManager()
                            .activateMyPet(finalNewInactive)
                            .orElse(null);
                    if (activePet != null) {
                        activePet.createEntity();
                    }
                }
            });

            // ── Step 5: UPDATE ADDON DATA ──
            // Update PetData with new MyPet UUID and mob type
            PetData updatedData = backupData
                    .withMypetUuid(newMypetUuid)
                    .withMobType(targetType);

            // Apply evolution stat bonuses to upgraded values
            // Stat bonus values are multipliers (e.g. 1.15 = +15% of base)
            PetStats updatedStats = backupStats != null ? backupStats : new PetStats(
                    addonPetId, Map.of(), Map.of());
            for (Map.Entry<String, Double> bonus : check.statBonus().entrySet()) {
                double baseValue = updatedStats.baseValues().getOrDefault(bonus.getKey(), 0.0);
                double currentUpgraded = updatedStats.upgradedValues().getOrDefault(bonus.getKey(), 0.0);
                // Apply multiplier to base and add as upgrade bonus
                double evolutionBonus = baseValue * (bonus.getValue() - 1.0);
                updatedStats = updatedStats.withUpgradedStat(
                        bonus.getKey(), currentUpgraded + evolutionBonus);
            }

            // Update cache (old key invalidated, new key inserted)
            petDataCache.invalidate(backupMypetUuid);
            petDataCache.put(updatedData, updatedStats);

            // Record evolution history asynchronously
            saveEvolutionHistory(addonPetId, fromType, targetType,
                    backupMypetUuid, newMypetUuid);

            // Reapply stats via StatsManager on next tick (after MyPet activation)
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                MyPet activePet = MyPetApi.getMyPetManager().getMyPet(player);
                if (activePet != null) {
                    statsManager.applyStats(activePet);
                }
            }, 5L);

            // ── Consume required item ──
            consumeRequiredItem(player, fromType);

            // ── Visual/audio feedback ──
            SoundUtil.playSound(player, "evolution", configManager);
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING,
                    player.getLocation().add(0, 1, 0), 50, 1.0, 1.5, 1.0, 0.3);

            player.sendMessage("§a§l進化成功！ §f" + originalName
                    + " §7が §e" + fromType + " §7から §a" + targetType + " §7に進化しました！");

            return true;

        } catch (Exception e) {
            // ── Step 6: ROLLBACK ──
            logger.log(Level.SEVERE, "Evolution failed for pet " + addonPetId
                    + ", attempting rollback", e);

            rollback(player, myPetPlayer(player), backupData, backupStats,
                    backupMypetUuid, originalName, backupExp,
                    backupSkilltree, newInactiveMyPet);

            player.sendMessage("§c進化に失敗しました。データをロールバックしました。");
            return false;
        }
    }

    // ─── Evolution Chain ────────────────────────────────────────

    /**
     * Returns the evolution target for a mob type from config.
     *
     * @param mobType the source mob type name
     * @return the target mob type, or empty if no evolution exists
     */
    @NotNull
    public Optional<String> getEvolutionTarget(@NotNull String mobType) {
        String target = configManager.getConfig()
                .getString("evolutions." + mobType + ".target", "");
        return target.isEmpty() ? Optional.empty() : Optional.of(target);
    }

    /**
     * Follows the evolves-to chain to build a full evolution path.
     * Example: ZOMBIE -> HUSK -> DROWNED
     *
     * @param mobType the starting mob type
     * @return the ordered list of mob types in the evolution chain
     */
    @NotNull
    public List<String> getEvolutionChain(@NotNull String mobType) {
        List<String> chain = new ArrayList<>();
        chain.add(mobType);

        String current = mobType;
        int safetyLimit = 20; // Prevent infinite loops from circular config

        while (safetyLimit-- > 0) {
            Optional<String> next = getEvolutionTarget(current);
            if (next.isEmpty() || chain.contains(next.get())) {
                break;
            }
            chain.add(next.get());
            current = next.get();
        }

        return List.copyOf(chain);
    }

    // ─── Evolution History ──────────────────────────────────────

    /**
     * Records an evolution event in the evolution_history table.
     * Executes asynchronously via Bukkit scheduler.
     *
     * @param addonPetId    the addon pet ID
     * @param fromType      the original mob type
     * @param toType        the evolved mob type
     * @param fromMypetUuid the old MyPet UUID
     * @param toMypetUuid   the new MyPet UUID
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

    // ─── Inner Records ──────────────────────────────────────────

    /**
     * Result of an evolution eligibility check.
     *
     * @param canEvolve whether evolution is possible
     * @param reason    human-readable reason (Japanese)
     * @param targetType the mob type the pet would evolve into (empty if not eligible)
     * @param statBonus  stat bonuses applied on evolution
     */
    public record EvolutionCheck(
            boolean canEvolve,
            @NotNull String reason,
            @NotNull String targetType,
            @NotNull Map<String, Double> statBonus
    ) {
        public EvolutionCheck {
            statBonus = Map.copyOf(statBonus);
        }

        @NotNull
        static EvolutionCheck fail(@NotNull String reason) {
            return new EvolutionCheck(false, reason, "", Map.of());
        }
    }

    // ─── Internal Helpers ───────────────────────────────────────

    /**
     * Parses stat bonuses from the evolution config section.
     */
    @NotNull
    private Map<String, Double> parseStatBonus(@NotNull ConfigurationSection evoSection) {
        ConfigurationSection bonusSection = evoSection.getConfigurationSection("stat-bonus");
        if (bonusSection == null) {
            return Map.of();
        }

        Map<String, Double> bonuses = new LinkedHashMap<>();
        for (String statName : bonusSection.getKeys(false)) {
            bonuses.put(statName, bonusSection.getDouble(statName, 1.0));
        }
        return Collections.unmodifiableMap(bonuses);
    }

    /**
     * Consumes the required evolution item from the player's inventory.
     */
    private void consumeRequiredItem(@NotNull Player player, @NotNull String mobType) {
        String requiredItemName = configManager.getConfig()
                .getString("evolutions." + mobType + ".conditions.required-item", "");
        if (requiredItemName.isEmpty()) {
            return;
        }

        Material material = Material.matchMaterial(requiredItemName);
        if (material == null) {
            return;
        }

        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() == material) {
                item.setAmount(item.getAmount() - 1);
                if (item.getAmount() <= 0) {
                    player.getInventory().setItem(i, null);
                }
                break;
            }
        }
    }

    /**
     * Gets the MyPetPlayer for a Bukkit Player, returning null on failure.
     */
    @NotNull
    private MyPetPlayer myPetPlayer(@NotNull Player player) {
        return MyPetApi.getPlayerManager().getMyPetPlayer(player);
    }

    /**
     * Attempts to rollback a failed evolution by recreating the old MyPet
     * and restoring addon data.
     */
    private void rollback(@NotNull Player player,
                          @NotNull MyPetPlayer myPetPlayer,
                          @NotNull PetData backupData,
                          @NotNull PetStats backupStats,
                          @NotNull UUID backupMypetUuid,
                          @NotNull String originalName,
                          double backupExp,
                          String backupSkilltree,
                          InactiveMyPet failedNewPet) {
        try {
            // Remove the failed new MyPet if it was created
            if (failedNewPet != null) {
                MyPetApi.getMyPetManager().deactivateMyPet(myPetPlayer, false);
                MyPetApi.getRepository().removeMyPet(failedNewPet.getUUID(),
                        new RepositoryCallback<>() {
                            @Override
                            public void callback(Boolean value) {
                                // Cleanup complete
                            }
                        });
            }

            // Recreate old MyPet
            InactiveMyPet restoredPet = new InactiveMyPet(myPetPlayer);
            MyPetType oldType = MyPetType.byEntityTypeName(backupData.mobType());
            restoredPet.setPetType(oldType);
            restoredPet.setPetName(originalName);

            WorldGroup wg = WorldGroup.getGroupByWorld(player.getWorld());
            restoredPet.setWorldGroup(wg.getName());
            restoredPet.getOwner().setMyPetForWorldGroup(wg, restoredPet.getUUID());

            // InactiveMyPet stores raw exp directly (no getExperience())
            restoredPet.setExp(backupExp);

            if (backupSkilltree != null) {
                de.Keyle.MyPet.api.skill.skilltree.Skilltree st =
                        MyPetApi.getSkilltreeManager().getSkilltree(backupSkilltree);
                if (st != null) {
                    restoredPet.setSkilltree(st);
                }
            }

            MyPetApi.getRepository().addMyPet(restoredPet, new RepositoryCallback<Boolean>() {
                @Override
                public void callback(Boolean value) {
                    var activePet = MyPetApi.getMyPetManager()
                            .activateMyPet(restoredPet)
                            .orElse(null);
                    if (activePet != null) {
                        activePet.createEntity();
                    }
                }
            });

            // Restore addon data in cache
            PetData restoredData = backupData.withMypetUuid(restoredPet.getUUID());
            petDataCache.invalidate(backupMypetUuid);
            if (backupStats != null) {
                petDataCache.put(restoredData, backupStats);
            }

            logger.info("[Evolution] Rollback completed for pet: " + backupData.addonPetId());

        } catch (Exception rollbackError) {
            logger.log(Level.SEVERE,
                    "[Evolution] CRITICAL: Rollback also failed for pet: "
                            + backupData.addonPetId(), rollbackError);
        }
    }
}
