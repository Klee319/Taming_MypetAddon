package com.mypetaddon.stats;

import com.mypetaddon.config.ConfigManager;
import de.Keyle.MyPet.api.event.MyPetExpEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

/**
 * Intercepts MyPet experience gain events and applies a configurable multiplier.
 * Also protects against unintended experience loss (e.g. during pet deactivation).
 */
public final class ExpModifierListener implements Listener {

    private final ConfigManager configManager;

    /** Pet UUIDs currently having their exp restored — skip multiplier for these. */
    private final java.util.Set<java.util.UUID> restoringPets =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public ExpModifierListener(@NotNull ConfigManager configManager) {
        this.configManager = configManager;
    }

    /** Mark a pet as currently restoring exp (bypass multiplier). */
    public void markRestoring(@NotNull java.util.UUID petUuid) {
        restoringPets.add(petUuid);
    }

    /** Unmark a pet from restoring state. */
    public void unmarkRestoring(@NotNull java.util.UUID petUuid) {
        restoringPets.remove(petUuid);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMyPetExp(@NotNull MyPetExpEvent event) {
        double original = event.getExp();

        // Protect against negative exp events (death penalties, bugs)
        // Cancel the event entirely so MyPet never applies the negative change.
        if (original < 0) {
            boolean preventLoss = configManager.getConfig().getBoolean(
                    "level-bonuses.prevent-exp-loss", true);
            if (preventLoss) {
                event.setCancelled(true);
            }
            return;
        }

        if (original == 0) {
            return;
        }

        // Skip multiplier for pets that are currently having exp restored
        if (restoringPets.remove(event.getPet().getUUID())) {
            return;
        }

        double multiplier = configManager.getDouble("level-bonuses.exp-multiplier", 1.0);
        // Clamp to non-negative and skip if effectively 1.0
        multiplier = Math.max(0.0, multiplier);
        if (Math.abs(multiplier - 1.0) < 1e-9) {
            return;
        }

        double modified = original * multiplier;

        // Ensure at least 1 EXP is granted if original was positive
        if (modified < 1.0) {
            modified = 1.0;
        }

        event.setExp(modified);
    }
}
