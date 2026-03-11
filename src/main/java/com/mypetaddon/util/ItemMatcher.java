package com.mypetaddon.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility for matching ItemStacks against config-defined item descriptors.
 * Supports two formats:
 * <ul>
 *   <li>Material name: e.g. "GOLDEN_APPLE" — matches by Material type only</li>
 *   <li>BASE64-encoded ItemStack: e.g. "base64:rO0ABX..." — matches by full ItemStack (type, meta, NBT)</li>
 * </ul>
 */
public final class ItemMatcher {

    private static final String BASE64_PREFIX = "base64:";

    private ItemMatcher() {}

    /**
     * Tests whether the given ItemStack matches the item descriptor from config.
     *
     * @param handItem   the item the player is holding
     * @param descriptor config value — Material name or "base64:..." encoded ItemStack
     * @param logger     logger for decode errors
     * @return true if the handItem matches
     */
    public static boolean matches(@NotNull ItemStack handItem,
                                  @NotNull String descriptor,
                                  @Nullable Logger logger) {
        if (descriptor.startsWith(BASE64_PREFIX)) {
            ItemStack decoded = decodeBase64Item(descriptor.substring(BASE64_PREFIX.length()), logger);
            if (decoded == null) {
                return false;
            }
            return handItem.isSimilar(decoded);
        }

        // Simple Material name match
        Material material = Material.matchMaterial(descriptor);
        return material != null && handItem.getType() == material;
    }

    /**
     * Decodes a BASE64-encoded ItemStack string.
     *
     * @param base64 the BASE64 string (without the "base64:" prefix)
     * @param logger logger for decode errors
     * @return the decoded ItemStack, or null on failure
     */
    @Nullable
    public static ItemStack decodeBase64Item(@NotNull String base64, @Nullable Logger logger) {
        try {
            byte[] data = Base64.getDecoder().decode(base64);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                 BukkitObjectInputStream ois = new BukkitObjectInputStream(bais)) {
                return (ItemStack) ois.readObject();
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.log(Level.WARNING, "[ItemMatcher] Failed to decode BASE64 item: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Encodes an ItemStack to BASE64 string (for use in config generation).
     *
     * @param item the ItemStack to encode
     * @return BASE64 string, or null on failure
     */
    @Nullable
    public static String encodeBase64Item(@NotNull ItemStack item) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            try (org.bukkit.util.io.BukkitObjectOutputStream oos =
                         new org.bukkit.util.io.BukkitObjectOutputStream(baos)) {
                oos.writeObject(item);
            }
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }
}
