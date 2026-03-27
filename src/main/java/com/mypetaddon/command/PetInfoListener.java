package com.mypetaddon.command;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.bond.BondLevel;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.config.ConfigManager.RerollItemEntry;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.PetStats;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.stats.ModifierPipeline;
import de.Keyle.MyPet.MyPetApi;
import de.Keyle.MyPet.api.entity.MyPet;
import de.Keyle.MyPet.api.player.MyPetPlayer;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Intercepts MyPet commands to integrate addon information.
 * - /petinfo: Appends rarity, personality, bond, and stats after MyPet's output
 * - /petchooseskilltree: Blocks manual skilltree selection (addon manages this)
 */
public final class PetInfoListener implements Listener {

    private static final String PREFIX = "§8[§6MyPetAddon§8] §r";

    private final MyPetAddonPlugin plugin;
    private final PetDataCache petDataCache;

    public PetInfoListener(@NotNull MyPetAddonPlugin plugin,
                           @NotNull PetDataCache petDataCache) {
        this.plugin = plugin;
        this.petDataCache = petDataCache;
    }

    /**
     * Blocks /petchooseskilltree at NORMAL priority (allowed to cancel).
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onBlockSkilltreeCommand(@NotNull PlayerCommandPreprocessEvent event) {
        if (event.isCancelled()) {
            return;
        }

        String message = event.getMessage().toLowerCase().trim();

        if (message.startsWith("/petchooseskilltree") || message.startsWith("/pcst")
                || message.startsWith("/petcst")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(PREFIX + "§cスキルツリーはアドオンが自動管理しています。");
            event.getPlayer().sendMessage(PREFIX + "§7変更するには §e" + getSkilltreeRerollItemName() + " §7をペットに右クリックしてください。");
        }
    }

    /**
     * Augments /petinfo output at MONITOR priority (read-only, never cancels).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPetInfoCommand(@NotNull PlayerCommandPreprocessEvent event) {
        if (event.isCancelled()) {
            return;
        }

        String message = event.getMessage().toLowerCase().trim();

        if (message.equals("/petinfo") || message.startsWith("/petinfo ")) {
            Player player = event.getPlayer();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    appendAddonInfo(player);
                }
            }, 1L);
        }
    }

    private void appendAddonInfo(@NotNull Player player) {
        MyPet myPet = getActiveMyPet(player);
        if (myPet == null) {
            return;
        }

        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            return;
        }

        PetStats petStats = petDataCache.getStats(petData.addonPetId());
        ModifierPipeline pipeline = plugin.getStatsManager().getModifierPipeline();

        // Header
        player.sendMessage("");
        player.sendMessage("§8§m──────────── §r§6 アドオン情報 §r§8§m────────────");

        // Rarity
        player.sendMessage("§8 ▸ §7レアリティ: " + petData.rarity().getColoredName());

        // Personality (Japanese display name from config)
        String personalityName = plugin.getConfigManager().getString(
                "personalities." + petData.personality().name() + ".display-name", "");
        if (personalityName.isEmpty()) personalityName = petData.personality().getDisplayName();
        player.sendMessage("§8 ▸ §7性格: §f" + personalityName);

        // Bond
        int bondLevel = petData.bondLevel();
        int bondExp = petData.bondExp();
        String bondLabel = bondLevelLabel(bondLevel);
        String bondBar = buildProgressBar(bondExp, bondLevel);
        player.sendMessage("§8 ▸ §7好感度: §e" + bondLabel + " §8(Lv." + bondLevel + ") "
                + bondBar + " §7" + bondExp + " EXP");

        // Stats
        if (petStats != null) {
            StringBuilder statLine = new StringBuilder("§8 ▸ §7ステータス: ");
            Set<String> allStatNames = new LinkedHashSet<>(petStats.baseValues().keySet());
            allStatNames.addAll(petStats.upgradedValues().keySet());
            boolean first = true;
            int petLevel = 0;
            try { petLevel = myPet.getExperience().getLevel(); } catch (Exception ignored) {}
            for (String statName : allStatNames) {
                double finalValue = pipeline.calculate(statName, petData, petStats, petLevel);
                if (!first) {
                    statLine.append(" §8| ");
                }
                // Life and Damage as integers, Speed as decimal
                String formatted = ("Life".equals(statName) || "Damage".equals(statName))
                        ? String.format("%.0f", finalValue)
                        : String.format("%.1f", finalValue);
                statLine.append("§f").append(translateStatName(statName))
                        .append(" §e").append(formatted);
                first = false;
            }
            player.sendMessage(statLine.toString());
        }

        player.sendMessage("§8§m─────────────────────────────────────");
    }

    @NotNull
    private String buildProgressBar(int currentExp, int bondLevel) {
        int currentLevelExp = BondLevel.getExpForLevel(bondLevel);
        int expToNext = BondLevel.getExpToNextLevel(bondLevel);

        if (expToNext == 0) {
            return "§a||||||||||||||||||||§8 §7MAX";
        }

        int progressInLevel = currentExp - currentLevelExp;
        int barLength = 20;
        int filled = (int) ((double) progressInLevel / expToNext * barLength);
        filled = Math.max(0, Math.min(barLength, filled));

        return "§a" + "|".repeat(filled) + "§7" + "|".repeat(barLength - filled);
    }

    @NotNull
    private String bondLevelLabel(int level) {
        return switch (level) {
            case 1 -> "初対面";
            case 2 -> "知人";
            case 3 -> "仲間";
            case 4 -> "信頼";
            case 5 -> "魂の絆";
            default -> "不明";
        };
    }

    @NotNull
    private String translateStatName(@NotNull String statName) {
        return switch (statName) {
            case "Life" -> "体力";
            case "Damage" -> "攻撃力";
            case "Speed" -> "速度";
            default -> statName;
        };
    }

    /**
     * Resolves the display name of the first skilltree-reroll item from config.
     */
    @NotNull
    private String getSkilltreeRerollItemName() {
        List<RerollItemEntry> items = plugin.getConfigManager().getRerollItems();
        for (RerollItemEntry entry : items) {
            if ("skilltree".equals(entry.type())) {
                String descriptor = entry.itemDescriptor();
                if (!descriptor.startsWith("base64:")) {
                    try {
                        Material mat = Material.valueOf(descriptor.toUpperCase());
                        // Convert e.g. COPPER_BLOCK -> Copper Block
                        String[] parts = mat.name().toLowerCase().split("_");
                        StringBuilder sb = new StringBuilder();
                        for (String part : parts) {
                            if (!sb.isEmpty()) sb.append(' ');
                            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
                        }
                        return sb.toString();
                    } catch (IllegalArgumentException ignored) {
                        return descriptor;
                    }
                }
                return "リロールアイテム";
            }
        }
        return "リロールアイテム";
    }

    @Nullable
    private MyPet getActiveMyPet(@NotNull Player player) {
        try {
            MyPetPlayer myPetPlayer = MyPetApi.getPlayerManager().getMyPetPlayer(player);
            if (myPetPlayer != null && myPetPlayer.hasMyPet()) {
                return myPetPlayer.getMyPet();
            }
        } catch (Exception e) {
            // Silently fail
        }
        return null;
    }
}
