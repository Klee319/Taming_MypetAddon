package com.mypetaddon.rarity;

import org.bukkit.Particle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Represents the rarity tier of a pet, determining stat multipliers,
 * skill slot counts, visual effects, and awakening eligibility.
 */
public enum Rarity {

    COMMON("Common", "§f", 1.0, 1, null, 0, false),
    UNCOMMON("Uncommon", "§a", 1.15, 2, null, 0, false),
    RARE("Rare", "§9", 1.35, 3, Particle.HAPPY_VILLAGER, 40, false),
    EPIC("Epic", "§5", 1.6, 4, Particle.WITCH, 30, true),
    LEGENDARY("Legendary", "§6", 2.0, 5, Particle.TOTEM_OF_UNDYING, 20, true),
    MYTHIC("Mythic", "§d", 2.5, 6, Particle.DRAGON_BREATH, 10, true);

    private final String displayName;
    private final String color;
    private final double statMultiplier;
    private final int skillSlots;
    @Nullable
    private final Particle particleType;
    private final int particleInterval;
    private final boolean awakeningEligible;

    Rarity(@NotNull String displayName,
           @NotNull String color,
           double statMultiplier,
           int skillSlots,
           @Nullable Particle particleType,
           int particleInterval,
           boolean awakeningEligible) {
        this.displayName = displayName;
        this.color = color;
        this.statMultiplier = statMultiplier;
        this.skillSlots = skillSlots;
        this.particleType = particleType;
        this.particleInterval = particleInterval;
        this.awakeningEligible = awakeningEligible;
    }

    @NotNull
    public String getDisplayName() {
        return displayName;
    }

    @NotNull
    public String getColor() {
        return color;
    }

    public double getStatMultiplier() {
        return statMultiplier;
    }

    public int getSkillSlots() {
        return skillSlots;
    }

    @Nullable
    public Particle getParticleType() {
        return particleType;
    }

    public int getParticleInterval() {
        return particleInterval;
    }

    public boolean isAwakeningEligible() {
        return awakeningEligible;
    }

    /**
     * Returns the colored display name (color code + name).
     */
    @NotNull
    public String getColoredName() {
        return color + displayName;
    }

    /**
     * Returns the next rarity tier. MYTHIC upgrades to itself.
     */
    @NotNull
    public Rarity upgrade() {
        Rarity[] values = values();
        int nextOrdinal = ordinal() + 1;
        return nextOrdinal < values.length ? values[nextOrdinal] : this;
    }

    /**
     * Parses a rarity from its name (case-insensitive).
     *
     * @param name the rarity name
     * @return the matching Rarity
     * @throws IllegalArgumentException if no match is found
     */
    @NotNull
    public static Rarity fromString(@NotNull String name) {
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown rarity: '" + name + "'. Valid values: " + java.util.Arrays.toString(values()), e);
        }
    }
}
