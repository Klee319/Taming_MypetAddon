package com.mypetaddon.skill;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cooldown tracking for pet skills.
 * Resets on server restart — cooldowns are not persisted.
 * Thread-safe via ConcurrentHashMap.
 */
public final class CooldownTracker {

    /** addonPetId -> (skillId -> lastUseMillis) */
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    /**
     * Checks whether the given skill is still on cooldown for a pet.
     *
     * @param addonPetId     the addon-internal pet UUID
     * @param skillId        the skill identifier
     * @param cooldownSeconds the cooldown duration in seconds
     * @return true if the skill is still on cooldown
     */
    public boolean isOnCooldown(@NotNull UUID addonPetId, @NotNull String skillId,
                                int cooldownSeconds) {
        return getRemainingCooldown(addonPetId, skillId, cooldownSeconds) > 0;
    }

    /**
     * Returns the remaining cooldown time in seconds for a skill.
     *
     * @param addonPetId     the addon-internal pet UUID
     * @param skillId        the skill identifier
     * @param cooldownSeconds the cooldown duration in seconds
     * @return seconds remaining, or 0 if not on cooldown
     */
    public int getRemainingCooldown(@NotNull UUID addonPetId, @NotNull String skillId,
                                    int cooldownSeconds) {
        Map<String, Long> petCooldowns = cooldowns.get(addonPetId);
        if (petCooldowns == null) {
            return 0;
        }

        Long lastUse = petCooldowns.get(skillId);
        if (lastUse == null) {
            return 0;
        }

        long elapsedMillis = System.currentTimeMillis() - lastUse;
        long cooldownMillis = cooldownSeconds * 1000L;
        long remainingMillis = cooldownMillis - elapsedMillis;

        return remainingMillis > 0 ? (int) Math.ceil(remainingMillis / 1000.0) : 0;
    }

    /**
     * Sets the cooldown for a skill to the current time.
     *
     * @param addonPetId the addon-internal pet UUID
     * @param skillId    the skill identifier
     */
    public void setCooldown(@NotNull UUID addonPetId, @NotNull String skillId) {
        cooldowns.computeIfAbsent(addonPetId, k -> new ConcurrentHashMap<>())
                .put(skillId, System.currentTimeMillis());
    }

    /**
     * Clears all cooldowns for a pet (e.g. on pet removal or evolution).
     *
     * @param addonPetId the addon-internal pet UUID
     */
    public void clearAll(@NotNull UUID addonPetId) {
        cooldowns.remove(addonPetId);
    }
}
