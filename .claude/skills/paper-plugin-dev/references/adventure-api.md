# Adventure API (Component System)

Paper はBukkit標準の `ChatColor` / 文字列ベースのメッセージシステムの代わりに、Adventure ライブラリのComponent APIを採用。

## Component 基礎

```java
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

// 基本テキスト
Component msg = Component.text("Hello World");

// 色付き
Component colored = Component.text("Red text", NamedTextColor.RED);

// 装飾付き
Component styled = Component.text("Bold and Gold")
    .color(NamedTextColor.GOLD)
    .decorate(TextDecoration.BOLD);

// 子コンポーネント結合
Component combined = Component.text("Welcome ")
    .color(NamedTextColor.GREEN)
    .append(Component.text("Player").color(NamedTextColor.YELLOW))
    .append(Component.text("!").color(NamedTextColor.GREEN));

// 翻訳可能コンポーネント
Component translatable = Component.translatable("block.minecraft.diamond_block");

// クリック/ホバーイベント
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

Component interactive = Component.text("[Click Me]")
    .color(NamedTextColor.AQUA)
    .clickEvent(ClickEvent.runCommand("/help"))
    .hoverEvent(HoverEvent.showText(Component.text("Click for help")));
```

**重要**: Component はイミュータブル。全メソッドは新しいインスタンスを返す。

## MiniMessage

人間可読な文字列フォーマットからComponentへ変換。

```java
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

MiniMessage mm = MiniMessage.miniMessage();

// 基本パース
Component msg = mm.deserialize("<green>Hello <bold>World</bold>!</green>");

// プレースホルダー付き
Component welcome = mm.deserialize(
    "<gold>Welcome <player> to the server!",
    Placeholder.parsed("player", player.getName())
);

// コンポーネントプレースホルダー
Component info = mm.deserialize(
    "Click <btn> to continue",
    Placeholder.component("btn",
        Component.text("[HERE]").clickEvent(ClickEvent.runCommand("/next")))
);
```

### MiniMessage タグ一覧

| タグ | 例 | 説明 |
|------|-----|------|
| 色 | `<red>`, `<#ff0000>`, `<color:blue>` | テキスト色 |
| 太字 | `<bold>` / `<b>` | 太字 |
| 斜体 | `<italic>` / `<i>` / `<em>` | 斜体 |
| 下線 | `<underlined>` / `<u>` | 下線 |
| 取り消し線 | `<strikethrough>` / `<st>` | 取り消し線 |
| 難読化 | `<obfuscated>` / `<obf>` | 文字化け表示 |
| グラデーション | `<gradient:red:blue>text</gradient>` | 色のグラデーション |
| レインボー | `<rainbow>text</rainbow>` | 虹色 |
| クリック | `<click:run_command:/cmd>` | クリックイベント |
| ホバー | `<hover:show_text:'text'>` | ホバーテキスト |
| 挿入 | `<insertion:text>` | Shift+クリックで挿入 |
| リセット | `<reset>` | 全スタイルリセット |

### クリックイベントアクション

- `run_command` - コマンド実行
- `suggest_command` - チャットにコマンド入力
- `open_url` - URLを開く
- `copy_to_clipboard` - クリップボードにコピー

## Audience

メッセージの送信先を抽象化するインターフェース。

```java
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.bossbar.BossBar;

// プレイヤーへ送信
player.sendMessage(Component.text("Hello!"));

// タイトル表示
Title title = Title.title(
    Component.text("Welcome!", NamedTextColor.GOLD),
    Component.text("Enjoy your stay", NamedTextColor.GRAY),
    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500))
);
player.showTitle(title);

// アクションバー
player.sendActionBar(Component.text("Health: 20", NamedTextColor.RED));

// サウンド
player.playSound(Sound.sound(
    Key.key("entity.experience_orb.pickup"),
    Sound.Source.MASTER, 1.0f, 1.0f
));

// ボスバー
BossBar bar = BossBar.bossBar(
    Component.text("Event Progress"),
    0.5f,  // 0.0 ~ 1.0
    BossBar.Color.PURPLE,
    BossBar.Overlay.PROGRESS
);
player.showBossBar(bar);

// 複数対象
Audience group = Audience.audience(player1, player2, player3);
group.sendMessage(Component.text("Group message"));

// サーバー全体
getServer().sendMessage(Component.text("Broadcast!"));
```

## Legacy変換（非推奨だが互換用）

```java
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

// §コード文字列 → Component
Component comp = LegacyComponentSerializer.legacySection().deserialize("§aGreen §btext");

// Component → §コード文字列
String legacy = LegacyComponentSerializer.legacySection().serialize(comp);

// &コード文字列 → Component
Component comp2 = LegacyComponentSerializer.legacyAmpersand().deserialize("&aGreen &btext");
```
