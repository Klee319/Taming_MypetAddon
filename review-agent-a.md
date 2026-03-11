## Agent A Review (Claude Code)

### Critical Issues

- [Severity: Critical] [Data Architecture - SQLite Schema] — `base_stats`, `upgraded_stats`, `equipment`, `skills` are all JSON-in-TEXT columns. This makes querying, indexing, and data integrity enforcement impossible at the DB level. For example, you cannot query "all pets with Damage > 10" without parsing every row's JSON. More importantly, JSON corruption in a single row silently breaks that pet's data with no DB-level constraint to catch it. Stats and equipment should be normalized into separate tables (`pet_stats`, `pet_equipment`, `pet_skills`) with proper foreign keys and type constraints.

- [Severity: Critical] [Data Architecture - pet_uuid as PRIMARY KEY] — The design uses `pet_uuid TEXT` as the primary key for `pet_data`, but MyPet's UUID changes when a pet is removed and re-added (e.g., evolution changes mob type, which may internally recreate the pet). The legacy code stores data keyed by `myPet.getUUID()` in config, and the evolution system proposes changing mob types. If evolution triggers MyPet pet recreation (which it likely does since MyPet ties UUID to mob type), all historical data for that pet is orphaned. The schema needs either an immutable addon-generated UUID or an evolution history chain linking old and new MyPet UUIDs.

- [Severity: Critical] [Evolution System - MyPet API Compatibility] — MyPet does not natively support changing a pet's mob type. `MyPetType` is set at creation via `InactiveMyPet.setPetType()` and is immutable after activation. Evolution (e.g., ZOMBIE -> HUSK) would require: (1) storing all addon data, (2) removing the old MyPet, (3) creating a new MyPet with the target type, (4) migrating all MyPet internal data (level, experience, skilltree progress). This is an extremely fragile operation that the design does not address. If any step fails mid-evolution, the player loses their pet. The design needs a detailed evolution migration procedure with rollback capability.

- [Severity: Critical] [Thread Safety - Bond System] — Bond gain events (combat-kill, feeding, riding per minute) will fire from the main Bukkit thread, but SQLite writes are blocking I/O. If bond updates are synchronous on the main thread, this will cause server tick lag with many active pets. If they're async, there are race conditions when multiple events fire for the same pet simultaneously (e.g., combat kill + riding tick in the same moment). The design needs to specify: async DB access with a write queue, or in-memory cache with periodic flush, plus proper synchronization strategy.

### Important Issues

- [Severity: Important] [Rarity System - LevelledMobs PDC Key] — The design hardcodes `new NamespacedKey(lm, "level")` as the PDC key for LevelledMobs. This key is an internal implementation detail of LevelledMobs and could change between versions without notice. LevelledMobs provides an API (`LevelInterface` / `LevelledMobsAPI`) specifically for reading mob levels. The PDC approach should be a fallback, not the primary method. The key name should also be configurable in config.yml.

- [Severity: Important] [Rarity System - Environment Bonuses] — The environment bonuses (full-moon, thunderstorm, etc.) say "+10% chance to upgrade rarity" but the implementation semantics are undefined. Does "+10%" mean: (a) add 10 to the percentage of the next-higher rarity, (b) 10% chance to bump the rolled rarity up one tier, or (c) something else? Multiple bonuses stacking (e.g., thunderstorm + end-dimension = +30%) could push probabilities over 100% or produce nonsensical distributions. The design needs explicit formulas.

- [Severity: Important] [Bond System - Daily Decay] — `daily-decay: 1` is specified but there's no mechanism described for when/how decay is applied. Is it on player login? A scheduled task? What happens if a player is offline for 30 days -- do they lose 30 bond? This could punish casual players heavily. The design should specify: max decay cap, decay calculation formula, and the trigger mechanism.

- [Severity: Important] [Personality System - Stat Modifiers] — `damage-taken: 1.15` (FIERCE) and `drop-quality: 1.20` (LUCKY) reference stats that don't exist in MyPet's skill system. MyPet manages `Life`, `Damage`, and a few other skills via `UpgradeModifier`, but "damage taken" requires intercepting damage events and "drop quality" requires hooking into loot generation. These are not simple multipliers -- they need custom event listeners. The design should clarify how these non-standard modifiers are implemented.

- [Severity: Important] [Equipment System - ItemStack Serialization] — Storing equipment as JSON in a TEXT column loses critical Minecraft item data. `ItemStack` contains NBT data, enchantments, custom model data, lore, and other metadata that doesn't serialize cleanly to simple JSON. Bukkit provides `ItemStack.serialize()` / `ConfigurationSerialization` or Base64 encoding via `BukkitObjectOutputStream`. The design should specify the serialization method.

