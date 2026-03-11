package com.mypetaddon.data;

import com.mypetaddon.personality.Personality;
import com.mypetaddon.rarity.Rarity;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CRUD operations for pet_data and pet_stats tables.
 * All methods execute on the calling thread; callers must ensure async execution.
 */
public final class PetDataRepository {

    private static final Logger LOGGER = Logger.getLogger(PetDataRepository.class.getName());

    private final DatabaseManager databaseManager;

    public PetDataRepository(@NotNull DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    // --- Save ---

    /**
     * Inserts or replaces pet data and all associated stats.
     */
    public void save(@NotNull PetData data, @NotNull PetStats stats) {
        String sql = "INSERT OR REPLACE INTO pet_data "
                + "(addon_pet_id, mypet_uuid, owner_uuid, mob_type, rarity, "
                + "personality, bond_level, bond_exp, original_lm_level, created_at, evolved_from) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, data.addonPetId().toString());
            ps.setString(2, data.mypetUuid().toString());
            ps.setString(3, data.ownerUuid().toString());
            ps.setString(4, data.mobType());
            ps.setString(5, data.rarity().name());
            ps.setString(6, data.personality().name());
            ps.setInt(7, data.bondLevel());
            ps.setInt(8, data.bondExp());
            ps.setInt(9, data.originalLmLevel());
            ps.setLong(10, data.createdAt());
            ps.setString(11, data.evolvedFrom() != null ? data.evolvedFrom().toString() : null);
            ps.executeUpdate();

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to save pet data: " + data.addonPetId(), e);
        }

        saveStats(stats);
    }

    // --- Find ---

    /**
     * Finds pet data by addon pet ID.
     */
    @NotNull
    public Optional<PetData> findByAddonPetId(@NotNull UUID addonPetId) {
        String sql = "SELECT * FROM pet_data WHERE addon_pet_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, addonPetId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapPetData(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to find pet by addon_pet_id: " + addonPetId, e);
        }
        return Optional.empty();
    }

    /**
     * Finds pet data by MyPet UUID.
     */
    @NotNull
    public Optional<PetData> findByMypetUuid(@NotNull UUID mypetUuid) {
        String sql = "SELECT * FROM pet_data WHERE mypet_uuid = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, mypetUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapPetData(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to find pet by mypet_uuid: " + mypetUuid, e);
        }
        return Optional.empty();
    }

    /**
     * Finds all pets owned by a player.
     */
    @NotNull
    public List<PetData> findByOwner(@NotNull UUID ownerUuid) {
        String sql = "SELECT * FROM pet_data WHERE owner_uuid = ?";
        List<PetData> results = new ArrayList<>();

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapPetData(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to find pets by owner: " + ownerUuid, e);
        }
        return List.copyOf(results);
    }

    /**
     * Finds all stats for a pet by addon pet ID.
     */
    @NotNull
    public Optional<PetStats> findStatsByAddonPetId(@NotNull UUID addonPetId) {
        String sql = "SELECT stat_name, base_value, upgraded_value FROM pet_stats WHERE addon_pet_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, addonPetId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, Double> baseValues = new LinkedHashMap<>();
                Map<String, Double> upgradedValues = new LinkedHashMap<>();
                boolean hasRows = false;

                while (rs.next()) {
                    hasRows = true;
                    String statName = rs.getString("stat_name");
                    baseValues.put(statName, rs.getDouble("base_value"));
                    upgradedValues.put(statName, rs.getDouble("upgraded_value"));
                }

                if (hasRows) {
                    return Optional.of(new PetStats(addonPetId, baseValues, upgradedValues));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to find stats for: " + addonPetId, e);
        }
        return Optional.empty();
    }

    // --- Update ---

    /**
     * Updates bond level and experience for a pet.
     */
    public void updateBond(@NotNull UUID addonPetId, int bondLevel, int bondExp) {
        String sql = "UPDATE pet_data SET bond_level = ?, bond_exp = ? WHERE addon_pet_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, bondLevel);
            ps.setInt(2, bondExp);
            ps.setString(3, addonPetId.toString());
            ps.executeUpdate();

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to update bond for: " + addonPetId, e);
        }
    }

    /**
     * Updates the MyPet UUID mapping (e.g. after evolution creates a new MyPet entity).
     */
    public void updateMypetUuid(@NotNull UUID addonPetId, @NotNull UUID newMypetUuid) {
        String sql = "UPDATE pet_data SET mypet_uuid = ? WHERE addon_pet_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, newMypetUuid.toString());
            ps.setString(2, addonPetId.toString());
            ps.executeUpdate();

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to update mypet_uuid for: " + addonPetId, e);
        }
    }

    /**
     * Updates the mob type (e.g. after evolution).
     */
    public void updateMobType(@NotNull UUID addonPetId, @NotNull String newMobType) {
        String sql = "UPDATE pet_data SET mob_type = ? WHERE addon_pet_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, newMobType);
            ps.setString(2, addonPetId.toString());
            ps.executeUpdate();

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to update mob_type for: " + addonPetId, e);
        }
    }

    // --- Delete ---

    /**
     * Deletes a pet and all associated data (CASCADE handles stats, equipment, skills).
     */
    public void delete(@NotNull UUID addonPetId) {
        String sql = "DELETE FROM pet_data WHERE addon_pet_id = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, addonPetId.toString());
            ps.executeUpdate();

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to delete pet: " + addonPetId, e);
        }
    }

    // --- Stats ---

    /**
     * Saves all stats for a pet (INSERT OR REPLACE for each stat entry).
     */
    public void saveStats(@NotNull PetStats stats) {
        String sql = "INSERT OR REPLACE INTO pet_stats "
                + "(addon_pet_id, stat_name, base_value, upgraded_value) "
                + "VALUES (?, ?, ?, ?)";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            for (String statName : stats.baseValues().keySet()) {
                ps.setString(1, stats.addonPetId().toString());
                ps.setString(2, statName);
                ps.setDouble(3, stats.baseValues().getOrDefault(statName, 0.0));
                ps.setDouble(4, stats.upgradedValues().getOrDefault(statName, 0.0));
                ps.addBatch();
            }
            ps.executeBatch();

        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to save stats for: " + stats.addonPetId(), e);
        }
    }

    // --- Row Mapper ---

    @NotNull
    private PetData mapPetData(@NotNull ResultSet rs) throws SQLException {
        String evolvedFromStr = rs.getString("evolved_from");
        UUID evolvedFrom = evolvedFromStr != null ? UUID.fromString(evolvedFromStr) : null;

        return new PetData(
                UUID.fromString(rs.getString("addon_pet_id")),
                UUID.fromString(rs.getString("mypet_uuid")),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("mob_type"),
                Rarity.fromString(rs.getString("rarity")),
                Personality.valueOf(rs.getString("personality")),
                rs.getInt("bond_level"),
                rs.getInt("bond_exp"),
                rs.getInt("original_lm_level"),
                rs.getLong("created_at"),
                evolvedFrom
        );
    }
}
