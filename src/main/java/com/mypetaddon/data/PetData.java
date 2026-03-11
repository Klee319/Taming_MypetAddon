package com.mypetaddon.data;

import com.mypetaddon.personality.Personality;
import com.mypetaddon.rarity.Rarity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Immutable record representing a pet's core data stored in the pet_data table.
 */
public record PetData(
        @NotNull UUID addonPetId,
        @NotNull UUID mypetUuid,
        @NotNull UUID ownerUuid,
        @NotNull String mobType,
        @NotNull Rarity rarity,
        @NotNull Personality personality,
        int bondLevel,
        int bondExp,
        int originalLmLevel,
        long createdAt,
        @Nullable UUID evolvedFrom
) {

    /**
     * Returns a copy with updated bond values.
     */
    @NotNull
    public PetData withBond(int newBondLevel, int newBondExp) {
        return new PetData(addonPetId, mypetUuid, ownerUuid, mobType, rarity,
                personality, newBondLevel, newBondExp, originalLmLevel, createdAt, evolvedFrom);
    }

    /**
     * Returns a copy with a new MyPet UUID (e.g. after evolution).
     */
    @NotNull
    public PetData withMypetUuid(@NotNull UUID newMypetUuid) {
        return new PetData(addonPetId, newMypetUuid, ownerUuid, mobType, rarity,
                personality, bondLevel, bondExp, originalLmLevel, createdAt, evolvedFrom);
    }

    /**
     * Returns a copy with a new mob type (e.g. after evolution).
     */
    @NotNull
    public PetData withMobType(@NotNull String newMobType) {
        return new PetData(addonPetId, mypetUuid, ownerUuid, newMobType, rarity,
                personality, bondLevel, bondExp, originalLmLevel, createdAt, evolvedFrom);
    }
}
