package com.mypetaddon.stats;

import com.mypetaddon.bond.BondLevel;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.PetData;
import com.mypetaddon.data.PetStats;
import com.mypetaddon.equipment.EquipmentManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Calculates final stat values by applying the modifier pipeline in order:
 * <ol>
 *   <li>Base value (base + upgraded)</li>
 *   <li>Rarity multiplier</li>
 *   <li>Personality multiplier</li>
 *   <li>Bond level bonus (additive)</li>
 *   <li>Equipment bonus (additive)</li>
 * </ol>
 *
 * Formula: result = (base * rarityMul * personalityMul) * (1 + bondBonus) + equipBonus
 */
public final class ModifierPipeline {

    private final ConfigManager configManager;
    private volatile EquipmentManager equipmentManager;

    public ModifierPipeline(@NotNull ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * Sets the equipment manager for equipment bonus calculations.
     * Called after EquipmentManager is initialized (avoids circular dependency).
     */
    public void setEquipmentManager(@Nullable EquipmentManager equipmentManager) {
        this.equipmentManager = equipmentManager;
    }

    /**
     * Calculates the final value for a single stat after applying all modifiers.
     * Overload without pet level (no level bonus applied).
     */
    public double calculate(@NotNull String statName,
                            @NotNull PetData petData,
                            @NotNull PetStats petStats) {
        return calculate(statName, petData, petStats, 0);
    }

    /**
     * Calculates the final value for a single stat after applying all modifiers.
     *
     * @param statName the stat identifier (e.g. "Life", "Damage", "Speed")
     * @param petData  the pet's core data (rarity, personality, bond level)
     * @param petStats the pet's base and upgraded stat values
     * @param petLevel the MyPet experience level (for level-based bonuses)
     * @return the final calculated stat value
     */
    public double calculate(@NotNull String statName,
                            @NotNull PetData petData,
                            @NotNull PetStats petStats,
                            int petLevel) {
        // Step 1: Base value = base + upgraded
        double base = petStats.baseValues().getOrDefault(statName, 0.0)
                + petStats.upgradedValues().getOrDefault(statName, 0.0);

        // Step 2: Rarity multiplier
        double rarityMul = petData.rarity().getStatMultiplier();

        // Step 3: Personality multiplier from config (falls back to enum default)
        double personalityMul = configManager.getPersonalityModifier(petData.personality(), statName);

        // Step 4: Bond level bonus (percentage multiplier, e.g. 0.28 = +28%)
        double bondBonus = BondLevel.getStatBonus(petData.bondLevel(), statName);

        // Step 5: Equipment bonus (additive flat)
        double equipBonus = 0.0;
        if (equipmentManager != null) {
            equipBonus = equipmentManager.getEquipmentStatBonus(petData.addonPetId(), statName);
        }

        // Step 6: Level bonus (after configured start level)
        double levelBonus = calculateLevelBonus(petLevel);

        // Final formula: bond bonus and level bonus are percentage-based on the adjusted value
        double adjusted = base * rarityMul * personalityMul;
        return adjusted * (1.0 + bondBonus) * (1.0 + levelBonus) + equipBonus;
    }

    /**
     * Calculates the percentage bonus based on pet level.
     * Returns 0 if pet level is below the configured start level.
     */
    private double calculateLevelBonus(int petLevel) {
        boolean enabled = configManager.getConfig().getBoolean("level-bonuses.enabled", false);
        if (!enabled) {
            return 0.0;
        }
        int startLevel = configManager.getInt("level-bonuses.start-level", 20);
        if (petLevel <= startLevel) {
            return 0.0;
        }
        double perLevel = configManager.getDouble("level-bonuses.per-level-bonus", 0.005);
        double maxBonus = configManager.getDouble("level-bonuses.max-bonus", 0.25);
        double bonus = (petLevel - startLevel) * perLevel;
        return Math.min(bonus, maxBonus);
    }
}
