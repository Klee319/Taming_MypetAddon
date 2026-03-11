# MyPet Addon Plugin - Design Draft

## Overview
MyPetプラグインと連携し、ペットシステムを大幅に拡張するPaper APIプラグイン。
LevelledMobs連携によるレアリティシステム、性格、親密度、進化、固有スキル、装備、図鑑を追加する。

## Technical Stack
- Paper API 1.21.x (Java 21+)
- MyPet API (hard dependency)
- LevelledMobs API (soft dependency - PDC経由でMobレベル取得)
- MythicMobs API (soft dependency - テイム除外判定)
- Data: SQLite (pet data) + YAML config (settings/balancing)
- GUI: Adventure Component + Custom Inventory GUI

## Phase 1: Core Systems

### 1. Taming System (Legacy Migration)
- Animals: Enchanted Golden Apple → instant tame
- Monsters: Diamond right-click → kill → `/petname <name>` to register
- MythicMobs exclusion maintained
- On tame: roll rarity, personality, base stats

### 2. Rarity System (LevelledMobs Integration)
Get mob level via PDC:
```java
public int getMobLevel(LivingEntity entity) {
    Plugin lm = Bukkit.getPluginManager().getPlugin("LevelledMobs");
    if (lm == null) return 0;
    NamespacedKey key = new NamespacedKey(lm, "level");
    return Objects.requireNonNullElse(
        entity.getPersistentDataContainer().get(key, PersistentDataType.INTEGER), 0);
}
```

Rarity tiers (6 tiers):
| Rarity | Color | Stat Multiplier | Skill Slots | Special |
|--------|-------|-----------------|-------------|---------|
| Common | §f白 | x1.0 | 0 | - |
| Uncommon | §a緑 | x1.2 | 0 | - |
| Rare | §9青 | x1.5 | 0 | Particle small |
| Epic | §5紫 | x2.0 | 1 | Particle medium |
| Legendary | §6金 | x3.0 | 2 | Particle large, awakening |
| Mythic | §c赤 | x4.0 | 3 | Particle special, awakening |

Rarity roll based on mob level:
```yaml
rarity-chances:
  level-ranges:
    1-10:
      COMMON: 60
      UNCOMMON: 30
      RARE: 10
    11-25:
      COMMON: 30
      UNCOMMON: 40
      RARE: 20
      EPIC: 10
    26-50:
      UNCOMMON: 20
      RARE: 40
      EPIC: 30
      LEGENDARY: 10
    51+:
      RARE: 20
      EPIC: 40
      LEGENDARY: 30
      MYTHIC: 10
  environment-bonuses:
    full-moon: 10      # +10% chance to upgrade rarity
    thunderstorm: 10
    deep-dark-biome: 15
    end-dimension: 20
```

Fallback when LevelledMobs not installed: all level ranges use equal probability.

### 3. Personality System (8 types)
```yaml
personalities:
  BRAVE:
    display-name: "勇敢"
    modifiers:
      damage: 1.15
      defense: 0.95
  STURDY:
    display-name: "頑丈"
    modifiers:
      max-health: 1.20
      speed: 0.90
  AGILE:
    display-name: "俊敏"
    modifiers:
      speed: 1.20
      max-health: 0.90
  LOYAL:
    display-name: "忠実"
    modifiers:
      bond-gain: 1.30
  FIERCE:
    display-name: "狂暴"
    modifiers:
      damage: 1.25
      damage-taken: 1.15
  CAUTIOUS:
    display-name: "慎重"
    modifiers:
      defense: 1.15
      damage: 0.90
  LUCKY:
    display-name: "幸運"
    modifiers:
      drop-quality: 1.20
  GENIUS:
    display-name: "天才"
    modifiers:
      exp-gain: 1.25
```
All values configurable in config.yml.

### 4. Bond/Affinity System
```yaml
bond:
  levels:
    1: { min: 0, max: 100 }
    2: { min: 100, max: 300, bonus: "speed: 1.05" }
    3: { min: 300, max: 600, bonus: "owner-regen: 1" }
    4: { min: 600, max: 1000, bonus: "stats: 1.10" }
    5: { min: 1000, max: -1, bonus: "awakening-eligible" }
  gain:
    combat-kill: 3
    feeding: 5
    login-summon: 1
    riding: 1  # per minute
  loss:
    daily-decay: 1
    pet-death: 10
```

### 5. Base Stats System (Legacy Migration)
Pet base values by mob type tier system maintained from legacy.
All values configurable in config.yml under `pet-base-values`.
Stat reroll/upgrade items configurable.

## Phase 2: Advanced Features

