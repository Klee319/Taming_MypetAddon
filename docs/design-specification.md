# MyPet Addon Plugin - Design Specification v1.0

## 1. Overview

MyPetプラグインと連携し、ペットシステムを大幅に拡張するPaper APIプラグイン。
LevelledMobs連携によるレアリティ、性格、親密度、進化、固有スキル、装備、図鑑システムを追加する。

## 2. Technical Stack & Compatibility Matrix

### 2.1 Dependencies

| Dependency | Type | Version | Notes |
|------------|------|---------|-------|
| Paper API | Hard | 1.21.x (Java 21+) | Minimum 1.21 |
| MyPet | Hard | 3.14.x | `de.Keyle.MyPet.*` |
| LevelledMobs | Soft | 4.x | 公式API優先、PDCフォールバック |
| MythicMobs | Soft | 5.x | テイム除外判定 |

### 2.2 Startup Compatibility Check

```java
// プラグイン起動時にfail-fast
@Override
public void onEnable() {
    // Hard dependency check
    if (!isPluginCompatible("MyPet", "3.14")) {
        getLogger().severe("MyPet 3.14+ is required!");
        getServer().getPluginManager().disablePlugin(this);
        return;
    }
    // Soft dependency check with warning
    if (isPluginPresent("LevelledMobs") && !isPluginCompatible("LevelledMobs", "4.0")) {
        getLogger().warning("LevelledMobs 4.0+ recommended. PDC fallback will be used.");
    }
}
```

### 2.3 Build Dependencies

```kotlin
// build.gradle.kts
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("de.keyle:mypet:3.14.0")
    compileOnly("io.github.arcaneplugins:levelledmobs-plugin:4.0.2")
    implementation("com.zaxxer:HikariCP:5.1.0")
}
```

## 3. Architecture

### 3.1 Package Structure

```
com.example.mypetaddon/
  +-- MyPetAddonPlugin.java          // エントリポイント
  +-- config/
  |     +-- ConfigManager.java        // YAML読込・バリデーション・アトミック反映
  |     +-- ConfigValidator.java      // スキーマ検証
  +-- data/
  |     +-- DatabaseManager.java      // HikariCP + 非同期DB操作
  |     +-- PetDataRepository.java    // pet_data CRUD
  |     +-- EncyclopediaRepository.java
  |     +-- MigrationManager.java     // Legacy YAML -> SQLite 移行
  |     +-- cache/
  |           +-- PetDataCache.java   // ConcurrentHashMap インメモリキャッシュ
  +-- taming/
  |     +-- TamingListener.java       // テイムイベント処理
  |     +-- TamingManager.java        // テイムロジック
  +-- rarity/
  |     +-- RarityManager.java        // レアリティ抽選
  |     +-- LevelledMobsIntegration.java // LM連携（API優先+PDCフォールバック）
  +-- personality/
  |     +-- PersonalityManager.java   // 性格抽選・適用
  +-- bond/
  |     +-- BondManager.java          // 親密度管理（デバウンス付き）
  |     +-- BondListener.java         // イベント検知
  +-- stats/
  |     +-- StatsManager.java         // 効果適用パイプライン
  |     +-- ModifierPipeline.java     // base -> rarity -> personality -> bond -> equip
  +-- evolution/
  |     +-- EvolutionManager.java     // 進化処理（安全な再作成）
  +-- skill/
  |     +-- PetSkillManager.java      // 固有スキル管理
  |     +-- CooldownTracker.java      // インメモリクールダウン
  +-- equipment/
  |     +-- EquipmentManager.java     // 装備管理
  +-- encyclopedia/
  |     +-- EncyclopediaManager.java  // 図鑑管理
  |     +-- EncyclopediaGUI.java      // GUI表示
  +-- integration/
  |     +-- MythicMobsIntegration.java
  +-- command/
  |     +-- PetCommandManager.java    // Brigadierコマンド登録
  +-- util/
        +-- SoundUtil.java            // Sound enum統一ユーティリティ
```

### 3.2 Execution Model (Critical)

```
[Main Thread (Bukkit)]              [Async Thread Pool (HikariCP)]
       |                                     |
  Event fires                                |
       |                                     |
  Read from cache (ConcurrentHashMap)        |
       |                                     |
  Update cache immediately                   |
       |                                     |
  Mark as dirty ----queue---->  Batch write (1-2秒間隔)
       |                                     |
  Apply to MyPet (sync)                      |
       |                                     |
                               onDisable() -> flush all dirty
```

