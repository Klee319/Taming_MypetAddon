package com.mypetaddon.equipment;

import com.mypetaddon.MyPetAddonPlugin;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.DatabaseManager;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages pet equipment with 6 slots (HEAD, CHEST, LEGS, FEET, WEAPON, ACCESSORY).
 * Handles serialization/deserialization via BukkitObjectOutputStream -> Base64
 * and persistence via the pet_equipment table.
 *
 * All DB operations run on calling thread; callers are responsible for async scheduling.
 */
public final class EquipmentManager {

    /** Valid equipment slot names. */
    public static final Set<String> VALID_SLOTS = Set.of("HEAD", "CHEST", "ACCESSORY");

    /** Stat PDC key prefix: NamespacedKey("mypetaddon", "stat_<statName>") */
    private static final String PDC_STAT_PREFIX = "stat_";

    private final MyPetAddonPlugin plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private final Logger logger;

    /** In-memory equipment cache: addonPetId -> (slot -> ItemStack). */
    private final Map<UUID, Map<String, ItemStack>> equipmentCache = new ConcurrentHashMap<>();

    public EquipmentManager(@NotNull MyPetAddonPlugin plugin,
                            @NotNull ConfigManager configManager,
                            @NotNull DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.databaseManager = databaseManager;
        this.logger = plugin.getLogger();
    }

    // ─── Serialization ──────────────────────────────────────────

    /**
     * Serializes an ItemStack to a Base64 string using BukkitObjectOutputStream.
     *
     * @param item the ItemStack to serialize
     * @return Base64-encoded string, or null on failure
     */
    @Nullable
    public static String serializeItem(@NotNull ItemStack item) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeObject(item);
            boos.flush();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Deserializes an ItemStack from a Base64 string using BukkitObjectInputStream.
     *
     * @param data Base64-encoded string
     * @return the deserialized ItemStack, or null on failure
     */
    @Nullable
    public static ItemStack deserializeItem(@NotNull String data) {
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                 BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
                return (ItemStack) bois.readObject();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ─── Equip / Unequip ────────────────────────────────────────

    /**
     * Equips an item in the given slot for a pet.
     * Uses INSERT OR REPLACE to upsert the equipment row.
     *
     * @param addonPetId the addon-internal pet UUID
     * @param slot       the slot name (HELMET, CHEST, ACCESSORY)
     * @param item       the ItemStack to equip
     * @return true if the item was successfully equipped
     */
    public boolean equipItem(@NotNull UUID addonPetId, @NotNull String slot, @NotNull ItemStack item) {
        if (!VALID_SLOTS.contains(slot)) {
            logger.warning("[Equipment] Invalid slot name: " + slot);
            return false;
        }

        String serialized = serializeItem(item);
        if (serialized == null) {
            logger.warning("[Equipment] Failed to serialize item for slot " + slot);
            return false;
        }

        String sql = "INSERT OR REPLACE INTO pet_equipment (addon_pet_id, slot, item_data) VALUES (?, ?, ?)";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, addonPetId.toString());
            ps.setString(2, slot);
            ps.setString(3, serialized);
            ps.executeUpdate();
            // Update cache
            equipmentCache.computeIfAbsent(addonPetId, k -> new ConcurrentHashMap<>())
                    .put(slot, item.clone());
            return true;
        } catch (Exception e) {
            logger.log(Level.WARNING, "[Equipment] Failed to equip item in slot " + slot
                    + " for pet " + addonPetId, e);
            return false;
        }
    }

    /**
     * Unequips an item from the given slot.
     * Loads the item from the DB, deletes the row, and returns the ItemStack.
     *
     * @param addonPetId the addon-internal pet UUID
     * @param slot       the slot name
     * @return the previously equipped ItemStack, or null if empty/failure
     */
    @Nullable
    public ItemStack unequipItem(@NotNull UUID addonPetId, @NotNull String slot) {
        if (!VALID_SLOTS.contains(slot)) {
            return null;
        }

        ItemStack item = null;

        // Load current item
        String selectSql = "SELECT item_data FROM pet_equipment WHERE addon_pet_id = ? AND slot = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, addonPetId.toString());
            ps.setString(2, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    item = deserializeItem(rs.getString("item_data"));
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "[Equipment] Failed to load item from slot " + slot
                    + " for pet " + addonPetId, e);
            return null;
        }

        if (item == null) {
            return null;
        }

        // Delete from DB
        String deleteSql = "DELETE FROM pet_equipment WHERE addon_pet_id = ? AND slot = ?";
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(deleteSql)) {
            ps.setString(1, addonPetId.toString());
            ps.setString(2, slot);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.log(Level.WARNING, "[Equipment] Failed to delete slot " + slot
                    + " for pet " + addonPetId, e);
            // Item was already loaded; return it anyway to avoid loss
        }

        // Update cache
        Map<String, ItemStack> cached = equipmentCache.get(addonPetId);
        if (cached != null) {
            cached.remove(slot);
        }

        return item;
    }

    // ─── Query ──────────────────────────────────────────────────

    /**
     * Loads all equipped items for a pet.
     *
     * @param addonPetId the addon-internal pet UUID
     * @return map of slot -> ItemStack (unmodifiable, only includes occupied slots)
     */
    @NotNull
    public Map<String, ItemStack> getEquipment(@NotNull UUID addonPetId) {
        // Check cache first
        Map<String, ItemStack> cached = equipmentCache.get(addonPetId);
        if (cached != null) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(cached));
        }

        // Cache miss — load from DB
        String sql = "SELECT slot, item_data FROM pet_equipment WHERE addon_pet_id = ?";
        Map<String, ItemStack> result = new ConcurrentHashMap<>();

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, addonPetId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String slot = rs.getString("slot");
                    ItemStack item = deserializeItem(rs.getString("item_data"));
                    if (item != null) {
                        result.put(slot, item);
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "[Equipment] Failed to load equipment for pet " + addonPetId, e);
        }

        // Populate cache
        equipmentCache.put(addonPetId, result);
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    /**
     * Invalidates the equipment cache for a pet (e.g. on pet removal).
     */
    public void invalidateCache(@NotNull UUID addonPetId) {
        equipmentCache.remove(addonPetId);
    }

    /**
     * Calculates the total stat bonus from all equipped items for a given stat name.
     * Reads PDC tags with key "mypetaddon:stat_<statName>" (Double type) from each item.
     *
     * @param addonPetId the addon-internal pet UUID
     * @param statName   the stat to sum bonuses for
     * @return the total bonus value (0.0 if no bonuses found)
     */
    public double getEquipmentStatBonus(@NotNull UUID addonPetId, @NotNull String statName) {
        Map<String, ItemStack> equipment = getEquipment(addonPetId);
        if (equipment.isEmpty()) {
            return 0.0;
        }

        NamespacedKey key = new NamespacedKey(plugin, PDC_STAT_PREFIX + statName);
        double totalBonus = 0.0;

        for (Map.Entry<String, ItemStack> entry : equipment.entrySet()) {
            String slot = entry.getKey();
            ItemStack item = entry.getValue();
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                continue;
            }

            // Priority 1: PDC tags (custom items)
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            Double bonus = pdc.get(key, PersistentDataType.DOUBLE);
            if (bonus != null) {
                totalBonus += bonus;
                continue;
            }

            // Priority 2: Default effects from config (vanilla items)
            Map<String, Double> defaults = configManager.getEquipmentDefaultEffects(
                    slot, item.getType().name());
            Double defaultBonus = defaults.get(statName);
            if (defaultBonus != null) {
                totalBonus += defaultBonus;
            }
        }

        return totalBonus;
    }
}
