package com.mypetaddon.command;

import com.mypetaddon.MyPetAddonPlugin;
import de.Keyle.MyPet.api.entity.MyPet;
import de.Keyle.MyPet.api.skill.skilltree.Skilltree;
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
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Inventory GUI that displays the pet's skilltree organized by unlock level.
 * Each item represents a level stage, showing all skills unlocked at that level.
 * Unlocked stages are green; locked stages are red.
 */
public final class PetSkillGUI implements Listener {

    private static final String GUI_TITLE_PREFIX = "§8§lスキルツリー: ";
    private static final int ROWS = 3;
    private static final int SIZE = ROWS * 9;

    private static final Map<String, String> SKILL_NAMES_JP = Map.ofEntries(
            Map.entry("Damage", "攻撃力"),
            Map.entry("Life", "体力"),
            Map.entry("Heal", "回復"),
            Map.entry("Thorns", "トゲ"),
            Map.entry("Ranged", "遠距離攻撃"),
            Map.entry("Fire", "火炎"),
            Map.entry("Poison", "毒"),
            Map.entry("Slow", "減速"),
            Map.entry("Sprint", "スプリント"),
            Map.entry("Knockback", "ノックバック"),
            Map.entry("Ride", "ライド"),
            Map.entry("Beacon", "ビーコン"),
            Map.entry("Backpack", "バックパック"),
            Map.entry("Pickup", "アイテム拾い"),
            Map.entry("Control", "コントロール"),
            Map.entry("Behavior", "行動モード")
    );

    /** Brief descriptions for each skill type. */
    private static final Map<String, String> SKILL_DESCRIPTIONS = Map.ofEntries(
            Map.entry("Damage", "近接攻撃ダメージ増加"),
            Map.entry("Life", "最大HPを増加"),
            Map.entry("Heal", "HPを定期回復"),
            Map.entry("Thorns", "被ダメージ時に反射"),
            Map.entry("Ranged", "弓矢等の遠距離攻撃"),
            Map.entry("Fire", "攻撃時に炎上付与"),
            Map.entry("Poison", "攻撃時に毒付与"),
            Map.entry("Slow", "攻撃時に移動速度低下"),
            Map.entry("Sprint", "移動速度が一時上昇"),
            Map.entry("Knockback", "攻撃時に吹き飛ばし"),
            Map.entry("Ride", "ペットに騎乗可能"),
            Map.entry("Beacon", "周囲にバフ効果を付与"),
            Map.entry("Backpack", "ペット用インベントリ"),
            Map.entry("Pickup", "周囲のアイテムを自動回収"),
            Map.entry("Control", "騎乗時に操作可能"),
            Map.entry("Behavior", "行動モードを拡張")
    );

    private final MyPetAddonPlugin plugin;
    private final Logger logger;
    private final Gson gson = new Gson();

    private final Set<UUID> activeSessions = ConcurrentHashMap.newKeySet();

