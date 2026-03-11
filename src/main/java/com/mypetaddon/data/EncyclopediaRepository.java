package com.mypetaddon.data;

import com.mypetaddon.rarity.Rarity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Repository for the encyclopedia (Pokedex) table.
 * Tracks taming history per player per mob type.
 */
public final class EncyclopediaRepository {

    private static final Logger LOGGER = Logger.getLogger(EncyclopediaRepository.class.getName());

    private final DatabaseManager databaseManager;

    public EncyclopediaRepository(@NotNull DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Records a tame event. Inserts a new entry or updates existing:
     * increments tame_count and upgrades highest_rarity if the new rarity is higher.
     * Uses a transaction with read-then-write for correct rarity ordinal comparison.
     */
    public void recordTame(@NotNull UUID ownerUuid, @NotNull String mobType, @NotNull Rarity rarity) {
        try (Connection conn = databaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                EncyclopediaEntry existing = findEntry(conn, ownerUuid, mobType);

                if (existing == null) {
                    // Insert new entry
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO encyclopedia "
                            + "(owner_uuid, mob_type, highest_rarity, tame_count, first_tamed_at) "
                            + "VALUES (?, ?, ?, 1, strftime('%s','now'))")) {
                        ps.setString(1, ownerUuid.toString());
                        ps.setString(2, mobType);
                        ps.setString(3, rarity.name());
                        ps.executeUpdate();
                    }
                } else {
                    // Update: increment count, upgrade rarity if higher
                    Rarity effectiveRarity = rarity.ordinal() > existing.highestRarity().ordinal()
                            ? rarity : existing.highestRarity();

                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE encyclopedia SET tame_count = tame_count + 1, highest_rarity = ? "
                            + "WHERE owner_uuid = ? AND mob_type = ?")) {
                        ps.setString(1, effectiveRarity.name());
                        ps.setString(2, ownerUuid.toString());
                        ps.setString(3, mobType);
                        ps.executeUpdate();
                    }
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to record tame for " + ownerUuid + " / " + mobType, e);
        }
    }

    /**
     * Returns all encyclopedia entries for a player.
     */
    @NotNull
    public List<EncyclopediaEntry> getEntries(@NotNull UUID ownerUuid) {
        String sql = "SELECT mob_type, highest_rarity, tame_count, first_tamed_at "
                + "FROM encyclopedia WHERE owner_uuid = ? ORDER BY mob_type";
        List<EncyclopediaEntry> entries = new ArrayList<>();

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new EncyclopediaEntry(
                            rs.getString("mob_type"),
                            Rarity.fromString(rs.getString("highest_rarity")),
                            rs.getInt("tame_count"),
                            rs.getLong("first_tamed_at")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to get encyclopedia entries for: " + ownerUuid, e);
        }

        return List.copyOf(entries);
    }

    /**
     * Returns the completion percentage for a player.
     *
     * @param ownerUuid  the player UUID
     * @param totalTypes the total number of tameable mob types
     * @return completion percentage (0.0 to 100.0)
     */
    public double getCompletionPercent(@NotNull UUID ownerUuid, int totalTypes) {
        if (totalTypes <= 0) {
            return 0.0;
        }

        String sql = "SELECT COUNT(*) AS cnt FROM encyclopedia WHERE owner_uuid = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int collected = rs.getInt("cnt");
                    return (collected * 100.0) / totalTypes;
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to get completion for: " + ownerUuid, e);
        }
        return 0.0;
    }

    // --- Internal ---

    @Nullable
    private EncyclopediaEntry findEntry(@NotNull Connection conn,
                                        @NotNull UUID ownerUuid,
                                        @NotNull String mobType) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT mob_type, highest_rarity, tame_count, first_tamed_at "
                + "FROM encyclopedia WHERE owner_uuid = ? AND mob_type = ?")) {
            ps.setString(1, ownerUuid.toString());
            ps.setString(2, mobType);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new EncyclopediaEntry(
                            rs.getString("mob_type"),
                            Rarity.fromString(rs.getString("highest_rarity")),
                            rs.getInt("tame_count"),
                            rs.getLong("first_tamed_at")
                    );
                }
            }
        }
        return null;
    }

    // --- Inner Record ---

    /**
     * Immutable record representing a single encyclopedia entry.
     */
    public record EncyclopediaEntry(
            @NotNull String mobType,
            @NotNull Rarity highestRarity,
            int tameCount,
            long firstTamedAt
    ) {}
}