**Rules:**
- DB I/O は**絶対に**メインスレッドで実行しない
- Bukkit API 操作（エンティティ、インベントリ等）は**必ず**メインスレッドで実行
- キャッシュ読み書きは `ConcurrentHashMap` で同期
- ダーティフラグ付きエントリを1-2秒間隔でバッチフラッシュ
- `onDisable()` で未保存データを同期的にフラッシュ

## 4. Data Architecture

### 4.1 SQLite Schema (Normalized)

```sql
-- アドオン独自UUID管理（MyPet UUID変更に耐える）
CREATE TABLE pet_data (
    addon_pet_id TEXT PRIMARY KEY,         -- アドオン独自UUID (不変)
    mypet_uuid TEXT NOT NULL,              -- MyPetのUUID (進化時に変更される)
    owner_uuid TEXT NOT NULL,
    mob_type TEXT NOT NULL,
    rarity TEXT NOT NULL DEFAULT 'COMMON',
    personality TEXT NOT NULL,
    bond_level INTEGER NOT NULL DEFAULT 0,
    bond_exp INTEGER NOT NULL DEFAULT 0,
    original_lm_level INTEGER DEFAULT 0,   -- テイム時のLevelledMobsレベル
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    evolved_from TEXT DEFAULT NULL,         -- 進化元のaddon_pet_id (進化チェーン)
    FOREIGN KEY (evolved_from) REFERENCES pet_data(addon_pet_id)
);
CREATE INDEX idx_pet_owner ON pet_data(owner_uuid);
CREATE INDEX idx_pet_mypet ON pet_data(mypet_uuid);

-- ステータス（正規化）
CREATE TABLE pet_stats (
    addon_pet_id TEXT NOT NULL,
    stat_name TEXT NOT NULL,               -- "Life", "Damage" 等
    base_value REAL NOT NULL DEFAULT 0,    -- 初期ランダム値
    upgraded_value REAL NOT NULL DEFAULT 0,-- 強化による加算値
    PRIMARY KEY (addon_pet_id, stat_name),
    FOREIGN KEY (addon_pet_id) REFERENCES pet_data(addon_pet_id) ON DELETE CASCADE
);

-- 装備（正規化）
CREATE TABLE pet_equipment (
    addon_pet_id TEXT NOT NULL,
    slot TEXT NOT NULL,                    -- "HELMET", "CHEST", "ACCESSORY"
    item_data TEXT NOT NULL,               -- Base64エンコード (BukkitObjectOutputStream)
    PRIMARY KEY (addon_pet_id, slot),
    FOREIGN KEY (addon_pet_id) REFERENCES pet_data(addon_pet_id) ON DELETE CASCADE
);

-- スキル（正規化）
CREATE TABLE pet_skills (
    addon_pet_id TEXT NOT NULL,
    skill_id TEXT NOT NULL,                -- "self-destruct", "howl" 等
    skill_level INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (addon_pet_id, skill_id),
    FOREIGN KEY (addon_pet_id) REFERENCES pet_data(addon_pet_id) ON DELETE CASCADE
);

-- 図鑑
CREATE TABLE encyclopedia (
    owner_uuid TEXT NOT NULL,
    mob_type TEXT NOT NULL,
    highest_rarity TEXT NOT NULL DEFAULT 'COMMON',
    tame_count INTEGER NOT NULL DEFAULT 0,
    first_tamed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_uuid, mob_type)
);
CREATE INDEX idx_encyclopedia_owner ON encyclopedia(owner_uuid);

-- 進化履歴
CREATE TABLE evolution_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    addon_pet_id TEXT NOT NULL,
    from_type TEXT NOT NULL,
    to_type TEXT NOT NULL,
    from_mypet_uuid TEXT NOT NULL,
    to_mypet_uuid TEXT NOT NULL,
    evolved_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (addon_pet_id) REFERENCES pet_data(addon_pet_id) ON DELETE CASCADE
);
```

### 4.2 Equipment Serialization

```java
// ItemStack -> Base64 (Bukkit標準シリアライゼーション)
public static String serializeItem(ItemStack item) {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    try (BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
        dataOutput.writeObject(item);
    }
    return Base64.getEncoder().encodeToString(outputStream.toByteArray());
}
```

