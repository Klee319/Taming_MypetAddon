# 設定ガイド（管理者向け）

MyPetAddon の設定は `plugins/MyPetAddon/` 内の複数の YAML ファイルで管理されています。このページでは主要な設定項目の解説とカスタマイズ例を紹介します。

---

## 設定ファイルの構成

| ファイル名 | セクション | 内容 |
|-----------|-----------|------|
| `config.yml` | `general` | デバッグモード、ロケール設定 |
| `config.yml` | `taming` | テイミング関連（アイテム、成功率、除外リスト） |
| `config.yml` | `level-bonuses` | レベルボーナス設定（EXP倍率、ボーナス上限） |
| `config.yml` | `reroll-items` | リロールアイテム設定 |
| `config.yml` | `levelledmobs` | LevelledMobs連携設定 |
| `rarity.yml` | `rarity` | レアリティティア定義、レベル帯別確率、環境ボーナス |
| `personality.yml` | `personalities` | 性格の定義（ステータス補正、特殊効果、重み） |
| `bond.yml` | `bond` | 絆レベル定義、EXP獲得量、減少量、クールダウン |
| `stats.yml` | `pet-base-values` | モブ種別ごとの基礎ステータス範囲（友好/敵対カテゴリ別ティア） |
| `evolution.yml` | `evolutions` | 進化設定（条件、ステータスボーナス、分岐） |
| `skilltree.yml` | `skilltree-assignment` | スキルツリー自動割り当て設定 |
| `equipment.yml` | `equipment` | 装備設定（スロット解放条件、装備効果） |
| `messages.yml` | `encyclopedia` | 図鑑設定（GUI、コンプリート報酬） |
| `messages.yml` | `sounds` | サウンド設定 |
| `messages.yml` | `messages` | メッセージ設定（全テキストのカスタマイズ） |

---

## 主要項目の解説

### テイミング成功率の変更

`taming.animals.items` および `taming.monsters.items` でアイテムごとの成功率を設定できます。

`config.yml` を編集:

```yaml
taming:
  animals:
    items:
      - item: "GOLDEN_APPLE"
        success-rate: 0.10    # 5% → 10% に変更
        consume: true
```

- `success-rate`: 0.0〜1.0 の範囲（0.05 = 5%、1.0 = 100%）
- `consume`: true にするとテイミング試行時にアイテムが消費される
- カスタムアイテムを追加する場合は `"base64:<エンコード済みItemStack>"` 形式も使用可能

### テイミング試行回数の変更

`config.yml` を編集:

```yaml
taming:
  max-attempts-per-mob: 10    # 5回 → 10回に変更（0 = 無制限）
```

### レアリティ確率の調整

`rarity.level-ranges` でレベル帯ごとのレアリティ重みを変更できます。

`rarity.yml` を編集:

```yaml
rarity:
  level-ranges:
    "1-10":
      COMMON: 40      # 60 → 40 に下げてレア度を上げる
      UNCOMMON: 30
      RARE: 15
      EPIC: 10
      LEGENDARY: 4
      MYTHIC: 1
```

### 環境ボーナスの調整

`rarity.yml` を編集:

```yaml
rarity:
  environment-bonuses:
    full-moon: 10          # 5 → 10
    thunderstorm: 15       # 10 → 15
    max-bonus-cap: 50      # 30 → 50
```

### 絆EXP獲得量の調整

`bond.yml` を編集:

```yaml
bond:
  gain:
    combat-kill: 10        # 5 → 10（戦闘をもっと報われるように）
    feeding: 50            # 100 → 50（エサやりを控えめに）
```

### レベルボーナスの調整

`config.yml` を編集:

```yaml
level-bonuses:
  exp-multiplier: 1.0      # 0.7 → 1.0（通常のEXP獲得速度に）
  start-level: 10          # 20 → 10（早期からボーナス開始）
  per-level-bonus: 0.01    # レベルごとのボーナスを増加
  max-bonus: 1.0           # 上限を+100%に
```

### 進化条件の変更

