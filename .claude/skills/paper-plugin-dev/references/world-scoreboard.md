# World, Scoreboard & Utilities

## ワールド操作

```java
import org.bukkit.World;
import org.bukkit.WorldCreator;

// ワールド取得
World world = Bukkit.getWorld("world");
World nether = Bukkit.getWorld("world_nether");
World end = Bukkit.getWorld("world_the_end");

// テレポート
player.teleport(new Location(world, x, y, z, yaw, pitch));

// ワールド設定
world.setTime(6000); // 正午
world.setStorm(false);
world.setThundering(false);
world.setDifficulty(Difficulty.HARD);

// ゲームルール
world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
world.setGameRule(GameRule.KEEP_INVENTORY, true);
world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);

// ワールドボーダー
WorldBorder border = world.getWorldBorder();
border.setCenter(0, 0);
border.setSize(1000); // 半径500ブロック
border.setDamageAmount(1.0);

// ブロック検索
int highestY = world.getHighestBlockYAt(x, z);
```

## チャンク操作

```java
import org.bukkit.Chunk;

// チャンク取得
Chunk chunk = loc.getChunk();

// チャンク座標（ブロック座標 >> 4）
int chunkX = blockX >> 4;
int chunkZ = blockZ >> 4;

// 強制ロード（プラグインチケット）
world.addPluginChunkTicket(chunkX, chunkZ, plugin);

// 強制ロード解除
world.removePluginChunkTicket(chunkX, chunkZ, plugin);

// 非同期チャンクロード
world.getChunkAtAsync(chunkX, chunkZ).thenAccept(chunk -> {
    // チャンクがロードされた後の処理
});

// ロード済みチェック
boolean loaded = world.isChunkLoaded(chunkX, chunkZ);
```

## スコアボード

```java
import org.bukkit.scoreboard.*;

// サイドバースコアボード作成
Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
Objective obj = board.registerNewObjective("sidebar", Criteria.DUMMY,
    Component.text("My Server", NamedTextColor.GOLD, TextDecoration.BOLD));
obj.setDisplaySlot(DisplaySlot.SIDEBAR);

// スコア設定（行表示、上から下へ番号が大きい順）
obj.getScore("§aOnline: §f" + Bukkit.getOnlinePlayers().size()).setScore(5);
obj.getScore("§7-----------").setScore(4);
obj.getScore("§eCoins: §f1000").setScore(3);
obj.getScore("§7").setScore(2);  // 空行
obj.getScore("§bplay.myserver.com").setScore(1);

// プレイヤーに適用
player.setScoreboard(board);
```

### 動的スコアボード更新

```java
public class SidebarManager {
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final JavaPlugin plugin;

    public void createBoard(Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective("sidebar", Criteria.DUMMY,
            Component.text("Server Info", NamedTextColor.GOLD));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        boards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
        updateBoard(player);
    }

    public void updateBoard(Player player) {
        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null) return;

        Objective obj = board.getObjective("sidebar");
        if (obj == null) return;

        // 既存エントリをクリア
        board.getEntries().forEach(board::resetScores);

        // 新しいスコアを設定
        obj.getScore("§aPlayers: §f" + Bukkit.getOnlinePlayers().size()).setScore(3);
        obj.getScore("§eTPS: §f" + String.format("%.1f", Bukkit.getTPS()[0])).setScore(2);
        obj.getScore("§bPing: §f" + player.getPing() + "ms").setScore(1);
    }

    public void removeBoard(UUID uuid) {
        boards.remove(uuid);
    }
}
```

## パーティクル & サウンド

```java
import org.bukkit.Particle;
import org.bukkit.Sound;

// パーティクル
player.getWorld().spawnParticle(Particle.HEART, player.getLocation().add(0, 2, 0),
    10,    // count
    0.5,   // offsetX
    0.5,   // offsetY
    0.5,   // offsetZ
    0.1    // speed
);

// 特定プレイヤーにのみ表示
player.spawnParticle(Particle.FLAME, loc, 20, 0.3, 0.3, 0.3, 0.05);

// 円形パーティクル
for (double angle = 0; angle < Math.PI * 2; angle += Math.PI / 16) {
    double x = Math.cos(angle) * radius;
    double z = Math.sin(angle) * radius;
    world.spawnParticle(Particle.END_ROD, loc.clone().add(x, 0, z), 1, 0, 0, 0, 0);
}

// サウンド（Bukkit API）
player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);

// サウンド（Adventure API）
import net.kyori.adventure.sound.Sound as AdventureSound;
import net.kyori.adventure.key.Key;

player.playSound(AdventureSound.sound(
    Key.key("entity.experience_orb.pickup"),
    AdventureSound.Source.MASTER,
    1.0f,  // volume
    1.5f   // pitch
));
```

## ユーティリティパターン

### クールダウン

```java
public class CooldownManager {
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    public boolean hasCooldown(UUID uuid, String key) {
        return getRemainingCooldown(uuid, key) > 0;
    }

    public long getRemainingCooldown(UUID uuid, String key) {
        Map<String, Long> playerCooldowns = cooldowns.get(uuid);
        if (playerCooldowns == null) return 0;
        Long expiry = playerCooldowns.get(key);
        if (expiry == null) return 0;
        long remaining = expiry - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    public void setCooldown(UUID uuid, String key, long durationMs) {
        cooldowns.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
            .put(key, System.currentTimeMillis() + durationMs);
    }

    public void removeCooldown(UUID uuid, String key) {
        Map<String, Long> playerCooldowns = cooldowns.get(uuid);
        if (playerCooldowns != null) playerCooldowns.remove(key);
    }

    public void clearPlayer(UUID uuid) {
        cooldowns.remove(uuid);
    }
}
```

### Location ユーティリティ

```java
// Location → 文字列（設定ファイル保存用）
public static String serializeLocation(Location loc) {
    return loc.getWorld().getName() + ","
        + loc.getX() + "," + loc.getY() + "," + loc.getZ()
        + "," + loc.getYaw() + "," + loc.getPitch();
}

public static Location deserializeLocation(String str) {
    String[] parts = str.split(",");
    World world = Bukkit.getWorld(parts[0]);
    return new Location(world,
        Double.parseDouble(parts[1]),
        Double.parseDouble(parts[2]),
        Double.parseDouble(parts[3]),
        Float.parseFloat(parts[4]),
        Float.parseFloat(parts[5]));
}
```