    public PetSkillGUI(@NotNull MyPetAddonPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Opens the skilltree GUI organized by unlock level stages.
     */
    public void openSkillGUI(@NotNull Player player, @NotNull MyPet myPet) {
        Skilltree skilltree = myPet.getSkilltree();
        if (skilltree == null) {
            player.sendMessage("§8[§6MyPetAddon§8] §cこのペットにはスキルツリーが設定されていません。");
            return;
        }

        String skilltreeId = skilltree.getName();
        String displayName = skilltree.hasDisplayName()
                ? skilltree.getDisplayName()
                : skilltreeId;

        JsonObject skilltreeJson = loadSkilltreeJson(skilltreeId);
        if (skilltreeJson == null) {
            player.sendMessage("§8[§6MyPetAddon§8] §cスキルツリーデータが見つかりません。");
            return;
        }

        int petLevel = getPetLevel(myPet);

        // Parse all skills and group upgrades by level
        // key: level, value: list of (skillName, upgradeData)
        TreeMap<Integer, List<SkillAtLevel>> levelMap = new TreeMap<>();
        JsonObject skillsObj = skilltreeJson.getAsJsonObject("Skills");
        if (skillsObj != null) {
            for (Map.Entry<String, JsonElement> skillEntry : skillsObj.entrySet()) {
                String skillName = skillEntry.getKey();
                if (!skillEntry.getValue().isJsonObject()) continue;
                JsonObject upgrades = skillEntry.getValue().getAsJsonObject().getAsJsonObject("Upgrades");
                if (upgrades == null) continue;
                for (Map.Entry<String, JsonElement> upgradeEntry : upgrades.entrySet()) {
                    int level;
                    try { level = Integer.parseInt(upgradeEntry.getKey()); }
                    catch (NumberFormatException e) { continue; }
                    levelMap.computeIfAbsent(level, k -> new ArrayList<>())
                            .add(new SkillAtLevel(skillName, upgradeEntry.getValue()));
                }
            }
        }

        // Build inventory
        String title = GUI_TITLE_PREFIX + displayName;
        if (title.length() > 32) title = title.substring(0, 32);
        Inventory gui = Bukkit.createInventory(null, SIZE, title);

        // Fill with dark glass
        ItemStack border = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < SIZE; i++) {
            gui.setItem(i, border);
        }

        // Layout (3 rows = 27 slots):
        // Row 0 (slots 0-8):  stage items, centered
        // Row 1 (slots 9-17): overflow if >9 stages (unlikely)
        // Row 2 (slots 18-26): [close] ... [pet info]
        int stageCount = levelMap.size();
        List<Map.Entry<Integer, List<SkillAtLevel>>> stages = new ArrayList<>(levelMap.entrySet());

        int rowCount = Math.min(9, stageCount);
        int startSlot = (9 - rowCount) / 2; // center in row 0
        for (int i = 0; i < Math.min(stageCount, 9); i++) {
            var entry = stages.get(i);
            boolean unlocked = petLevel >= entry.getKey();
            gui.setItem(startSlot + i, buildStageItem(entry.getKey(), entry.getValue(), unlocked));
        }
        // Overflow to row 1 if >9 stages
        if (stageCount > 9) {
            int overflowCount = stageCount - 9;
            int overflowStart = 9 + (9 - overflowCount) / 2;
            for (int i = 0; i < overflowCount; i++) {
                var entry = stages.get(9 + i);
                boolean unlocked = petLevel >= entry.getKey();
                gui.setItem(overflowStart + i, buildStageItem(entry.getKey(), entry.getValue(), unlocked));
            }
        }

        // Row 2: close button (left) + pet info (center-right)
        gui.setItem(18, createItem(Material.BARRIER, "§c§l閉じる"));
        gui.setItem(22, createInfoItem(myPet, displayName, petLevel, levelMap.size(),
                (int) levelMap.keySet().stream().filter(l -> petLevel >= l).count()));

        activeSessions.add(player.getUniqueId());
        player.openInventory(gui);
    }

    // ─── Event Handlers ─────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!activeSessions.contains(player.getUniqueId())) return;
        String title = event.getView().getTitle();
        if (!title.startsWith(GUI_TITLE_PREFIX)) return;

