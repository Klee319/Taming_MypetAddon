package com.mypetaddon.skill;

import com.mypetaddon.data.PetData;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.personality.Personality;
import de.Keyle.MyPet.api.entity.MyPet;
import de.Keyle.MyPet.api.entity.MyPetBukkitEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Listener for passive skill effects and skill-related events.
 * Handles damage modifications based on pet personality traits.
 */
public final class PetSkillListener implements Listener {

    private static final double CAUTIOUS_DAMAGE_REDUCTION = 0.10;

    private final PetSkillManager petSkillManager;
    private final PetDataCache petDataCache;

    public PetSkillListener(@NotNull PetSkillManager petSkillManager,
                            @NotNull PetDataCache petDataCache) {
        this.petSkillManager = petSkillManager;
        this.petDataCache = petDataCache;
    }

    /**
     * Handles entity damage events involving MyPet entities.
     * <ul>
     *   <li>If the damage source is a MyPet, check for damage-based passive effects.</li>
     *   <li>If the target is a MyPet with CAUTIOUS personality, apply damage reduction.</li>
     * </ul>
     */
    @EventHandler
    public void onEntityDamageByEntity(@NotNull EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Entity victim = event.getEntity();

        // Check if a MyPet is dealing damage (passive damage effects)
        if (damager instanceof MyPetBukkitEntity myPetDamager) {
            handlePetDealingDamage(myPetDamager, event);
        }

        // Check if a MyPet is receiving damage (CAUTIOUS damage reduction)
        if (victim instanceof MyPetBukkitEntity myPetVictim) {
            handlePetReceivingDamage(myPetVictim, event);
        }
    }

    // ─── Internal ────────────────────────────────────────────────

    /**
     * Handles passive effects when a MyPet deals damage.
     * Reserved for future Phase extensibility (e.g. FIERCE crit chance).
     */
    private void handlePetDealingDamage(@NotNull MyPetBukkitEntity myPetEntity,
                                        @NotNull EntityDamageByEntityEvent event) {
        MyPet myPet = myPetEntity.getMyPet();
        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            return;
        }

        // Future: Add passive damage effects based on personality
        // e.g. FIERCE crit chance, BRAVE bonus damage, etc.
    }

    /**
     * Applies damage reduction when a MyPet with CAUTIOUS personality takes damage.
     */
    private void handlePetReceivingDamage(@NotNull MyPetBukkitEntity myPetEntity,
                                          @NotNull EntityDamageByEntityEvent event) {
        MyPet myPet = myPetEntity.getMyPet();
        PetData petData = petDataCache.get(myPet.getUUID());
        if (petData == null) {
            return;
        }

        if (petData.personality() == Personality.CAUTIOUS) {
            double originalDamage = event.getDamage();
            double reducedDamage = originalDamage * (1.0 - CAUTIOUS_DAMAGE_REDUCTION);
            event.setDamage(reducedDamage);
        }
    }
}
