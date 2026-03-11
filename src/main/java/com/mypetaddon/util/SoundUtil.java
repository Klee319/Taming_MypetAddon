package com.mypetaddon.util;

import com.mypetaddon.config.ConfigManager;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Static utility class for playing sounds based on config definitions.
 * Reads sound name, volume, and pitch from the config under "sounds.&lt;configKey&gt;".
 */
public final class SoundUtil {

    private static final Logger LOGGER = Logger.getLogger(SoundUtil.class.getName());

    private SoundUtil() {
        // Utility class — no instantiation
    }

    /**
     * Plays a sound at the player's location using config-defined values.
     *
     * @param player    the player to play the sound for
     * @param configKey the config key under "sounds" (e.g. "tame-success")
     * @param config    the config manager
     */
    public static void playSound(@NotNull Player player, @NotNull String configKey,
                                 @NotNull ConfigManager config) {
        playSound(player.getLocation(), configKey, config);
    }

    /**
     * Plays a sound at a specific location using config-defined values.
     * The config is expected to have entries under "sounds.&lt;configKey&gt;":
     * <pre>
     *   sounds:
     *     tame-success:
     *       sound: ENTITY_PLAYER_LEVELUP
     *       volume: 1.0
     *       pitch: 1.0
     * </pre>
     *
     * @param location  the location to play the sound at
     * @param configKey the config key under "sounds" (e.g. "tame-success")
     * @param config    the config manager
     */
    public static void playSound(@NotNull Location location, @NotNull String configKey,
                                 @NotNull ConfigManager config) {
        String basePath = "sounds." + configKey;
        String soundName = config.getString(basePath + ".sound", "");

        if (soundName.isEmpty()) {
            LOGGER.fine("[Sound] No sound configured for key: " + configKey);
            return;
        }

        Sound sound = parseSound(soundName);
        if (sound == null) {
            LOGGER.warning("[Sound] Unknown sound name '" + soundName + "' for key: " + configKey);
            return;
        }

        float volume = (float) config.getDouble(basePath + ".volume", 1.0);
        float pitch = (float) config.getDouble(basePath + ".pitch", 1.0);

        if (location.getWorld() == null) {
            LOGGER.fine("[Sound] Cannot play sound — location has no world.");
            return;
        }

        location.getWorld().playSound(location, sound, volume, pitch);
    }

    /**
     * Safely parses a Sound enum value from a string (case-insensitive).
     *
     * @param name the sound name
     * @return the Sound, or null if not found
     */
    @Nullable
    private static Sound parseSound(@NotNull String name) {
        try {
            return Sound.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