### 4.3 Legacy Migration

```java
// 初回起動時に自動実行
public class MigrationManager {
    // 1. Legacy YAML (config) から mypet.<uuid>.base.* を読み取り
    // 2. pet_data + pet_stats に変換・挿入
    // 3. 成功後 config に migration_completed: true を書き込み
    // 4. 失敗時はロールバック + ログ出力、プラグイン無効化しない（新規データのみ使用）
}
```

## 5. Feature Specifications

### 5.1 Taming System

#### 動物テイム
```yaml
taming:
  animals:
    item: ENCHANTED_GOLDEN_APPLE   # configurable
    consume: true
    particle: HEART
    particle-count: 15
    sound: ENTITY_PLAYER_LEVELUP
  monsters:
    item: DIAMOND                  # configurable
    consume: true
    naming-timeout: 30             # seconds
    naming-command: "petname"
    single-target-per-player: true # 1プレイヤー1体制限（明示的仕様）
  excluded-types:                  # テイム不可Mob
    - ENDER_DRAGON
    - ELDER_GUARDIAN
  mythicmobs-exclusion: true       # MythicMobsのMobを除外
```

#### テイムフロー（モンスター）
1. プレイヤーがダイヤモンドで右クリック → ダイヤ消費、ターゲット登録
2. 同じプレイヤーがそのモンスターを倒す
3. ドロップ無効化、ダミーエンティティ生成（AI無効・無敵）
4. `/petname <名前>` で命名 → MyPet作成 + レアリティ/性格抽選
5. 30秒タイムアウトでダミー消去、テイム失敗

**入力バリデーション:**
- 名前: 空文字禁止、最大32文字、カラーコード許可、不正文字フィルタ
- タイムアウト後の`/petname`は「仲間にできる敵が存在しません」を返す

### 5.2 Rarity System

#### LevelledMobs Integration (3-Layer Fallback)

```java
public class LevelledMobsIntegration {
    // Layer 1: Official API (LevelInterface)
    // Layer 2: PDC fallback (configurable key name)
    // Layer 3: Default level 0 + warning log

    public int getMobLevel(LivingEntity entity) {
        // 1. Try official API
        if (levelInterface != null) {
            try { return levelInterface.getLevelOfMob(entity); }
            catch (Exception e) { /* fall through */ }
        }
        // 2. Try PDC with configurable key
        String keyName = config.getString("levelledmobs.pdc-key", "level");
        NamespacedKey key = new NamespacedKey(lmPlugin, keyName);
        Integer level = entity.getPersistentDataContainer()
            .get(key, PersistentDataType.INTEGER);
        if (level != null) return level;
        // 3. Fallback
        getLogger().warning("Could not read LevelledMobs level for " + entity.getType());
        return 0;
    }
}
```

#### Rarity Roll Algorithm

```yaml
rarity:
  tiers:
    COMMON:
      color: "§f"
      stat-multiplier: 1.0
      skill-slots: 0
      particle: null
    UNCOMMON:
      color: "§a"
      stat-multiplier: 1.2
      skill-slots: 0
      particle: null
    RARE:
      color: "§9"
      stat-multiplier: 1.5
      skill-slots: 0
      particle: ENCHANTMENT_TABLE
      particle-interval: 40    # ticks
    EPIC:
      color: "§5"
      stat-multiplier: 2.0
      skill-slots: 1
      particle: SPELL_WITCH
      particle-interval: 30
    LEGENDARY:
      color: "§6"
      stat-multiplier: 3.0
      skill-slots: 2
      particle: TOTEM_OF_UNDYING
      particle-interval: 20
      awakening: true
    MYTHIC:
      color: "§c"
      stat-multiplier: 4.0
      skill-slots: 3
      particle: DRAGON_BREATH
      particle-interval: 10
      awakening: true

  # Mob level -> rarity weight table
  level-ranges:
    "1-10":
      COMMON: 60
      UNCOMMON: 30
      RARE: 10
    "11-25":
      COMMON: 30
      UNCOMMON: 40
      RARE: 20
      EPIC: 10
    "26-50":
      UNCOMMON: 20
      RARE: 40
      EPIC: 30
      LEGENDARY: 10
    "51+":
      RARE: 20
      EPIC: 40
      LEGENDARY: 30
      MYTHIC: 10

  # LevelledMobs未導入時のフォールバック
  no-levelledmobs-fallback:
    COMMON: 50
    UNCOMMON: 30
    RARE: 15
    EPIC: 4
    LEGENDARY: 1

  # Environment bonuses
  # 方式: 抽選後に「アップグレード判定」を1回実施
  # 各ボーナスの合計値(%) でアップグレード成功率を決定（上限50%）
  environment-bonuses:
    full-moon: 10
    thunderstorm: 10
    deep-dark-biome: 15
    end-dimension: 20
    nether-fortress: 10
    max-bonus-cap: 50           # 合計上限
```