        event.setCancelled(true);
        if (event.getRawSlot() == 18) player.closeInventory();
    }

    @EventHandler
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            activeSessions.remove(player.getUniqueId());
        }
    }

    // ─── Item Builders ──────────────────────────────────────────

    /**
     * Builds an item for one level stage showing all skills unlocked at that level.
     */
    @NotNull
    private ItemStack buildStageItem(int level, @NotNull List<SkillAtLevel> skills, boolean unlocked) {
        Material material = unlocked ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String statusPrefix = unlocked ? "§a§l" : "§c§l";
        meta.setDisplayName(statusPrefix + "LV " + level + (unlocked ? " §a(解放済み)" : " §c(未解放)"));

        List<String> lore = new ArrayList<>();
        lore.add("");

        for (SkillAtLevel skill : skills) {
            String jpName = SKILL_NAMES_JP.getOrDefault(skill.skillName(), skill.skillName());
            String desc = SKILL_DESCRIPTIONS.getOrDefault(skill.skillName(), "");

            // Skill header line with description
            String mark = unlocked ? "§a✔" : "§c✗";
            String nameColor = unlocked ? "§e" : "§7";
            String descColor = unlocked ? "§7" : "§8";
            lore.add(mark + " " + nameColor + jpName + " " + descColor + desc);

            // Parameter lines (indented, one per param)
            formatUpgradeLines(skill.data(), unlocked, lore);
            lore.add("");
        }

        if (!unlocked) {
            lore.add("§7LV " + level + " で解放");
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Adds formatted parameter lines for an upgrade to the lore list.
     * Each parameter gets its own line for readability.
     */
    private void formatUpgradeLines(@NotNull JsonElement data, boolean unlocked,
                                     @NotNull List<String> lore) {
        if (!data.isJsonObject()) return;

        JsonObject obj = data.getAsJsonObject();
        String valColor = unlocked ? "§f" : "§8";
        String keyColor = unlocked ? "§b" : "§8";

        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            String translatedKey = translateUpgradeKey(key);

            if (value.isJsonPrimitive()) {
                if (value.getAsJsonPrimitive().isBoolean()) {
                    String boolStr = value.getAsBoolean() ? "§a有効" : "§c無効";
                    if (!unlocked) boolStr = "§8" + (value.getAsBoolean() ? "有効" : "無効");
                    lore.add("   " + keyColor + translatedKey + ": " + boolStr);
                } else {
                    String displayVal = formatStatValue(key, value.getAsString());
                    lore.add("   " + keyColor + translatedKey + ": " + valColor + displayVal);
                }
            } else if (value.isJsonObject()) {
                // Nested (e.g., Beacon Buffs)
                lore.add("   " + keyColor + translatedKey + ":");
                JsonObject nested = value.getAsJsonObject();
                for (Map.Entry<String, JsonElement> ne : nested.entrySet()) {
                    String nestedKey = translateUpgradeKey(ne.getKey());
                    String nestedVal = ne.getValue().getAsString();
                    lore.add("    " + keyColor + "- " + nestedKey + ": " + valColor + nestedVal);
                }
            }
        }
    }

    /**
     * Formats Damage and Health values as integers (no decimals).
     */
    @NotNull
    private String formatStatValue(@NotNull String key, @NotNull String rawValue) {
        if ("Damage".equals(key) || "Health".equals(key)) {
            try {
                String numPart = rawValue.startsWith("+") ? rawValue.substring(1) : rawValue;
                double val = Double.parseDouble(numPart);
                int intVal = (int) Math.round(val);
                return rawValue.startsWith("+") ? "+" + intVal : String.valueOf(intVal);
            } catch (NumberFormatException ignored) {}
        }
        return rawValue;
    }

    @NotNull
    private String translateUpgradeKey(@NotNull String key) {
        return switch (key) {
            case "Damage" -> "ダメージ";
            case "Health" -> "HP";
            case "Timer" -> "間隔";
            case "Chance" -> "確率";
            case "Reflection" -> "反射";
            case "Range" -> "範囲";
            case "Duration" -> "持続";
            case "Count" -> "回数";
            case "Buffs" -> "バフ";
            case "Active" -> "有効";
            case "rows" -> "行数";
            case "drop" -> "ドロップ";
            case "Exp" -> "経験値";
            case "Friend" -> "友好";
            case "Farm" -> "農場";
            case "Duel" -> "戦闘";
            case "Aggro" -> "攻撃的";
            case "Raid" -> "レイド";
            case "Regeneration" -> "再生";
            case "Strength" -> "筋力";
            case "Resistance" -> "耐性";
            case "Speed" -> "速度";
            case "Rate" -> "発射速度";
            case "Projectile" -> "弾種";
            case "JumpHeight" -> "ジャンプ";
            case "CanFly" -> "飛行";
            case "WaterBreathing" -> "水中呼吸";
            case "Luck" -> "幸運";
            default -> key;
        };
    }

    @NotNull
    private ItemStack createInfoItem(@NotNull MyPet myPet, @NotNull String skilltreeName,
                                      int petLevel, int totalStages, int unlockedStages) {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b§l" + myPet.getPetName());
            List<String> lore = List.of(
                    "",
                    "§7スキルツリー: §e" + skilltreeName,
                    "§7ペットレベル: §e" + petLevel,
                    "§7解放状況: §e" + unlockedStages + "§7/§e" + totalStages + " §7段階",
                    "",
                    "§8レベルを上げてスキルを解放しよう！"
            );
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
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

    private int getPetLevel(@NotNull MyPet myPet) {
        try { return myPet.getExperience().getLevel(); }
        catch (Exception e) { return 0; }
    }

    // ─── Skilltree JSON Loading ─────────────────────────────────

    @Nullable
    private JsonObject loadSkilltreeJson(@NotNull String skilltreeId) {
        String filename = skilltreeId + ".st.json";

        File mypetDir = new File(plugin.getServer().getPluginsFolder(),
                "MyPet" + File.separator + "skilltrees");
        File addonDir = new File(plugin.getDataFolder(), "skilltrees");

        for (File dir : new File[]{mypetDir, addonDir}) {
            File file = new File(dir, filename);
            if (file.exists() && file.isFile()) {
                return parseJsonFile(file);
            }
        }

        logger.warning("[PetSkillGUI] Could not find skilltree file: " + filename);
        return null;
    }

    @Nullable
    private JsonObject parseJsonFile(@NotNull File file) {
        try (Reader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, JsonObject.class);
        } catch (IOException e) {
            logger.log(Level.WARNING,
                    "[PetSkillGUI] Failed to read skilltree file: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    private record SkillAtLevel(@NotNull String skillName, @NotNull JsonElement data) {}
}
