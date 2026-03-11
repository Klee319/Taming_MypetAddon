# Scheduler & Database

## スケジューラ

### BukkitScheduler（基本）

```java
import org.bukkit.scheduler.BukkitRunnable;

// 即時実行（メインスレッド）
Bukkit.getScheduler().runTask(plugin, () -> {
    // メインスレッド処理
});

// 遅延実行（20tick = 1秒後）
Bukkit.getScheduler().runTaskLater(plugin, () -> {
    // 1秒後に実行
}, 20L);

// 繰り返し実行（20tick後に開始、100tickごとに繰り返し）
Bukkit.getScheduler().runTaskTimer(plugin, () -> {
    // 5秒ごとに実行
}, 20L, 100L);

// 非同期実行（別スレッド - DB/IO処理用）
Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
    // 非同期処理（Bukkit APIを呼ばないこと）
});

// 非同期タイマー
Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
    // 定期的な非同期処理
}, 0L, 20L * 60); // 毎分
```

### BukkitRunnable（キャンセル可能）

```java
new BukkitRunnable() {
    int count = 0;

    @Override
    public void run() {
        count++;
        if (count >= 10) {
            cancel(); // 自身をキャンセル
            return;
        }
        // 処理
    }
}.runTaskTimer(plugin, 0L, 20L);
```

### Folia互換スケジューラ（推奨）

Paper独自のスケジューラ。Folia移行時もコード変更不要。

```java
// AsyncScheduler - サーバーティックと独立
Bukkit.getAsyncScheduler().runNow(plugin, task -> {
    // 非同期処理
});
Bukkit.getAsyncScheduler().runDelayed(plugin, task -> {
    // 3秒後に非同期実行
}, 3, TimeUnit.SECONDS);
Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
    // 1秒ごとに非同期実行
}, 0, 1, TimeUnit.SECONDS);

// GlobalRegionScheduler - グローバルタスク
Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
    // メインスレッド即時実行
});
Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
    // メインスレッド遅延実行
}, 20L);

// RegionScheduler - 位置に紐づくタスク
Bukkit.getRegionScheduler().run(plugin, location, task -> {
    // その位置のリージョンで実行
});

// EntityScheduler - エンティティに紐づくタスク
entity.getScheduler().run(plugin, task -> {
    // そのエンティティのリージョンで実行
}, null); // retired callback（エンティティ消滅時）
```

### 非同期→メインスレッド パターン

```java
// 非同期でDB読み込み → メインスレッドで結果適用
Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
    // 非同期: DBからデータ取得
    String data = database.loadData(player.getUniqueId());

    // メインスレッドに戻す
    Bukkit.getScheduler().runTask(plugin, () -> {
        // メインスレッド: Bukkit APIを安全に使用
        player.sendMessage(Component.text("Data: " + data));
    });
});
```

**重要**: Bukkit APIはメインスレッド以外から呼び出し禁止。非同期タスクでは必ずメインスレッドに戻す。

## データベース

### SQLite（バンドル済み）

```java
import java.sql.*;

public class Database {
    private Connection connection;
    private final JavaPlugin plugin;

    public Database(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        try {
            connection = DriverManager.getConnection(
                "jdbc:sqlite:" + plugin.getDataFolder() + "/data.db");

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_data (
                        uuid TEXT PRIMARY KEY,
                        coins INTEGER DEFAULT 0,
                        last_login INTEGER
                    )
                    """);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Database initialization failed: " + e.getMessage());
        }
    }

    public int getCoins(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT coins FROM player_data WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt("coins") : 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get coins: " + e.getMessage());
            return 0;
        }
    }

    public void setCoins(UUID uuid, int coins) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO player_data (uuid, coins) VALUES (?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, coins);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to set coins: " + e.getMessage());
        }
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to close database: " + e.getMessage());
        }
    }
}
```

### MySQL / MariaDB (HikariCP)

`build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.zaxxer:HikariCP:6.2.1")
}
```

```java
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class MySQLDatabase {
    private HikariDataSource dataSource;

    public void initialize(FileConfiguration config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:mysql://"
            + config.getString("database.host") + ":"
            + config.getInt("database.port") + "/"
            + config.getString("database.name"));
        hikariConfig.setUsername(config.getString("database.username"));
        hikariConfig.setPassword(config.getString("database.password"));
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setMaxLifetime(1800000);  // 30分
        hikariConfig.setConnectionTimeout(5000);  // 5秒

        dataSource = new HikariDataSource(hikariConfig);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // try-with-resources で必ず接続を返却
    public int getCoins(UUID uuid) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT coins FROM player_data WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt("coins") : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    public void close() {
        if (dataSource != null) dataSource.close();
    }
}
```

### 非同期DBアクセスパターン

```java
public class PlayerDataManager {
    private final JavaPlugin plugin;
    private final Database database;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();

    public CompletableFuture<PlayerData> loadPlayerData(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            return database.loadPlayer(uuid); // 非同期スレッド
        }).thenApply(data -> {
            cache.put(uuid, data);
            return data;
        });
    }

    public void savePlayerData(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data == null) return;

        // 非同期で保存
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            database.savePlayer(uuid, data);
        });
    }

    // プレイヤー参加時にロード
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        loadPlayerData(event.getPlayer().getUniqueId())
            .thenAccept(data -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    event.getPlayer().sendMessage(
                        Component.text("Welcome back! Coins: " + data.getCoins()));
                });
            });
    }

    // プレイヤー退出時にセーブ&キャッシュ削除
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        savePlayerData(event.getPlayer().getUniqueId());
        cache.remove(event.getPlayer().getUniqueId());
    }
}
```

### ベストプラクティス

1. **メインスレッドでDB操作禁止** - 常に非同期で実行
2. **プリペアドステートメント必須** - SQLインジェクション防止
3. **try-with-resources** - 接続リーク防止
4. **コネクションプール** - MySQL利用時はHikariCP推奨
5. **キャッシュ活用** - 頻繁なクエリを避けメモリキャッシュを利用
6. **オフロード** - ソート/フィルタはSQL側で行いJava側で処理しない