**環境ボーナス計算式:**
```
1. level-rangesテーブルからレアリティを重み付きランダムで決定
2. 環境条件ボーナスの合計を算出 (上限 max-bonus-cap %)
3. 合計% の確率で、決定したレアリティを1段階アップグレード
4. アップグレード先がMYTHIC超えの場合はMYTHICに留まる
```

### 5.3 Personality System

```yaml
personalities:
  BRAVE:
    display-name: "勇敢"
    description: "攻撃に優れるが、防御が若干低い"
    modifiers:
      damage: 1.15              # MyPet UpgradeModifier対応
      max-health: 0.95          # MyPet UpgradeModifier対応
    weight: 12                  # 抽選ウェイト
  STURDY:
    display-name: "頑丈"
    description: "HPが高いが足が遅い"
    modifiers:
      max-health: 1.20
      # speed: MyPet直接対応外 → カスタムAttribute操作
    custom-effects:
      speed-multiplier: 0.90    # Attribute.GENERIC_MOVEMENT_SPEED
    weight: 12
  AGILE:
    display-name: "俊敏"
    description: "素早いがHPが低い"
    modifiers:
      max-health: 0.90
    custom-effects:
      speed-multiplier: 1.20
    weight: 12
  LOYAL:
    display-name: "忠実"
    description: "親密度が上がりやすい"
    modifiers: {}
    custom-effects:
      bond-gain-multiplier: 1.30
    weight: 12
  FIERCE:
    display-name: "狂暴"
    description: "攻撃力が高いが被ダメージも増加"
    modifiers:
      damage: 1.25
    custom-effects:
      damage-taken-multiplier: 1.15  # EntityDamageEventで処理
    weight: 10
  CAUTIOUS:
    display-name: "慎重"
    description: "被ダメージが減るが攻撃力が低い"
    modifiers:
      damage: 0.90
    custom-effects:
      damage-taken-multiplier: 0.85
    weight: 12
  LUCKY:
    display-name: "幸運"
    description: "ドロップ品質が向上"
    modifiers: {}
    custom-effects:
      drop-quality-bonus: 1.20    # EntityDeathEventのドロップ操作
    weight: 10
  GENIUS:
    display-name: "天才"
    description: "経験値獲得が多い"
    modifiers: {}
    custom-effects:
      exp-gain-multiplier: 1.25   # MyPetExpEvent処理
    weight: 10
```

**実装分類:**
- `modifiers`: MyPet `UpgradeModifier` 経由で適用（Life, Damage）
- `custom-effects`: カスタムイベントリスナーで処理
  - `speed-multiplier` → `Attribute.GENERIC_MOVEMENT_SPEED` 操作
  - `damage-taken-multiplier` → `EntityDamageEvent` で被ダメ倍率適用
  - `drop-quality-bonus` → `EntityDeathEvent` でドロップ操作
  - `bond-gain-multiplier` → BondManagerの内部倍率
  - `exp-gain-multiplier` → MyPet経験値イベントフック

### 5.4 Bond / Affinity System

```yaml
bond:
  levels:
    1: { min: 0, max: 100, bonus: null }
    2: { min: 100, max: 300, bonus: "speed: 1.05" }
    3: { min: 300, max: 600, bonus: "owner-regen: 1" }
    4: { min: 600, max: 1000, bonus: "stats: 1.10" }
    5: { min: 1000, max: -1, bonus: "awakening-eligible" }
  gain:
    combat-kill: 3
    feeding: 5
    login-summon: 1
    riding-per-minute: 1
  loss:
    daily-decay: 1
    pet-death: 10
    max-offline-decay: 30       # オフライン日数x1だが最大30まで
  debounce:
    combat-kill-cooldown: 5000  # ms, 同一ペットの連続kill加算を防止
    feeding-cooldown: 10000     # ms
```

