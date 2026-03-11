package com.mypetaddon.command;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.bond.BondLevel;
import com.mypetaddon.bond.BondManager;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.PetStats;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.rarity.Rarity;
import com.mypetaddon.stats.ModifierPipeline;
import com.mypetaddon.stats.StatsManager;
import com.mypetaddon.evolution.EvolutionManager;
import com.mypetaddon.taming.TamingManager;
import de.Keyle.MyPet.MyPetApi;
import de.Keyle.MyPet.api.entity.MyPet;
import de.Keyle.MyPet.api.player.MyPetPlayer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Registers and handles all plugin commands using the Bukkit CommandExecutor pattern.
 * Supports /petname, /petstatus, and /petadmin with tab completion.
 */
public final class PetCommandManager implements CommandExecutor, TabCompleter {

    private static final int MAX_PET_NAME_LENGTH = 32;
    private static final String PREFIX = "§8[§6MyPetAddon§8] §r";

    private static final List<String> ADMIN_SUBCOMMANDS = List.of(
            "reload", "setrarity", "setbond", "migrate"
    );

    private final MyPetAddonPlugin plugin;
    private final TamingManager tamingManager;
    private final PetDataCache petDataCache;
    private final StatsManager statsManager;
    private final BondManager bondManager;
    private final ConfigManager configManager;
    private final Logger logger;

    public PetCommandManager(@NotNull MyPetAddonPlugin plugin,
                             @NotNull TamingManager tamingManager,
                             @NotNull PetDataCache petDataCache,
                             @NotNull StatsManager statsManager,
                             @NotNull BondManager bondManager,
                             @NotNull ConfigManager configManager) {
        this.plugin = plugin;
        this.tamingManager = tamingManager;
        this.petDataCache = petDataCache;
        this.statsManager = statsManager;
        this.bondManager = bondManager;
        this.configManager = configManager;
        this.logger = plugin.getLogger();
    }

    /**
     * Registers all plugin commands with this executor and tab completer.
     * Commands must be declared in plugin.yml.
     */
    public void registerCommands() {
        registerCommand("petname");
        registerCommand("petstatus");
        registerCommand("petadmin");
        registerCommand("petevolve");
        registerCommand("petequip");
        registerCommand("petdex");
    }

