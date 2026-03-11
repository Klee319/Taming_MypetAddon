package com.mypetaddon.skilltree;

import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.rarity.Rarity;
import de.Keyle.MyPet.MyPetApi;
import de.Keyle.MyPet.api.entity.MyPet;
import de.Keyle.MyPet.api.event.MyPetSelectSkilltreeEvent;
import de.Keyle.MyPet.api.skill.skilltree.Skilltree;
import de.Keyle.MyPet.api.skill.skilltree.SkilltreeManager;
import de.Keyle.MyPet.entity.InactiveMyPet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Assigns skill trees to pets on taming and handles skill tree rerolling.
 * <p>
 * Assignment logic:
 * <ol>
 *   <li>Check config for rarity-specific overrides (e.g. MYTHIC → specific trees)</li>
 *   <li>Check config for mob-type-specific overrides</li>
 *   <li>Fall back to MyPet's built-in {@code getRandomSkilltree()} which respects
 *       the skill tree's own {@code mobTypes} and {@code weight} settings</li>
 * </ol>
 * <p>
 * MyPet's skill tree system already supports per-mob-type restrictions and weighted
 * random selection. This class adds rarity-based filtering on top.
 */
public final class SkilltreeAssigner {

    private final ConfigManager configManager;
    private final Logger logger;

    public SkilltreeAssigner(@NotNull ConfigManager configManager, @NotNull Logger logger) {
        this.configManager = configManager;
        this.logger = logger;
    }

    /**
     * Assigns a skill tree to a newly created InactiveMyPet based on config rules.
     *
     * @param inactiveMyPet the pet to assign a skill tree to
     * @param rarity        the pet's rarity (for rarity-based overrides)
     * @param mobType       the pet's mob type name (e.g. "ZOMBIE")
     */
    public void assignOnTame(@NotNull InactiveMyPet inactiveMyPet,
                             @NotNull Rarity rarity,
                             @NotNull String mobType) {
        Skilltree selected = selectSkilltree(rarity, mobType);
        if (selected != null) {
            inactiveMyPet.setSkilltree(selected);
            logger.info("[Skilltree] Assigned '" + selected.getName()
                    + "' to new pet (" + mobType + ", " + rarity.name() + ")");
        }
    }

    /**
     * Rerolls the skill tree for an active MyPet.
     *
     * @param myPet   the active pet
     * @param rarity  the pet's rarity
     * @param mobType the pet's mob type name
     * @return the newly assigned Skilltree, or null if no change occurred
     */
    @Nullable
    public Skilltree reroll(@NotNull MyPet myPet,
                            @NotNull Rarity rarity,
                            @NotNull String mobType) {
        Skilltree current = myPet.getSkilltree();
        Skilltree selected = selectSkilltree(rarity, mobType);

        // Try to avoid re-selecting the same tree (attempt twice)
        if (selected != null && current != null && selected.getName().equals(current.getName())) {
            selected = selectSkilltree(rarity, mobType);
        }

        if (selected == null) {
            return null;
        }

        myPet.setSkilltree(selected, MyPetSelectSkilltreeEvent.Source.Other);
        return selected;
    }

    /**
     * Selects a skill tree based on config overrides, falling back to MyPet's built-in random.
     */
    @Nullable
    private Skilltree selectSkilltree(@NotNull Rarity rarity, @NotNull String mobType) {
        SkilltreeManager stManager = MyPetApi.getSkilltreeManager();

        // 1. Check rarity-specific override in config
        List<WeightedTree> rarityTrees = getConfigTrees(
                "skilltree-assignment.per-rarity." + rarity.name());
        if (!rarityTrees.isEmpty()) {
            return pickWeighted(rarityTrees, stManager);
        }

        // 2. Check mob-type-specific override in config
        List<WeightedTree> mobTrees = getConfigTrees(
                "skilltree-assignment.per-mob." + mobType);
        if (!mobTrees.isEmpty()) {
            return pickWeighted(mobTrees, stManager);
        }

        // 3. Check default override in config
        List<WeightedTree> defaultTrees = getConfigTrees(
                "skilltree-assignment.default");
        if (!defaultTrees.isEmpty()) {
            return pickWeighted(defaultTrees, stManager);
        }

        // 4. Fall back to MyPet's built-in weighted random (uses Skilltree.weight + mobTypes)
        // We need an active MyPet for this, but during taming we only have InactiveMyPet.
        // Instead, pick from all available skilltrees weighted by their own weight.
        return pickFromAllSkilltrees(stManager);
    }

    /**
     * Parses weighted skill tree entries from config.
     * Expected format:
     * <pre>
     * path:
     *   - skilltree: "Combat"
     *     weight: 30
     *   - skilltree: "Farm"
     *     weight: 20
     * </pre>
     */
    @NotNull
    private List<WeightedTree> getConfigTrees(@NotNull String configPath) {
        List<?> rawList = configManager.getConfig().getList(configPath);
        if (rawList == null) {
            return Collections.emptyList();
        }

        List<WeightedTree> result = new ArrayList<>();
        for (Object obj : rawList) {
            if (obj instanceof Map<?, ?> map) {
                Object nameObj = map.get("skilltree");
                Object weightObj = map.get("weight");
                if (nameObj != null) {
                    String name = String.valueOf(nameObj);
                    int weight = weightObj instanceof Number n ? n.intValue() : 10;
                    result.add(new WeightedTree(name, Math.max(1, weight)));
                }
            }
        }
        return result;
    }

    /**
     * Picks a skilltree from weighted entries by name, resolving against SkilltreeManager.
     */
    @Nullable
    private Skilltree pickWeighted(@NotNull List<WeightedTree> entries,
                                   @NotNull SkilltreeManager stManager) {
        // Filter to only valid (registered) skilltrees
        List<WeightedTree> valid = new ArrayList<>();
        for (WeightedTree entry : entries) {
            if (stManager.hasSkilltree(entry.name())) {
                valid.add(entry);
            } else {
                logger.warning("[Skilltree] Config references unknown skilltree: " + entry.name());
            }
        }

        if (valid.isEmpty()) {
            return null;
        }

        int totalWeight = valid.stream().mapToInt(WeightedTree::weight).sum();
        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;

        for (WeightedTree entry : valid) {
            cumulative += entry.weight();
            if (roll < cumulative) {
                return stManager.getSkilltree(entry.name());
            }
        }

        return stManager.getSkilltree(valid.getLast().name());
    }

    /**
     * Picks from all registered skilltrees using their own weight values.
     */
    @Nullable
    private Skilltree pickFromAllSkilltrees(@NotNull SkilltreeManager stManager) {
        List<Skilltree> all = new ArrayList<>(stManager.getSkilltrees());
        if (all.isEmpty()) {
            return null;
        }

        double totalWeight = all.stream().mapToDouble(Skilltree::getWeight).sum();
        if (totalWeight <= 0) {
            // All weights are 0 — pick uniformly
            return all.get(ThreadLocalRandom.current().nextInt(all.size()));
        }

        double roll = ThreadLocalRandom.current().nextDouble() * totalWeight;
        double cumulative = 0;

        for (Skilltree tree : all) {
            cumulative += tree.getWeight();
            if (roll < cumulative) {
                return tree;
            }
        }

        return all.getLast();
    }

    private record WeightedTree(@NotNull String name, int weight) {}
}