**Bond冪等性設計:**
```java
public class BondManager {
    // デバウンス: イベント種別ごとにlast-gainタイムスタンプを記録
    // 同一イベントが cooldown 以内に再発火しても無視
    private final Map<UUID, Map<String, Long>> lastGainTimestamps = new ConcurrentHashMap<>();

    public void addBondExp(UUID addonPetId, String source, int amount) {
        long now = System.currentTimeMillis();
        long cooldown = config.getLong("bond.debounce." + source + "-cooldown", 0);
        Map<String, Long> petTimestamps = lastGainTimestamps
            .computeIfAbsent(addonPetId, k -> new ConcurrentHashMap<>());
        Long lastTime = petTimestamps.get(source);
        if (lastTime != null && (now - lastTime) < cooldown) return; // debounce
        petTimestamps.put(source, now);
        // キャッシュ更新 -> dirty mark -> 非同期flush
    }
}
```

**日次減衰計算:**
- ログイン時に `last_login` との差分日数を計算
- `decay = min(差分日数 * daily-decay, max-offline-decay)`
- ログイン時1回のみ適用（冪等）

### 5.5 Base Stats System

```yaml
pet-base-values:
  tier-1:  # 1級
    types: ["PIGLIN_BRUTE", "ILLUSIONER"]
    stats:
      Life: { min: 16, max: 25 }
      Damage: { min: 6, max: 10 }
  tier-2:  # 2級
    types: ["WITCH", "VINDICATOR", "PHANTOM", "GUARDIAN", "EVOKER", "RAVAGER", "VEX"]
    stats:
      Life: { min: 11, max: 20 }
      Damage: { min: 3, max: 8 }
  tier-3:  # 3級 (上記以外のモンスター + POLAR_BEAR, IRON_GOLEM)
    types: ["auto:monsters-except-above", "POLAR_BEAR", "IRON_GOLEM"]
    stats:
      Life: { min: 5, max: 15 }
      Damage: { min: 1, max: 5 }
  tier-4:
    types: ["ZOMBIE_HORSE", "HORSE", "PANDA", "SKELETON_HORSE", "ALLAY", "MULE", "DONKEY", "TURTLE", "LLAMA", "STRIDER"]
    stats:
      Life: { min: 5, max: 15 }
      Damage: 0
  tier-5:  # 上記以外の動物
    types: ["auto:animals-except-above"]
    stats:
      Life: { min: 1, max: 10 }
      Damage: 0
  special-wither:
    types: ["WITHER"]
    stats:
      Life: { min: 26, max: 40 }
      Damage: { min: 13, max: 20 }
  special-warden:
    types: ["WARDEN"]
    stats:
      Life: { min: 20, max: 30 }
      Damage: { min: 8, max: 12 }
  special-wolf-bee:
    types: ["WOLF", "BEE"]
    stats:
      Life: { min: 1, max: 10 }
      Damage: { min: 1, max: 5 }

  reroll-item: GOLD_INGOT          # 基礎ステータス再抽選アイテム
  upgrade-item: GOLD_NUGGET        # 基礎ステータス+1アイテム
  skilltree-reroll-item: DIAMOND   # スキルツリー再抽選アイテム
```

### 5.6 Modifier Pipeline (Effect Application Order)

**適用順序（明文化）:**
```
Final Stat = ((Base + Upgraded) * Rarity Multiplier * Personality Modifier
              + Bond Bonus + Equipment Bonus) + Temporary Buffs
```

```java
public class ModifierPipeline {
    public double calculate(String statName, PetData pet) {
        double base = pet.getBaseStat(statName) + pet.getUpgradedStat(statName);
        double rarityMul = pet.getRarity().getStatMultiplier();
        double personalityMul = pet.getPersonality().getModifier(statName, 1.0);
        double bondBonus = pet.getBondLevel().getStatBonus(statName);
        double equipBonus = pet.getEquipment().getStatBonus(statName);
        return (base * rarityMul * personalityMul) + bondBonus + equipBonus;
    }
    // この結果を UpgradeModifier として MyPet に適用
}
```

## 6. Phase 2: Advanced Features

### 6.1 Evolution System

#### Evolution Safety Procedure