    private void registerCommand(@NotNull String name) {
        var command = plugin.getCommand(name);
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        } else {
            logger.warning("[Commands] Command '" + name + "' not found in plugin.yml!");
        }
    }

    // ─── Command Dispatch ────────────────────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "petname" -> handlePetName(sender, args);
            case "petstatus" -> handlePetStatus(sender);
            case "petadmin" -> handlePetAdmin(sender, args);
            case "petevolve" -> handlePetEvolve(sender);
            case "petequip" -> handlePetEquip(sender);
            case "petdex" -> handlePetDex(sender);
            default -> false;
        };
    }

    // ─── /petname <name> ─────────────────────────────────────────

    private boolean handlePetName(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "§cThis command can only be used by players.");
            return true;
        }

        if (!player.hasPermission("mypetaddon.tame")) {
            player.sendMessage(PREFIX + "§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(PREFIX + "§cUsage: /petname <name>");
            return true;
        }

        String name = String.join(" ", args);

        if (name.isBlank()) {
            player.sendMessage(PREFIX + "§cPet name cannot be empty.");
            return true;
        }

        if (name.length() > MAX_PET_NAME_LENGTH) {
            player.sendMessage(PREFIX + "§cPet name must be " + MAX_PET_NAME_LENGTH + " characters or fewer.");
            return true;
        }

        tamingManager.handlePetName(player, name);
        return true;
    }

    // ─── /petstatus ──────────────────────────────────────────────

    private boolean handlePetStatus(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "§cThis command can only be used by players.");
            return true;
        }

        if (!player.hasPermission("mypetaddon.status")) {
            player.sendMessage(PREFIX + "§cYou do not have permission to use this command.");
            return true;
        }

        MyPet myPet = getActiveMyPet(player);
        if (myPet == null) {
            player.sendMessage(PREFIX + "§cYou do not have an active pet.");
            return true;
        }

        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            player.sendMessage(PREFIX + "§cNo addon data found for this pet. It may not have been tamed through MyPetAddon.");
            return true;
        }

        PetStats petStats = petDataCache.getStats(petData.addonPetId());
        displayPetStatus(player, myPet, petData, petStats);
        return true;
    }

    private void displayPetStatus(@NotNull Player player,
                                  @NotNull MyPet myPet,
                                  @NotNull PetData petData,
                                  @Nullable PetStats petStats) {
        Rarity rarity = petData.rarity();
        int bondLevel = petData.bondLevel();
        int bondExp = petData.bondExp();

        player.sendMessage("§8§m─────────────────────────────────");
        player.sendMessage("  " + rarity.getColor() + "§l" + myPet.getPetName()
                + " §8| " + rarity.getColoredName());
        player.sendMessage("  §7Type: §f" + petData.mobType()
                + " §8| §7Personality: §f" + petData.personality().getDisplayName());
        player.sendMessage("");

        // Bond level + progress bar
        String bondBar = buildProgressBar(bondExp, bondLevel);
        player.sendMessage("  §7Bond: §e" + bondLevelLabel(bondLevel) + " §8(Lv." + bondLevel + ")");
        player.sendMessage("  " + bondBar + " §7" + bondExp + " EXP");
        player.sendMessage("");

        // Stat breakdown (use ModifierPipeline for accurate final values)
        if (petStats != null) {
            ModifierPipeline pipeline = plugin.getStatsManager().getModifierPipeline();
            player.sendMessage("  §7§nStat Breakdown:");
            java.util.Set<String> allStatNames = new java.util.LinkedHashSet<>(petStats.baseValues().keySet());
            allStatNames.addAll(petStats.upgradedValues().keySet());
            for (String statName : allStatNames) {
                double base = petStats.baseValues().getOrDefault(statName, 0.0);
                double upgraded = petStats.upgradedValues().getOrDefault(statName, 0.0);
                double rarityMult = rarity.getStatMultiplier();
                double personalityMod = petData.personality().getModifier(statName, 1.0);
                double bondBonus = BondLevel.getStatBonus(bondLevel, statName);
                double finalValue = pipeline.calculate(statName, petData, petStats);

                player.sendMessage(String.format(
                        "  §7%s: §f%.1f §8(base+%.1f) §7x§e%.2f §8(rarity) §7x§b%.2f §8(personality) §7+§a%.2f §8(bond) §7= §f%.1f",
                        capitalize(statName), base, upgraded, rarityMult, personalityMod, bondBonus, finalValue
                ));
            }
        } else {
            player.sendMessage("  §7Stats: §8(not available)");
        }

        player.sendMessage("");
        player.sendMessage("  §7Awakening: " + (rarity.isAwakeningEligible() ? "§aEligible" : "§8Ineligible"));
        player.sendMessage("§8§m─────────────────────────────────");
    }

    @NotNull
    private String buildProgressBar(int currentExp, int bondLevel) {
        int currentLevelExp = BondLevel.getExpForLevel(bondLevel);
        int expToNext = BondLevel.getExpToNextLevel(bondLevel);

        if (expToNext == 0) {
            // Max level
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
            case 1 -> "Stranger";
            case 2 -> "Acquaintance";
            case 3 -> "Companion";
            case 4 -> "Trusted";
            case 5 -> "Soulbound";
            default -> "Unknown";
        };
    }

    // ─── /petevolve ─────────────────────────────────────────────

    private boolean handlePetEvolve(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "§cThis command can only be used by players.");
            return true;
        }
        if (!player.hasPermission("mypetaddon.evolve")) {
            player.sendMessage(PREFIX + "§cYou do not have permission to use this command.");
            return true;
        }

        MyPet myPet = getActiveMyPet(player);
        if (myPet == null) {
            player.sendMessage(PREFIX + "§cYou do not have an active pet.");
            return true;
        }

        EvolutionManager evoManager = plugin.getEvolutionManager();
        EvolutionManager.EvolutionCheck check = evoManager.canEvolve(myPet);
        if (!check.canEvolve()) {
            player.sendMessage(PREFIX + "§c" + check.reason());
            return true;
        }

        // Open confirmation GUI
        plugin.getEvolutionGUI().openConfirmation(player, myPet, check);
        return true;
    }

    // ─── /petequip ─────────────────────────────────────────────

    private boolean handlePetEquip(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "§cThis command can only be used by players.");
            return true;
        }
        if (!player.hasPermission("mypetaddon.equip")) {
            player.sendMessage(PREFIX + "§cYou do not have permission to use this command.");
            return true;
        }

        MyPet myPet = getActiveMyPet(player);
        if (myPet == null) {
            player.sendMessage(PREFIX + "§cYou do not have an active pet.");
            return true;
        }

        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            player.sendMessage(PREFIX + "§cNo addon data found for this pet.");
            return true;
        }

        plugin.getEquipmentGUI().openEquipmentGUI(player, petData);
        return true;
    }

    // ─── /petdex ──────────────────────────────────────────────

    private boolean handlePetDex(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "§cThis command can only be used by players.");
            return true;
        }
        if (!player.hasPermission("mypetaddon.encyclopedia")) {
            player.sendMessage(PREFIX + "§cYou do not have permission to use this command.");
            return true;
        }

        plugin.getEncyclopediaGUI().openEncyclopedia(player, 0);
        return true;
    }

    // ─── /petadmin ───────────────────────────────────────────────

    private boolean handlePetAdmin(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission("mypetaddon.admin")) {
            sender.sendMessage(PREFIX + "§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendAdminUsage(sender);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleAdminReload(sender);
            case "setrarity" -> handleAdminSetRarity(sender, args);
            case "setbond" -> handleAdminSetBond(sender, args);
            case "migrate" -> handleAdminMigrate(sender);
            default -> {
                sendAdminUsage(sender);
                yield true;
            }
        };
    }

    private boolean handleAdminReload(@NotNull CommandSender sender) {
        sender.sendMessage(PREFIX + "§7Reloading configuration...");

        ConfigManager.ValidationResult result = configManager.reload();
        if (result.valid()) {
            // Reapply all active pet stats with new config values
            statsManager.applyAllActivePets();
            sender.sendMessage(PREFIX + "§aConfiguration reloaded successfully. Stats reapplied.");
        } else {
            sender.sendMessage(PREFIX + "§cReload failed! Keeping previous config.");
            for (String error : result.errors()) {
                sender.sendMessage(PREFIX + "§c  - " + error);
            }
        }
        return true;
    }

    private boolean handleAdminSetRarity(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "§cUsage: /petadmin setrarity <player> <rarity>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(PREFIX + "§cPlayer '" + args[1] + "' not found or offline.");
            return true;
        }

        Rarity newRarity;
        try {
            newRarity = Rarity.fromString(args[2]);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(PREFIX + "§cInvalid rarity: '" + args[2] + "'. Valid: "
                    + Arrays.toString(Rarity.values()));
            return true;
        }

        MyPet myPet = getActiveMyPet(target);
        if (myPet == null) {
            sender.sendMessage(PREFIX + "§cPlayer '" + target.getName() + "' has no active pet.");
            return true;
        }

        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            sender.sendMessage(PREFIX + "§cNo addon data found for that player's pet.");
            return true;
        }

        // Create updated PetData with new rarity
        PetData updated = new PetData(
                petData.addonPetId(), petData.mypetUuid(), petData.ownerUuid(),
                petData.mobType(), newRarity, petData.personality(),
                petData.bondLevel(), petData.bondExp(),
                petData.originalLmLevel(), petData.createdAt(), petData.evolvedFrom()
        );

        PetStats currentStats = petDataCache.getStats(petData.addonPetId());
        if (currentStats != null) {
            petDataCache.put(updated, currentStats);
        }

        // Reapply stats with new rarity
        statsManager.applyAllActivePets();

        sender.sendMessage(PREFIX + "§aSet " + target.getName() + "'s pet rarity to "
                + newRarity.getColoredName() + "§a.");
        return true;
    }

    private boolean handleAdminSetBond(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "§cUsage: /petadmin setbond <player> <amount>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(PREFIX + "§cPlayer '" + args[1] + "' not found or offline.");
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(PREFIX + "§cInvalid number: '" + args[2] + "'.");
            return true;
        }

        if (amount < 0) {
            sender.sendMessage(PREFIX + "§cBond amount must be non-negative.");
            return true;
        }

        MyPet myPet = getActiveMyPet(target);
        if (myPet == null) {
            sender.sendMessage(PREFIX + "§cPlayer '" + target.getName() + "' has no active pet.");
            return true;
        }

        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            sender.sendMessage(PREFIX + "§cNo addon data found for that player's pet.");
            return true;
        }

        int newBondLevel = BondLevel.fromExp(amount);
        petDataCache.updateBond(petData.addonPetId(), newBondLevel, amount);

        sender.sendMessage(PREFIX + "§aSet " + target.getName() + "'s pet bond to "
                + amount + " EXP (Level " + newBondLevel + ").");
        return true;
    }

    private boolean handleAdminMigrate(@NotNull CommandSender sender) {
        sender.sendMessage(PREFIX + "§7Triggering legacy data migration...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var migrationManager = new com.mypetaddon.data.MigrationManager(
                        plugin, new com.mypetaddon.data.PetDataRepository(plugin.getDatabaseManager()));
                migrationManager.migrateIfNeeded();

                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(PREFIX + "§aMigration completed. Check console for details."));
            } catch (Exception e) {
                logger.warning("[Commands] Migration failed: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(PREFIX + "§cMigration failed! Check console for errors."));
            }
        });
        return true;
    }

    private void sendAdminUsage(@NotNull CommandSender sender) {
        sender.sendMessage(PREFIX + "§7Admin commands:");
        sender.sendMessage("  §e/petadmin reload §8- §7Reload configuration");
        sender.sendMessage("  §e/petadmin setrarity <player> <rarity> §8- §7Set pet rarity");
        sender.sendMessage("  §e/petadmin setbond <player> <amount> §8- §7Set pet bond EXP");
        sender.sendMessage("  §e/petadmin migrate §8- §7Run legacy data migration");
    }

    // ─── Tab Completion ──────────────────────────────────────────

    @Override
    @Nullable
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String alias,
                                      @NotNull String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "petname" -> Collections.emptyList();
            case "petstatus" -> Collections.emptyList();
            case "petadmin" -> completeAdmin(sender, args);
            case "petevolve" -> Collections.emptyList();
            case "petequip" -> Collections.emptyList();
            case "petdex" -> Collections.emptyList();
            default -> null;
        };
    }

    @NotNull
    private List<String> completeAdmin(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission("mypetaddon.admin")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filterStartsWith(ADMIN_SUBCOMMANDS, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (args.length == 2 && ("setrarity".equals(sub) || "setbond".equals(sub))) {
            return filterStartsWith(getOnlinePlayerNames(), args[1]);
        }

        if (args.length == 3 && "setrarity".equals(sub)) {
            List<String> rarities = Arrays.stream(Rarity.values())
                    .map(r -> r.name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toList());
            return filterStartsWith(rarities, args[2]);
        }

        if (args.length == 3 && "setbond".equals(sub)) {
            return List.of("100", "350", "750", "1500");
        }

        return Collections.emptyList();
    }

    // ─── Utility Methods ─────────────────────────────────────────

    @Nullable
    private MyPet getActiveMyPet(@NotNull Player player) {
        try {
            MyPetPlayer myPetPlayer = MyPetApi.getPlayerManager().getMyPetPlayer(player);
            if (myPetPlayer != null && myPetPlayer.hasMyPet()) {
                return myPetPlayer.getMyPet();
            }
        } catch (Exception e) {
            logger.warning("[Commands] Failed to get MyPet for player "
                    + player.getName() + ": " + e.getMessage());
        }
        return null;
    }

    @NotNull
    private List<String> getOnlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());
    }

    @NotNull
    private List<String> filterStartsWith(@NotNull List<String> options, @NotNull String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .collect(Collectors.toList());
    }

    @NotNull
    private String capitalize(@NotNull String str) {
        if (str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase(Locale.ROOT) + str.substring(1);
    }
}
