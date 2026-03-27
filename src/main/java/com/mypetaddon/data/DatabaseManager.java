package com.mypetaddon.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages SQLite database connections via HikariCP connection pooling.
 * Handles schema creation and lifecycle management.
 */
public final class DatabaseManager {

    private final JavaPlugin plugin;
    private final Logger logger;
    private HikariDataSource dataSource;

    public DatabaseManager(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Initializes the HikariCP data source and creates the database schema.
     *
     * @return true if initialization succeeds
     */
    public boolean initialize() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                logger.severe("[DB] Failed to create data folder: " + dataFolder.getAbsolutePath());
                return false;
            }

            String dbPath = new File(dataFolder, "pets.db").getAbsolutePath();

            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbPath);
            hikariConfig.setDriverClassName("org.sqlite.JDBC");
            hikariConfig.setMaximumPoolSize(1); // SQLite supports single writer
            hikariConfig.setMinimumIdle(1);
            hikariConfig.setConnectionTimeout(30_000);
            hikariConfig.setIdleTimeout(600_000);
            hikariConfig.setMaxLifetime(1_800_000);
            hikariConfig.setPoolName("MyPetAddon-SQLite");

            dataSource = new HikariDataSource(hikariConfig);

            createSchema();
            logger.info("[DB] Database initialized successfully at: " + dbPath);
            return true;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "[DB] Failed to initialize database", e);
            return false;
        }
    }

    /**
     * Obtains a connection from the HikariCP pool.
     *
     * @return a pooled Connection
     * @throws SQLException if the pool is closed or connection fails
     */
    @NotNull
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("DataSource is not initialized or has been closed.");
        }
        return dataSource.getConnection();
    }

    /**
     * Closes the HikariCP data source and all pooled connections.
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("[DB] Database connection pool closed.");
        }
    }

    // --- Schema Creation ---

    private void createSchema() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Enable WAL mode for better concurrent read performance
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");
            stmt.execute("PRAGMA foreign_keys=ON");

            // Core pet data
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS pet_data ("
                + "addon_pet_id TEXT PRIMARY KEY, "
                + "mypet_uuid   TEXT NOT NULL, "
                + "owner_uuid   TEXT NOT NULL, "
                + "mob_type     TEXT NOT NULL, "
                + "rarity       TEXT NOT NULL DEFAULT 'COMMON', "
                + "personality  TEXT NOT NULL, "
                + "bond_level   INTEGER NOT NULL DEFAULT 0, "
                + "bond_exp     INTEGER NOT NULL DEFAULT 0, "
                + "original_lm_level INTEGER NOT NULL DEFAULT 0, "
                + "created_at   INTEGER NOT NULL DEFAULT (strftime('%s','now')), "
                + "evolved_from TEXT"
                + ")"
            );

            // Per-stat storage (normalized)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS pet_stats ("
                + "addon_pet_id TEXT NOT NULL, "
                + "stat_name    TEXT NOT NULL, "
                + "base_value   REAL NOT NULL DEFAULT 0.0, "
                + "upgraded_value REAL NOT NULL DEFAULT 0.0, "
                + "PRIMARY KEY (addon_pet_id, stat_name), "
                + "FOREIGN KEY (addon_pet_id) REFERENCES pet_data(addon_pet_id) ON DELETE CASCADE"
                + ")"
            );

            // Equipment slots
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS pet_equipment ("
                + "addon_pet_id TEXT NOT NULL, "
                + "slot         TEXT NOT NULL, "
                + "item_data    TEXT NOT NULL, "
                + "PRIMARY KEY (addon_pet_id, slot), "
                + "FOREIGN KEY (addon_pet_id) REFERENCES pet_data(addon_pet_id) ON DELETE CASCADE"
                + ")"
            );

            // Skill progression
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS pet_skills ("
                + "addon_pet_id TEXT NOT NULL, "
                + "skill_id     TEXT NOT NULL, "
                + "skill_level  INTEGER NOT NULL DEFAULT 1, "
                + "PRIMARY KEY (addon_pet_id, skill_id), "
                + "FOREIGN KEY (addon_pet_id) REFERENCES pet_data(addon_pet_id) ON DELETE CASCADE"
                + ")"
            );

            // Encyclopedia / Pokedex
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS encyclopedia ("
                + "owner_uuid     TEXT NOT NULL, "
                + "mob_type       TEXT NOT NULL, "
                + "highest_rarity TEXT NOT NULL DEFAULT 'COMMON', "
                + "tame_count     INTEGER NOT NULL DEFAULT 0, "
                + "first_tamed_at INTEGER NOT NULL DEFAULT (strftime('%s','now')), "
                + "PRIMARY KEY (owner_uuid, mob_type)"
                + ")"
            );

            // Evolution history log
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS evolution_history ("
                + "id               INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "addon_pet_id     TEXT NOT NULL, "
                + "from_type        TEXT NOT NULL, "
                + "to_type          TEXT NOT NULL, "
                + "from_mypet_uuid  TEXT NOT NULL, "
                + "to_mypet_uuid    TEXT NOT NULL, "
                + "evolved_at       INTEGER NOT NULL DEFAULT (strftime('%s','now')), "
                + "FOREIGN KEY (addon_pet_id) REFERENCES pet_data(addon_pet_id) ON DELETE CASCADE"
                + ")"
            );

            // --- Migrations ---
            // Add captured_scale column if not present (added in v1.1)
            try {
                stmt.execute("ALTER TABLE pet_data ADD COLUMN captured_scale REAL NOT NULL DEFAULT 0.0");
            } catch (SQLException ignored) {
                // Column already exists — expected on subsequent starts
            }

            // --- Indexes ---
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pet_data_mypet_uuid ON pet_data(mypet_uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pet_data_owner_uuid ON pet_data(owner_uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pet_stats_addon_pet_id ON pet_stats(addon_pet_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pet_equipment_addon_pet_id ON pet_equipment(addon_pet_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_pet_skills_addon_pet_id ON pet_skills(addon_pet_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_encyclopedia_owner ON encyclopedia(owner_uuid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_evolution_history_pet ON evolution_history(addon_pet_id)");

            logger.info("[DB] Schema created/verified successfully.");
        }
    }
}
