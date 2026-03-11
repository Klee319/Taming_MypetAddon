# Project Setup

## Gradle (build.gradle.kts)

```kotlin
plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.14" // optional: NMS access
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // NMS access (optional, requires paperweight plugin):
    // paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}
```

## paper-plugin.yml (推奨)

`src/main/resources/paper-plugin.yml`:

```yaml
name: MyPlugin
version: "${version}"
main: com.example.myplugin.MyPlugin
description: A Paper plugin
api-version: "1.21"
author: YourName

# 依存関係
dependencies:
  server:
    Vault:
      load: BEFORE
      required: false
      join-classpath: true

# 権限定義
permissions:
  myplugin.use:
    description: Basic usage permission
    default: true
  myplugin.admin:
    description: Admin permission
    default: op
```

## plugin.yml (Bukkit互換)

`src/main/resources/plugin.yml`:

```yaml
name: MyPlugin
version: "${version}"
main: com.example.myplugin.MyPlugin
api-version: "1.21"
description: A Paper plugin
author: YourName
depend: []
softdepend: [Vault]

commands:
  mycommand:
    description: My command
    usage: /<command> [args]
    permission: myplugin.use

permissions:
  myplugin.use:
    description: Basic usage
    default: true
```

## paper-plugin.yml vs plugin.yml

| 項目 | paper-plugin.yml | plugin.yml |
|------|-----------------|------------|
| クラスローダー | 分離（安全） | 共有（他プラグインのクラスにアクセス可能） |
| コマンド登録 | Brigadier API | YAMLで宣言 |
| 依存管理 | ロード順とクラスアクセスを分離 | dependで一括管理 |
| 推奨度 | 新規プラグイン向け | レガシー互換 |

## プロジェクト構成

```
my-plugin/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── src/main/
    ├── java/com/example/myplugin/
    │   ├── MyPlugin.java          # メインクラス
    │   ├── commands/               # コマンド
    │   ├── listeners/              # イベントリスナー
    │   ├── gui/                    # GUI/インベントリ
    │   ├── data/                   # データ管理
    │   └── util/                   # ユーティリティ
    └── resources/
        ├── paper-plugin.yml        # プラグイン定義
        └── config.yml              # デフォルト設定
```

## メインクラス

```java
package com.example.myplugin;

import org.bukkit.plugin.java.JavaPlugin;

public class MyPlugin extends JavaPlugin {

    private static MyPlugin instance;

    @Override
    public void onLoad() {
        // ワールドロード前の初期化（データ準備等）
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // イベントリスナー登録
        getServer().getPluginManager().registerEvents(new MyListener(), this);

        // コマンド登録（plugin.ymlの場合）
        getCommand("mycommand").setExecutor(new MyCommand());

        getLogger().info("MyPlugin enabled!");
    }

    @Override
    public void onDisable() {
        // リソース解放、データ保存
        getLogger().info("MyPlugin disabled!");
    }

    public static MyPlugin getInstance() {
        return instance;
    }
}
```
