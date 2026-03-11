---
name: paper-plugin-dev
description: >
  Minecraft Paper API plugin development for Java. Covers project setup (Gradle, paper-plugin.yml),
  event listeners, Brigadier commands, Adventure Component/MiniMessage text system, custom items/blocks/entities,
  inventory GUIs, schedulers (BukkitScheduler + Folia-compatible), database integration (SQLite/MySQL with HikariCP),
  Persistent Data Container (PDC), Vault economy, scoreboards, particles, and world management.
  Target: Paper API 1.21.11 (Java 21+). Note: Paper hard-forked from Spigot since 1.21.4.
  TRIGGER when: user asks to create/modify a Minecraft plugin, Paper/Bukkit/Spigot plugin development,
  server plugin, mentions paper-plugin.yml or plugin.yml, or works with Paper API classes.
  DO NOT TRIGGER when: NeoForge/Fabric mod development, Bedrock Edition, client-side mods.
---

# Paper Plugin Development

Paper API 1.21.11 / Java 21+ 向けのMinecraftサーバープラグイン開発スキル。
1.21.4以降PaperはSpigotからハードフォーク済み。Timingsは非推奨（sparkがデフォルト）、Metadatable APIは非推奨（PDC推奨）。

## Reference Files

| File | Content | When to Read |
|------|---------|-------------|
| [project-setup.md](references/project-setup.md) | Gradle設定, paper-plugin.yml, プロジェクト構成, メインクラス | プロジェクト新規作成時 |
| [adventure-api.md](references/adventure-api.md) | Component, MiniMessage, Audience, Title, BossBar, Sound | テキスト表示・メッセージ処理時 |
| [events-commands.md](references/events-commands.md) | イベントリスナー, Brigadier API, plugin.ymlコマンド, カスタムイベント | コマンド・イベント実装時 |
| [items-blocks-entities.md](references/items-blocks-entities.md) | ItemStack, PDC, レシピ, ブロック操作, エンティティ, インベントリGUI | アイテム・ブロック・GUI実装時 |
| [scheduler-database.md](references/scheduler-database.md) | BukkitScheduler, Folia互換, SQLite, MySQL/HikariCP, 非同期パターン | スケジューラ・DB実装時 |
| [configuration-permissions.md](references/configuration-permissions.md) | config.yml, 権限システム, Vault連携 | 設定・権限・経済実装時 |
| [world-scoreboard.md](references/world-scoreboard.md) | ワールド, チャンク, スコアボード, パーティクル, ユーティリティ | ワールド操作・視覚演出時 |

## Core Patterns

### Plugin Lifecycle

```
onLoad() → ワールドロード前の初期化
onEnable() → リスナー登録, コマンド登録, 設定読み込み, DB接続
onDisable() → データ保存, リソース解放, DB切断
```

### Thread Safety Rules

1. **Bukkit APIはメインスレッドからのみ呼び出す**
2. DB/ファイルI/O/HTTPは非同期で実行
3. 非同期→メイン: `Bukkit.getScheduler().runTask(plugin, () -> { ... })`
4. `ConcurrentHashMap` でスレッド間共有データを管理

### Adventure API (Text)

Paper は `String` / `ChatColor` の代わりに Adventure `Component` を使用。

```java
// MiniMessage（推奨）
Component msg = MiniMessage.miniMessage().deserialize("<gold>Hello <player>!",
    Placeholder.parsed("player", name));
player.sendMessage(msg);

// Component Builder
Component msg = Component.text("Hello ", NamedTextColor.GREEN)
    .append(Component.text("World", NamedTextColor.GOLD, TextDecoration.BOLD));
```

### Event Pattern

```java
public class MyListener implements Listener {
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEvent(SomeEvent event) { /* ... */ }
}
// Register: getServer().getPluginManager().registerEvents(new MyListener(), this);
```

### GUI Pattern

`InventoryHolder` を実装 → `Bukkit.createInventory(holder, size, title)` → `InventoryClickEvent` で `event.getInventory().getHolder(false) instanceof MyGUI` で識別。詳細は items-blocks-entities.md 参照。

## Common Pitfalls

1. **非同期スレッドからBukkit API呼び出し** → `IllegalStateException`。必ずメインスレッドに戻す
2. **PlayerQuitEvent後のPlayer参照** → 無効。UUID でデータ管理し、オフライン後は `Bukkit.getOfflinePlayer(uuid)` 使用
3. **onDisable()での非同期タスク** → サーバー停止時は同期で保存する（非同期は完了しない可能性がある）
4. **メインスレッドでのDB操作** → サーバーラグの原因。必ず非同期で
5. **`new ItemStack(Material.AIR)`** → 使用禁止。`null` か `ItemStack.empty()` を使用
6. **InventoryClickEvent内でのインベントリ変更** → 次ティックにスケジュールするか、先に `setCancelled(true)` する
7. **static フィールドでのプラグイン参照** → リロード時に古い参照が残る。依存注入パターン推奨