### 6. Pet Evolution
```yaml
evolutions:
  ZOMBIE:
    evolves-to: HUSK
    conditions:
      min-level: 30
      min-bond: 3
      required-item: "GOLDEN_APPLE"
  HUSK:
    evolves-to: DROWNED
    conditions:
      min-level: 50
      min-bond: 4
      required-item: "HEART_OF_THE_SEA"
  SKELETON:
    evolves-to: WITHER_SKELETON
    conditions:
      min-level: 40
      min-bond: 4
      required-item: "WITHER_SKELETON_SKULL"
  SPIDER:
    evolves-to: CAVE_SPIDER
    conditions:
      min-level: 25
      min-bond: 3
      required-item: "FERMENTED_SPIDER_EYE"
```
Evolution chains fully configurable. Stats carry over with bonus.

### 7. Unique Skills (Mob-specific)
```yaml
pet-skills:
  CREEPER:
    self-destruct:
      display-name: "自爆"
      cooldown: 60
      damage: 15.0
      self-damage-percent: 30
      radius: 5.0
  WOLF:
    howl:
      display-name: "遠吠え"
      cooldown: 45
      effect: SLOW
      effect-duration: 100
      radius: 8.0
  ENDERMAN:
    teleport-sync:
      display-name: "テレポート同行"
      cooldown: 30
      range: 50.0
  BLAZE:
    fire-rain:
      display-name: "炎の雨"
      cooldown: 60
      damage: 8.0
      radius: 6.0
      duration: 60
```
Skills unlocked based on rarity (Epic+ gets slots).
All skills, cooldowns, damage values configurable.

## Phase 3: Collection & Equipment

### 8. Pet Equipment
```yaml
equipment-slots:
  - HELMET
  - CHEST
  - ACCESSORY
equipment-recipes:
  pet-iron-helmet:
    type: SHAPED
    result:
      material: IRON_HELMET
      custom-model-data: 10001
      stats:
        defense: 3
    pattern:
      - "III"
      - "I I"
    ingredients:
      I: IRON_INGOT
```
Custom crafting recipes for pet-specific equipment.

### 9. Pet Encyclopedia / Pokedex
- GUI-based collection viewer
- Track: species tamed, highest rarity per species, total tamed count
- Completion rewards configurable:
```yaml
encyclopedia:
  completion-rewards:
    25-percent:
      reward: "tame-chance-bonus: 5"
    50-percent:
      reward: "tame-chance-bonus: 10"
    75-percent:
      reward: "rarity-bonus: 5"
    100-percent:
      reward: "title: §6§l[ペットマスター]"
```

## Data Architecture

### SQLite Schema
```sql
CREATE TABLE pet_data (
    pet_uuid TEXT PRIMARY KEY,
    owner_uuid TEXT NOT NULL,
    mob_type TEXT NOT NULL,
    rarity TEXT NOT NULL DEFAULT 'COMMON',
    personality TEXT NOT NULL,
    bond_level INTEGER NOT NULL DEFAULT 0,
    bond_exp INTEGER NOT NULL DEFAULT 0,
    base_stats TEXT NOT NULL,  -- JSON: {"Life": 10, "Damage": 5}
    upgraded_stats TEXT DEFAULT '{}',  -- JSON
    equipment TEXT DEFAULT '{}',  -- JSON: {"HELMET": itemData, ...}
    skills TEXT DEFAULT '[]',  -- JSON: ["self-destruct", ...]
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE encyclopedia (
    owner_uuid TEXT NOT NULL,
    mob_type TEXT NOT NULL,
    highest_rarity TEXT NOT NULL DEFAULT 'COMMON',
    tame_count INTEGER NOT NULL DEFAULT 0,
    first_tamed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_uuid, mob_type)
);
```

## Commands
| Command | Description |
|---------|-------------|
| `/petname <name>` | Name a pending tamed monster |
| `/petstatus` | Show pet stats, rarity, personality, bond |
| `/petskill <skill>` | Use a pet skill |
| `/petequip` | Open pet equipment GUI |
| `/petdex` | Open pet encyclopedia GUI |
| `/petevolve` | Evolve pet (if conditions met) |
| `/petadmin reload` | Reload config |
| `/petadmin setrarity <player> <rarity>` | Admin: set rarity |
| `/petadmin setbond <player> <amount>` | Admin: set bond |

## Config-Driven Design Principle
ALL balancing values are externalized to YAML config:
- Rarity chances, multipliers, level ranges
- Personality types and modifier values
- Bond level thresholds and gain/loss rates
- Evolution chains and conditions
- Skill parameters (cooldown, damage, radius)
- Equipment stats and recipes
- Encyclopedia rewards
- Taming items and requirements
- Base stat ranges per mob type
- Environment bonuses
- Messages and display strings (i18n ready)
