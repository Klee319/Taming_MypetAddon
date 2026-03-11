package com.mypetaddon.stats;

import com.mypetaddon.bond.BondLevel;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.PetStats;
import org.jetbrains.annotations.NotNull;

/**
 * Calculates final stat values by applying the modifier pipeline in order:
 * <ol>
 *   <li>Base value (base + upgraded)</li>
 *   <li>Rarity multiplier</li>
 *   <li>Personality multiplier</li>
 *   <li>Bond level bonus (additive)</li>
 * </ol>
 *
 * Formula: result = (base * rarityMul * personalityMul) + bondBonus
 */
public final class ModifierPipeline {

    private final ConfigManager configManager;

    public ModifierPipeline(@NotNull ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * Calculates the final value for a single stat after applying all modifiers.
     *
     * @param statName the stat identifier (e.g. "damage", "health", "speed")
     * @param petData  the pet's core data (rarity, personality, bond level)
     * @param petStats the pet's base and upgraded stat values
     * @return the final calculated stat value
     */
    public double calculate(@NotNull String statName,
                            @NotNull PetData petData,
                            @NotNull PetStats petStats) {
        // Step 1: Base value = base + upgraded
        double base = petStats.baseValues().getOrDefault(statName, 0.0)
                + petStats.upgradedValues().getOrDefault(statName, 0.0);

        // Step 2: Rarity multiplier
        double rarityMul = petData.rarity().getStatMultiplier();

        // Step 3: Personality multiplier (defaults to 1.0 if no modifier defined)
        double personalityMul = petData.personality().getModifier(statName, 1.0);

        // Step 4: Bond level bonus (additive flat bonus)
        double bondBonus = BondLevel.getStatBonus(petData.bondLevel(), statName);

        // Final formula
        return (base * rarityMul * personalityMul) + bondBonus;
    }
}