```
1. PRE-CHECK
   - 条件確認（レベル、親密度、アイテム）
   - GUI確認ダイアログ表示 → プレイヤー承認待ち

2. BACKUP
   - 現在のaddon dataをメモリに保持（ロールバック用）
   - mypet_uuid, stats, equipment, skills を全記録

3. REMOVE OLD MYPET
   - MyPetApi.getMyPetManager().deactivateMyPet()
   - MyPetApi.getRepository().removeMyPet()

4. CREATE NEW MYPET
   - 新しいmob typeで InactiveMyPet 作成
   - 旧MyPetのレベル・経験値・スキルツリーを可能な限り移行
   - MyPetApi.getRepository().addMyPet()
   - activateMyPet() + createEntity()

5. UPDATE ADDON DATA
   - pet_data.mypet_uuid を新UUIDに更新
   - pet_data.mob_type を新typeに更新
   - evolution_history にレコード追加
   - ステータスに進化ボーナス加算

6. ROLLBACK (Step 4-5 で例外発生時)
   - 新MyPetが作成済みなら削除
   - バックアップデータから旧MyPetを再作成
   - addon dataを元に戻す
   - プレイヤーにエラー通知
```

#### Evolution Config

```yaml
evolutions:
  ZOMBIE:
    evolves-to: HUSK
    conditions:
      min-level: 30
      min-bond-level: 3
      required-item: GOLDEN_APPLE
      consume-item: true
    stat-bonus:
      Life: 5
      Damage: 2
  HUSK:
    evolves-to: DROWNED
    conditions:
      min-level: 50
      min-bond-level: 4
      required-item: HEART_OF_THE_SEA
      consume-item: true
    stat-bonus:
      Life: 8
      Damage: 3
  SKELETON:
    evolves-to: WITHER_SKELETON
    conditions:
      min-level: 40
      min-bond-level: 4
      required-item: WITHER_SKELETON_SKULL
      consume-item: true
    stat-bonus:
      Life: 10
      Damage: 5
  SPIDER:
    evolves-to: CAVE_SPIDER
    conditions:
      min-level: 25
      min-bond-level: 3
      required-item: FERMENTED_SPIDER_EYE
      consume-item: true
    stat-bonus:
      Life: 3
      Damage: 4
```

### 6.2 Unique Skills

```yaml
pet-skills:
  CREEPER:
    self-destruct:
      display-name: "自爆"
      description: "周囲に爆発ダメージを与えるが、自身もダメージを受ける"
      cooldown: 60
      damage: 15.0
      self-damage-percent: 30
      radius: 5.0
      required-rarity: EPIC
  WOLF:
    howl:
      display-name: "遠吠え"
      description: "周囲の敵にスロー効果を与える"
      cooldown: 45
      effect: SLOW
      effect-duration: 100
      effect-amplifier: 1
      radius: 8.0
      required-rarity: EPIC
  ENDERMAN:
    teleport-sync:
      display-name: "テレポート同行"
      description: "オーナーの位置にテレポートする"
      cooldown: 30
      range: 50.0
      required-rarity: RARE
  BLAZE:
    fire-rain:
      display-name: "炎の雨"
      description: "範囲に炎のダメージを降らせる"
      cooldown: 60
      damage: 8.0
      radius: 6.0
      fire-duration: 60
      required-rarity: EPIC
```

**Cooldown Tracking (In-Memory Only):**
```java
public class CooldownTracker {
    // サーバー再起動でリセット（永続化不要）
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    public boolean isOnCooldown(UUID addonPetId, String skillId) {
        // ...
    }
}
```

## 7. Phase 3: Collection & Equipment

### 7.1 Pet Equipment

```yaml
equipment:
  slots:
    - HELMET
    - CHEST
    - ACCESSORY
  # MyPet既存の装備表示との関係: addon装備はステータスのみ影響
  # 見た目はMyPet標準の装備システムに委譲
  stat-only: true
```

装備のItemStackはBase64シリアライズでDB保存（4.1参照）。

### 7.2 Encyclopedia / Pokedex

