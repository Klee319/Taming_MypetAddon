# Configuration & Permissions

## 設定ファイル (config.yml)

### デフォルト設定

`src/main/resources/config.yml`:
```yaml
prefix: "<gold>[MyPlugin]</gold> "
database:
  type: sqlite  # sqlite or mysql
  host: localhost
  port: 3306
  name: myplugin
  username: root
  password: ""
settings:
  max-homes: 3
  teleport-delay: 5
  enabled-worlds:
    - world
    - world_nether
messages:
  welcome: "<green>Welcome to the server, <player>!</green>"
  no-permission: "<red>You don't have permission to do this.</red>"
```

### 設定の読み書き

```java
@Override
public void onEnable() {
    // デフォルト設定をデータフォルダにコピー（既存なら上書きしない）
    saveDefaultConfig();

    // 値の取得
    FileConfiguration config = getConfig();
    String prefix = config.getString("prefix", "[MyPlugin] ");
    int maxHomes = config.getInt("settings.max-homes", 3);
    boolean debug = config.getBoolean("settings.debug", false);
    List<String> worlds = config.getStringList("settings.enabled-worlds");

    // 値の設定
    config.set("settings.max-homes", 5);
    saveConfig();

    // リロード
    reloadConfig();
}
```

### カスタム設定ファイル

```java
private FileConfiguration messagesConfig;
private File messagesFile;

public void loadMessagesConfig() {
    messagesFile = new File(getDataFolder(), "messages.yml");
    if (!messagesFile.exists()) {
        saveResource("messages.yml", false);
    }
    messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
}

public void saveMessagesConfig() {
    try {
        messagesConfig.save(messagesFile);
    } catch (IOException e) {
        getLogger().warning("Failed to save messages.yml: " + e.getMessage());
    }
}
```

## 権限システム

### plugin.yml での定義

```yaml
permissions:
  myplugin.*:
    description: All permissions
    default: op
    children:
      myplugin.use: true
      myplugin.admin: true
  myplugin.use:
    description: Basic usage
    default: true
  myplugin.admin:
    description: Admin commands
    default: op
  myplugin.bypass.cooldown:
    description: Bypass cooldown
    default: false
```

### default値

| 値 | 説明 |
|----|------|
| `true` | 全プレイヤーに付与 |
| `false` | 誰にも付与しない（権限プラグインで付与） |
| `op` | OP限定 |
| `not op` | OP以外 |

### コード内での権限チェック

```java
// 基本チェック
if (player.hasPermission("myplugin.admin")) {
    // 管理者処理
}

// 動的権限付与
PermissionAttachment attachment = player.addAttachment(plugin);
attachment.setPermission("myplugin.vip", true);

// 権限削除
player.removeAttachment(attachment);

// 権限によるアイテム数制限パターン
int maxHomes = 1;
for (int i = 50; i > 1; i--) {
    if (player.hasPermission("myplugin.homes." + i)) {
        maxHomes = i;
        break;
    }
}
```

### Vault連携

`build.gradle.kts`:
```kotlin
repositories {
    maven("https://jitpack.io")
}
dependencies {
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
}
```

```java
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;

private Economy economy;

@Override
public void onEnable() {
    if (!setupEconomy()) {
        getLogger().severe("Vault Economy not found!");
        getServer().getPluginManager().disablePlugin(this);
        return;
    }
}

private boolean setupEconomy() {
    if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
    RegisteredServiceProvider<Economy> rsp =
        getServer().getServicesManager().getRegistration(Economy.class);
    if (rsp == null) return false;
    economy = rsp.getProvider();
    return true;
}

// 使用例
public void payPlayer(Player player, double amount) {
    economy.depositPlayer(player, amount);
    player.sendMessage(Component.text("+" + amount + " coins!", NamedTextColor.GOLD));
}

public boolean chargePlayer(Player player, double amount) {
    if (!economy.has(player, amount)) return false;
    economy.withdrawPlayer(player, amount);
    return true;
}
```