- [Severity: Important] [Missing: Concurrent Pet Handling] — MyPet allows players to have multiple stored pets (only one active). The design doesn't address: What happens to addon data when switching active pets? Are bond decay and riding timers properly paused/resumed? Can a player evolve an inactive pet?

- [Severity: Important] [Taming System - Legacy Race Condition Preserved] — The legacy code stores taming state in `enemyskey[player.uniqueId]`, meaning a player can only tame one monster at a time. If a player right-clicks monster A with a diamond, then right-clicks monster B, monster A's entry is silently overwritten. The design should either preserve this as intentional single-target behavior (and document it) or fix it.

### Suggestions

- [Data Architecture] — Consider using HikariCP connection pooling with async database operations. Paper's scheduler (`Bukkit.getScheduler().runTaskAsynchronously()`) can handle DB writes, with an in-memory cache (ConcurrentHashMap) for reads. Flush dirty entries periodically and on plugin disable.

- [Rarity System] — Add a `no-levelled-mobs-fallback` config section that defines rarity chances when LevelledMobs is not installed, rather than "equal probability" which would give Common/Uncommon/Rare/Epic/Legendary/Mythic each ~16.7% chance -- far too generous for the higher tiers.

- [Config-Driven Design] — The ANIMALS and MONSTERS lists from the legacy code are hardcoded. These should be configurable or auto-detected from MyPet's supported types. New mob types added in future Minecraft versions would require a code change otherwise.

- [Pet Skills - Cooldown Tracking] — The design specifies cooldowns per skill but doesn't describe where cooldown state is stored. Cooldowns should be in-memory only (not persisted to DB) and reset on server restart. Consider using a `Map<UUID, Map<String, Long>>` for last-use timestamps.

- [Encyclopedia - Data Consistency] — The `encyclopedia` table tracks `highest_rarity` but doesn't track the specific pet that achieved it. If a player releases their only Mythic zombie, the encyclopedia still shows Mythic. Consider whether this is intentional (collection record) or if it should reflect current ownership.

- [Commands] — Missing `/petstatus` detail: Should it show base stats, personality modifiers, rarity multipliers, and equipment bonuses as separate lines? Players need transparency into how their pet's final stats are calculated. Consider a breakdown view.

- [Evolution System] — Add a confirmation step (GUI or chat confirmation) before evolution. Since evolution may change the pet's appearance and mob type, players should see a preview of what they're getting.

- [Equipment System] — The design specifies 3 equipment slots but doesn't describe how equipment interacts with MyPet's existing equipment system (MyPet already supports equipment display on some mob types). Clarify whether these are addon-only stat slots or if they visually render on the pet entity.

- [Scalability] — Add an index on `pet_data.owner_uuid` since most queries will filter by owner. Also consider `CREATE INDEX idx_pet_owner ON pet_data(owner_uuid);` and `CREATE INDEX idx_encyclopedia_owner ON encyclopedia(owner_uuid);`.

- [Legacy Migration] — The design doesn't describe how existing pets (created via the legacy script with data in YAML config) will be migrated to the new SQLite schema. A one-time migration command or automatic migration on first load is needed.

### Positive Observations

- The config-driven design principle is excellent. Externalizing all balancing values to YAML is the right approach for a server plugin where admins need to tune gameplay without code changes.

- The phased implementation plan (Core -> Advanced -> Collection) is well-structured and allows incremental delivery with each phase being independently useful.

- The personality system is a creative addition that adds meaningful variety between pets of the same species and rarity, increasing replayability.

- The LevelledMobs integration via PDC is a low-coupling approach that avoids hard API dependencies, which is good for a soft dependency.

- The legacy code analysis shows the team understands MyPet's internal API well (UpgradeModifier, InactiveMyPet, RepositoryCallback pattern), which gives confidence in implementation feasibility.

- The MythicMobs exclusion is properly carried forward from legacy, preventing conflicts with another major mob plugin.

### Summary

The design is ambitious and feature-rich with strong config-driven principles, but has critical gaps in three areas: (1) the evolution system lacks a concrete MyPet API migration strategy and will likely break pets if implemented naively, (2) the data architecture uses JSON-in-TEXT columns that sacrifice queryability and integrity for convenience, and (3) thread safety for frequent DB operations (bond updates, stat reads) is unaddressed. These must be resolved before implementation begins. The remaining issues are important but solvable during development.
