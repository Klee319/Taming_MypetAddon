package com.mypetaddon.encyclopedia;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.EncyclopediaRepository;
import com.mypetaddon.data.EncyclopediaRepository.EncyclopediaEntry;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the encyclopedia (Pokedex) system.
 * Provides async data access, completion tracking, and reward distribution.
 */
public final class EncyclopediaManager {

    private static final Logger LOGGER = Logger.getLogger(EncyclopediaManager.class.getName());

    private final MyPetAddonPlugin plugin;
    private final ConfigManager configManager;
    private final EncyclopediaRepository repository;

    // PDC keys for tracking granted rewards
    private final NamespacedKey keyReward25;
    private final NamespacedKey keyReward50;
    private final NamespacedKey keyReward75;
    private final NamespacedKey keyReward100;

    public EncyclopediaManager(@NotNull MyPetAddonPlugin plugin,
                               @NotNull ConfigManager configManager,
                               @NotNull EncyclopediaRepository repository) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.repository = repository;

        this.keyReward25 = new NamespacedKey(plugin, "encyclopedia_reward_25");
        this.keyReward50 = new NamespacedKey(plugin, "encyclopedia_reward_50");
        this.keyReward75 = new NamespacedKey(plugin, "encyclopedia_reward_75");
        this.keyReward100 = new NamespacedKey(plugin, "encyclopedia_reward_100");
    }

    // ─── Data Access (Async) ────────────────────────────────────

    /**
     * Loads all encyclopedia entries for a player asynchronously.
     */
    @NotNull
    public CompletableFuture<List<EncyclopediaEntry>> getPlayerEntries(@NotNull UUID ownerUuid) {
        return CompletableFuture.supplyAsync(() -> repository.getEntries(ownerUuid));
    }

    /**
     * Calculates completion percentage for a player asynchronously.
     */
    @NotNull
    public CompletableFuture<Double> getCompletionPercent(@NotNull UUID ownerUuid) {
        int totalTypes = getTameableTypes().size();
        return CompletableFuture.supplyAsync(() -> repository.getCompletionPercent(ownerUuid, totalTypes));
    }

    /**
     * Checks whether a player has completed the entire encyclopedia.
     */
    @NotNull
    public CompletableFuture<Boolean> isComplete(@NotNull UUID ownerUuid) {
        return getCompletionPercent(ownerUuid).thenApply(percent -> percent >= 100.0);
    }

    // ─── Tameable Types ─────────────────────────────────────────

    /**
     * Collects all mob types defined in the pet-base-values config section.
     * Iterates through all tiers and gathers mob type names from their "types" lists.
     */
    @NotNull
    public List<String> getTameableTypes() {
        var config = configManager.getConfig();
        var baseSection = config.getConfigurationSection("pet-base-values");
        if (baseSection == null) {
            return Collections.emptyList();
        }

        List<String> types = new ArrayList<>();
        for (String tierKey : baseSection.getKeys(false)) {
            var tierSection = baseSection.getConfigurationSection(tierKey);
            if (tierSection == null) {
                continue;
            }

            // Handle nested structure (e.g., special.wither.types)
            List<String> directTypes = tierSection.getStringList("types");
            if (!directTypes.isEmpty()) {
                types.addAll(directTypes);
            } else {
                // Check for sub-sections (special tier has nested groups)
                for (String subKey : tierSection.getKeys(false)) {
                    var subSection = tierSection.getConfigurationSection(subKey);
                    if (subSection != null) {
                        List<String> subTypes = subSection.getStringList("types");
                        types.addAll(subTypes);
                    }
                }
            }
        }

        // Deduplicate while preserving order
        List<String> deduplicated = new ArrayList<>();
        for (String type : types) {
            if (!deduplicated.contains(type)) {
                deduplicated.add(type);
            }
        }

        return Collections.unmodifiableList(deduplicated);
    }

    // ─── Reward System ──────────────────────────────────────────

    /**
     * Checks completion thresholds and grants rewards that haven't been claimed yet.
     * Must be called on the main thread.
     */
    public void checkAndGrantRewards(@NotNull Player player, double completionPercent) {
        checkThreshold(player, completionPercent, 25, keyReward25);
        checkThreshold(player, completionPercent, 50, keyReward50);
        checkThreshold(player, completionPercent, 75, keyReward75);
        checkThreshold(player, completionPercent, 100, keyReward100);
    }

    /**
     * Checks a single threshold and grants the reward if eligible and not already claimed.
     */
    private void checkThreshold(@NotNull Player player, double completionPercent,
                                int threshold, @NotNull NamespacedKey rewardKey) {
        if (completionPercent < threshold) {
            return;
        }

        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (pdc.has(rewardKey, PersistentDataType.BYTE)) {
            return; // Already claimed
        }

        // Mark as claimed
        pdc.set(rewardKey, PersistentDataType.BYTE, (byte) 1);

        // Execute reward commands from config
        var config = configManager.getConfig();
        String basePath = "encyclopedia.completion-rewards." + threshold;

        List<String> commands = config.getStringList(basePath + ".commands");
        String message = config.getString(basePath + ".message", "");

        String playerName = player.getName();
        for (String command : commands) {
            String resolved = command.replace("%player%", playerName);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
        }

        // Send reward message
        if (!message.isEmpty()) {
            String prefix = configManager.getString("messages.prefix", "");
            player.sendMessage(prefix + message);
        }

        LOGGER.info("Granted encyclopedia " + threshold + "% reward to " + playerName);
    }
}