`evolution.yml` を編集:

```yaml
evolutions:
  ZOMBIE:
    branches:
      husk:
        conditions:
          min-level: 15          # 20 → 15 に緩和
          min-bond-level: 2      # 3 → 2 に緩和
          required-item-amount: 3 # 1 → 3 に増加
```

### 装備効果の変更

`equipment.yml` を編集:

```yaml
equipment:
  default-effects:
    HEAD:
      DIAMOND_HELMET:
        Life: 15.0             # 10.0 → 15.0 に強化
        Damage: 1.0            # 攻撃力ボーナスを追加
```

### 図鑑コンプリート報酬の変更

`messages.yml` を編集:

```yaml
encyclopedia:
  completion-rewards:
    25:
      commands:
        - "give %player% diamond 10"    # 報酬を増やす
        - "give %player% experience_bottle 32"  # 新しい報酬を追加
      message: "§a図鑑25%%達成！豪華報酬を獲得！"
```

### メッセージのカスタマイズ

`messages` セクションで全てのプレイヤー向けメッセージをカスタマイズできます。

`messages.yml` を編集:

```yaml
messages:
  prefix: "§8[§bペット§eアドオン§8]§r "    # プレフィックスの変更
  tame-success: "§a%pet_name% を仲間にした！"  # テイム成功メッセージの変更
```

---

## カスタムテイミングアイテムの追加

BASE64エンコードされた ItemStack を使用することで、カスタムアイテムをテイミングアイテムとして追加できます。

`config.yml` を編集:

```yaml
taming:
  animals:
    items:
      - item: "GOLDEN_APPLE"
        success-rate: 0.05
        consume: true
      - item: "base64:<ここにBASE64エンコード文字列>"
        success-rate: 0.50
        consume: true
```

---

## リロード方法

設定を変更した後は、以下のコマンドでリロードできます。

```
/mypetaddon reload
```

- `/mypetaddon reload` は全ての設定ファイル（config.yml, rarity.yml, personality.yml, bond.yml, stats.yml, evolution.yml, skilltree.yml, equipment.yml, messages.yml）を一括でリロードします
- リロード成功時: 設定が反映され、全ペットのステータスが再計算されます
- リロード失敗時: エラーメッセージが表示され、前の設定が維持されます

> サーバーの再起動なしに設定変更を反映できます。ただし、一部の設定（プラグイン依存関係など）はサーバー再起動が必要な場合があります。

---

## MythicMobs 除外設定

MythicMobs のカスタムモブをテイミング対象から除外できます。

`config.yml` を編集:

```yaml
taming:
  mythicmobs-exclusion:
    enabled: true
    excluded-ids: []          # 空の場合は全MythicMobを除外
    # 特定のMythicMobのみ除外する場合:
    # excluded-ids:
    #   - "CustomBoss1"
    #   - "CustomBoss2"
```

---

## ステータスティアの構成

`stats.yml` のティアは **友好モブ** (friendly-tier-1〜5) と **敵対モブ** (hostile-tier-1〜4) の2カテゴリに分かれています。友好モブは5段階、敵対モブは4段階のティアがあり、それぞれのティアでステータスの範囲が異なります。

さらに `special` セクションで、ウィザー・ウォーデン・帯電クリーパー・イリュージョナーなどの特殊モブが個別に定義されています。

### 帯電クリーパー (CHARGED_CREEPER) について

帯電クリーパーはアドオン内部で通常のクリーパー (`CREEPER`) とは独立した別のモブタイプ (`CHARGED_CREEPER`) として扱われます。これにより、通常クリーパーとは異なるステータス範囲・スキルツリー・進化ルートを持つことができます。stats.yml では `special.charged-creeper` セクションで定義されています。

---

## テイム後のペットサイズ変更

大型モブのテイム後のサイズを調整できます。

`config.yml` を編集:

```yaml
taming:
  scale-overrides:
    WITHER: 0.7              # 70%サイズ
    ELDER_GUARDIAN: 0.7
    GHAST: 0.5               # 50%サイズ
```
