# Events & Commands

## イベントリスナー

```java
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class MyListener implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        event.joinMessage(Component.text(event.getPlayer().getName() + " joined!")
            .color(NamedTextColor.GREEN));
    }

    // 優先度指定 + キャンセル済みイベントをスキップ
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        // HIGH優先度で実行、既にキャンセルされたイベントは無視
    }
}
```

### 登録

```java
// onEnable() 内で
getServer().getPluginManager().registerEvents(new MyListener(), this);
```

### イベント優先度（実行順）

1. `LOWEST` → 最初に実行
2. `LOW`
3. `NORMAL` → デフォルト
4. `HIGH`
5. `HIGHEST` → 最終決定権
6. `MONITOR` → 監視専用（変更禁止）

### よく使うイベント

| カテゴリ | イベント | 説明 |
|----------|---------|------|
| Player | `PlayerJoinEvent` | プレイヤー参加 |
| Player | `PlayerQuitEvent` | プレイヤー退出 |
| Player | `PlayerInteractEvent` | ブロック/空気にインタラクト |
| Player | `PlayerMoveEvent` | 移動（高頻度注意） |
| Player | `AsyncPlayerChatEvent` | チャット（非同期） |
| Block | `BlockBreakEvent` | ブロック破壊 |
| Block | `BlockPlaceEvent` | ブロック設置 |
| Entity | `EntityDamageByEntityEvent` | エンティティ間ダメージ |
| Entity | `EntityDeathEvent` | エンティティ死亡 |
| Entity | `CreatureSpawnEvent` | エンティティスポーン |
| Inventory | `InventoryClickEvent` | インベントリクリック |
| Inventory | `InventoryOpenEvent` | インベントリ開く |
| Server | `ServerLoadEvent` | サーバーロード完了 |

### カスタムイベント

```java
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Cancellable;

public class CustomEvent extends Event implements Cancellable {
    private static final HandlerList HANDLER_LIST = new HandlerList();
    private boolean cancelled;
    private final Player player;
    private int value;

    public CustomEvent(Player player, int value) {
        this.player = player;
        this.value = value;
    }

    public Player getPlayer() { return player; }
    public int getValue() { return value; }
    public void setValue(int value) { this.value = value; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLER_LIST; }
    public static HandlerList getHandlerList() { return HANDLER_LIST; }
}

// 発火
CustomEvent event = new CustomEvent(player, 42);
Bukkit.getPluginManager().callEvent(event);
if (!event.isCancelled()) {
    // 処理続行
}
```

## コマンド

### plugin.yml ベース（Bukkit互換）

```java
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public class MyCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only!", NamedTextColor.RED));
            return true;
        }

        if (!player.hasPermission("myplugin.use")) {
            player.sendMessage(Component.text("No permission!", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /mycommand <arg>", NamedTextColor.YELLOW));
            return true;
        }

        // 処理
        player.sendMessage(Component.text("Executed with: " + args[0], NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("option1", "option2", "option3").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .toList();
        }
        return List.of();
    }
}

// onEnable()で登録
getCommand("mycommand").setExecutor(new MyCommand());
getCommand("mycommand").setTabCompleter(new MyCommand());
```

### Brigadier Command API（Paper推奨、実験的）

```java
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

// onEnable() 内で
getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
    final Commands commands = event.registrar();

    commands.register(
        Commands.literal("myplugin")
            .then(Commands.literal("give")
                .then(Commands.argument("player", ArgumentTypes.player())
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> {
                            var player = ctx.getArgument("player", PlayerSelectorArgumentResolver.class)
                                .resolve(ctx.getSource()).getFirst();
                            int amount = IntegerArgumentType.getInteger(ctx, "amount");
                            // 処理
                            return Command.SINGLE_SUCCESS;
                        })
                    )
                )
            )
            .then(Commands.literal("reload")
                .requires(src -> src.getSender().hasPermission("myplugin.admin"))
                .executes(ctx -> {
                    // リロード処理
                    return Command.SINGLE_SUCCESS;
                })
            )
            .build(),
        "Main plugin command",
        List.of("mp") // エイリアス
    );
});
```