```yaml
encyclopedia:
  gui:
    title: "§8§lペット図鑑"
    rows: 6
    sort: ALPHABETICAL    # or BY_RARITY, BY_TAME_COUNT
  completion-rewards:
    25:
      reward-type: TAME_CHANCE_BONUS
      value: 5
      message: "§a図鑑25%達成！テイム成功率+5%"
    50:
      reward-type: TAME_CHANCE_BONUS
      value: 10
      message: "§a図鑑50%達成！テイム成功率+10%"
    75:
      reward-type: RARITY_BONUS
      value: 5
      message: "§a図鑑75%達成！レアリティUP確率+5%"
    100:
      reward-type: TITLE
      value: "§6§l[ペットマスター]"
      message: "§6§l図鑑コンプリート！称号獲得！"
```

## 8. Commands & Permissions

| Command | Description | Permission |
|---------|-------------|------------|
| `/petname <name>` | テイム中のモンスターに名前を付ける | `mypetaddon.tame` |
| `/petstatus` | ペットステータス詳細表示（分解ビュー） | `mypetaddon.status` |
| `/petskill <skill>` | 固有スキル使用 | `mypetaddon.skill` |
| `/petequip` | 装備GUI表示 | `mypetaddon.equip` |
| `/petdex` | 図鑑GUI表示 | `mypetaddon.encyclopedia` |
| `/petevolve` | 進化実行（確認GUI付き） | `mypetaddon.evolve` |
| `/petadmin reload` | Config再読込（検証付き） | `mypetaddon.admin` |
| `/petadmin setrarity <player> <rarity>` | レアリティ強制変更 | `mypetaddon.admin` |
| `/petadmin setbond <player> <amount>` | 親密度強制変更 | `mypetaddon.admin` |
| `/petadmin migrate` | レガシーデータ手動移行 | `mypetaddon.admin` |

**`/petstatus` 出力例:**
```
§6§l=== ペットステータス ===
§7名前: §fタロウ §9[レア]
§7種族: §fゾンビ  §7性格: §f勇敢
§7親密度: §eLv3 (450/600)
§7--- ステータス内訳 ---
§7Life: §f基礎12 §ax1.5(レア) §ax1.0(性格) §a+0(絆) §a+3(装備) §6= 21
§7Damage: §f基礎5 §ax1.5(レア) §ax1.15(勇敢) §a+0(絆) §a+0(装備) §6= 8.6
```

## 9. Config Validation

```java
public class ConfigValidator {
    // reload時にバリデーション実行
    // 失敗時は旧configを維持（アトミック反映）

    public ValidationResult validate(FileConfiguration newConfig) {
        List<String> errors = new ArrayList<>();

        // レアリティのstat-multiplierが正の数であること
        // 性格のweightが0以上であること
        // 進化チェーンに循環がないこと
        // level-rangesの重みの合計が100であること
        // bond levelのminが昇順であること
        // 参照されるMob typeがEntityTypeに存在すること

        return new ValidationResult(errors.isEmpty(), errors);
    }

    // /petadmin reload のフロー:
    // 1. 新configをtempにロード
    // 2. validate() 実行
    // 3. 成功 -> activeConfigを差し替え + キャッシュ再計算
    // 4. 失敗 -> エラーメッセージ表示、旧config維持
}
```

## 10. Sound Policy

レガシーの文字列ベースサウンドキー（`"random.levelup"`）は廃止。
全サウンドは `Sound` enum で統一。config側でもenum名を使用。

```yaml
sounds:
  tame-success: ENTITY_PLAYER_LEVELUP
  tame-monster-ready: ENTITY_PLAYER_LEVELUP
  evolution-success: ENTITY_FIREWORK_ROCKET_LARGE_BLAST
  skill-use: ENTITY_ENDER_DRAGON_FLAP
  rarity-upgrade: UI_TOAST_CHALLENGE_COMPLETE
```

## 11. Implementation Phases

### Phase 1 (Core) — 目標: 基本プレイループ完成
1. プロジェクト基盤（Gradle, Paper plugin, DB, Config）
2. テイムシステム（Legacy移行）
3. レアリティシステム（LevelledMobs連携）
4. 性格システム
5. 親密度システム
6. 基礎ステータス + ModifierPipeline
7. `/petstatus` コマンド

### Phase 2 (Advanced) — 目標: 長期プレイ動線
8. 進化システム（安全な再作成手順）
9. 固有スキルシステム
10. `/petskill`, `/petevolve` コマンド

### Phase 3 (Collection) — 目標: コレクション要素
11. 装備システム
12. 図鑑システム
13. `/petequip`, `/petdex` コマンド
14. 図鑑達成報酬
