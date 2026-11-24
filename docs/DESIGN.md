# Death Census — Design (v1.0.0)

Status: approved by pen 2026-11-24 (design outline reported in the implementation
chat and accepted with "proceed with that"). This document is the specification
for the 1.0.0 implementation. Where this document and the README disagree, this
document wins for implementation details; the README wins for positioning.

## 1. Positioning (do not dilute)

- Server-side only NeoForge mod. Vanilla clients can join; nothing is required
  on the client. No custom GUI screens in 1.0.0 — everything is chat output.
- The differentiator is the **server-wide permanent census plus analytics**:
  every death of every player, death-cause statistics, leaderboards, Discord
  sharing and HTML/JSON/CSV exports. Existing mods are personal/client-side
  (DeathLog, Fabric/Quilt, ~720k DL) or minimal (Server Death Logger,
  NeoForge, last-5-deaths only). Do not drift into item-recovery features.
- Targets: Minecraft **1.21.1 / 26.1.2 / 26.2 / 26.3** (NeoForge), four JARs,
  `common + versions/` layout like Item Milestone Log. Single-JAR unification
  is explicitly out of scope for 1.0.0 (decide after IML 1.3.0's experiment).

## 2. Architecture

- `common/` holds pure-Java logic with **no Minecraft imports** (Java 21
  compatible, unit-tested): the death record model, cause classification from
  plain strings, statistics/rankings, pagination, text escaping, and the
  HTML/JSON/CSV renderers, Discord payload builders and backup file logic.
- `versions/<mc>/src/main/java` holds the Minecraft-facing code per version:
  mod entry point, config, SavedData storage, death event listener, commands,
  Discord HTTP sender, export/backup file glue, and server-language helper.
  Version-specific API differences are contained in these files:
  - 1.21.1: `SavedData.Factory` + NBT, `ModConfig.Type.COMMON`,
    `ResourceLocation`, `source.hasPermission(2)`, `player.server`,
    `overworld().getDayTime()`.
  - 26.x: `SavedDataType` + Codec, `Identifier`, `Commands.hasPermission(
    Commands.LEVEL_GAMEMASTERS)`, `player.level().getServer()`,
    `getOverworldClockTime()`, config type via the version's compat holder
    (26.3 uses a different config type than 26.1/26.2 — follow IML's
    `McCompat.config().type()` pattern).
- Storage, commands and tracking must never run on the client side. The mod
  entry point registers everything on the NeoForge game event bus and the mod
  declares `displayTest="IGNORE_ALL_VERSION"` so vanilla clients are accepted.

## 3. Data model

### 3.1 Death record (`DeathEntry`, common, pure Java)

| field | type | meaning |
|---|---|---|
| playerUuid | String (UUID) | who died |
| playerName | String | name at death time (names can change) |
| epochMillis | long | real-world time of death (server clock) |
| gameDay | long | in-game day index, `floor(overworld day time / 24000)` |
| playTicks | int | vanilla `Stats.PLAY_TIME` at death (cumulative) |
| survivalTicks | int | playTicks minus previous death's playTicks (or the player's recorded baseline for the first death) |
| dimension | String | dimension ID, e.g. `minecraft:the_nether` |
| x, y, z | double | death position (block-aligned values are fine) |
| causeCategory | String | classifier output, see §4 |
| causeId | String | raw damage-type ID, e.g. `minecraft:fall` |
| deathMessage | String | the vanilla death message text at death time |
| killerType | String | entity type ID of the direct killer, or "" |
| killerName | String | killer display name (player name / custom name), or "" |
| gameMode | String | `survival` / `adventure` / `creative` / `spectator` |
| experienceLevel | int | XP level at death |

### 3.2 Per-player state (`PlayerCensus`, version storage)

- `lastKnownName` (updated on login and on death)
- `optOutPublic` boolean (privacy, §8; default false)
- `baselinePlayTicks` int — PLAY_TIME when the player was first seen in this
  world (set on login when no state exists); used for the first survival time
- `lastDeathPlayTicks` int, -1 = no death recorded yet
- `deaths` — ordered list of `DeathEntry`, oldest first

### 3.3 Storage

- Per-world `SavedData` named for this mod (`deathcensus_data` on 1.21.1;
  a `SavedDataType` with ID `deathcensus:deaths` on 26.x), stored on the
  overworld data storage so it is per world / per server and survives restarts.
- Plus one server-level value: `lastDigestEpochMillis` (Discord digest, §7).
- **Limits and generations:** config `maxRecordsPerPlayer` (default 10000,
  range 100–100000). When a player's list exceeds the cap, the oldest records
  are dropped (and the drop is logged once per overflow batch). There is no
  other automatic deletion. Manual data removal is deliberately **not**
  offered in 1.0.0 except the admin backup/restore path (§6.3) — no bulk
  reset commands.
- Malformed stored entries are skipped with a warning, never fail the whole
  load (same policy as IML's SavedData loaders).
- **Formats are not interchangeable across versions:** 1.21.1 stores NBT
  written by hand, 26.x stores Codec-serialized data. Moving a world between
  1.21.1 and 26.x does not carry the census over; run `/dc backup` before the
  move and `/dc restore <file>` after it (the JSON backup is version-neutral).

## 4. Cause classification

Input (gathered in version code from `DamageSource`): damage-type ID,
direct-entity type ID, attacker type ID, whether the attacker is a player.

Classifier (common, pure): map the damage-type ID's path to a category:

`fall, drown, fire (in_fire/on_fire), lava, lightning, freeze, starve,
suffocate (in_wall), cactus (also sweet berry bush), explosion, magic
(includes dragon breath / wither / indirect magic), projectile (arrow etc.
when the damage type says so), mob (generic mob attack), player (attacker is
a player and no more specific category matched), void, generic, other`.

Rules: an explicit damage-type match wins over the attacker-based fallback;
player-kill is used only when the attacker is a player and the type is a
generic attack type; anything unknown becomes `other` and keeps its raw
`causeId` + death message so it stays identifiable. The death message is
always stored verbatim (plain text of the vanilla localized message).

Recording hook: server-side `LivingDeathEvent` for `ServerPlayer`
(verify the exact event class per version — in 26.x it may be a player
death event; the version code isolates this). Skip fake players. Record in
all game modes (creative/spectator deaths are rare but real deaths count;
this is a census, not a progression tracker — a config switch
`recordCreativeDeaths` default true allows turning creative off).

Also on death: update `lastDeathPlayTicks`, `lastKnownName`, mark dirty,
fire the Discord notification (§7) and, if enabled, nothing else — no chat
broadcast by default (the vanilla death message already broadcasts).

## 5. Playtime / day helpers

- Playtime = `player.getStats().getValue(Stats.CUSTOM, Stats.PLAY_TIME)`
  (identical across versions per IML's Playtime).
- Day = floor division of the overworld day time by 24000 (API name differs
  per version, see §2).
- Formatting (common): ticks → `27h 15m 30s`; epoch → `yyyy-MM-dd HH:mm`
  in the configured display timezone (config `displayTimeZone`, empty =
  server default; reuse IML's validation approach).

## 6. Commands

Root: `/deathcensus`, alias `/dc` (two root literals registering the same
tree). All output is chat text built with the server-language helper (bundled
en/ja fallbacks so vanilla clients see real text, like IML's ServerLang).
Page size 10 everywhere.

### 6.1 Everyone (no permission)

- `/dc` / `/dc help` — help listing
- `/dc history [page]` — own death history, newest first:
  `#n  Day 12 · 2026-11-24 21:03 · minecraft:overworld (12, 64, -30) · <death message> · survived 3h 12m`
- `/dc history <player> [page]` — another player's history, only if that
  player has not opted out (or the viewer is an operator); unknown/offline
  names resolve from stored `lastKnownName`s
- `/dc stats` — own summary: total deaths, top causes, longest/shortest
  survival, first/latest death
- `/dc stats server` — server-wide: total deaths, deaths per cause
  (counts + %), most deaths per player (public players only), rarest cause
- `/dc leaderboard deaths|survival|fastest|rarest [page]` —
  most deaths / longest single survival / fastest death (shortest survival
  with survivalTicks > 0) / rarest cause holders (players whose deaths
  include the globally rarest cause, ranked by that count). Opted-out
  players are excluded.
- `/dc optout` / `/dc optin` — toggle own public visibility (§8), with a
  confirmation message showing the new state

### 6.2 Operators (permission level 2 / GAMEMASTERS)

- `/dc lookup <player> [page]` — like history but ignores opt-out
- `/dc export html|json|csv [player]` — write an export file (§7.2) under
  `<world>/deathcensus/exports/` (or for one player) and report the path
- `/dc backup` — write a timestamped backup copy of the current data file
  to `<world>/deathcensus/backups/` (generations kept: config,
  default 5) and report the path
- `/dc restore <file>` — replace the current data with a backup file from
  `<world>/deathcensus/backups/` (a safety backup of the current data is
  written first) and report the number of restored death records
- `/dc discordtest` — send a test Discord message if a webhook is set
- `/dc reload` — re-read config-dependent state (digest schedule) and confirm

### 6.3 Not in 1.0.0

No record deletion/reset commands, no editing of records, no GUI, no
in-mod HTTP API. (Keeps the census trustworthy and the scope shippable.)

## 7. Discord and exports

### 7.1 Discord

- Config: `discordWebhookUrl` (empty = disabled; supports `env:VAR_NAME`;
  never logged or shown in chat), `discordNotifyDeaths` (default true),
  `discordLanguage` (`en_us`/`ja_jp`), `discordDigestEnabled` (default
  false), `discordDigestIntervalDays` (default 7, 1–30).
- On death (if enabled and the player has not opted out of Discord via
  opt-out — public opt-out also suppresses Discord posts for that player):
  POST JSON `{content: "<death message> — Day N, <dimension>, survived X"}`
  asynchronously on a daemon single-thread executor; failures are logged
  once per failure, never retried, never block the server thread. HTTP via
  `java.net.http.HttpClient` with a 10 s timeout (IML pattern).
- Digest: on server tick, if enabled and `now - lastDigest >= interval`,
  send one summary (deaths since last digest, top causes, top players —
  public only) and store `lastDigestEpochMillis`. Also checked once on
  server start. No deaths in the period = no message, but the timestamp
  still advances.

### 7.2 Exports (renderers in common, file glue in version code)

- **HTML**: one standalone file, inline CSS only, **no JavaScript**, all
  user-derived strings HTML-escaped. Sections: summary, cause statistics
  table, leaderboards, per-player timelines. Public exports exclude
  opted-out players; an operator export includes them only when the
  `includeOptedOutInExports` config (default false) is on.
- **JSON**: `{generatedAt, server: {...totals}, players: [{uuid, name,
  optOutPublic, deaths: [DeathEntry...]}]}` with proper JSON escaping.
- **CSV**: one row per death, header row, RFC-4180 quoting:
  `player_uuid,player_name,epoch_millis,datetime_iso,game_day,play_ticks,survival_ticks,dimension,x,y,z,cause_category,cause_id,killer_type,killer_name,game_mode,experience_level,death_message`.
- File names: `deathcensus-<yyyyMMdd-HHmmss>.<ext>` (plus `-<player>`
  for single-player exports), sanitized player names.

## 8. Privacy

- `/dc optout` hides the player from: other players' `history <player>`,
  all leaderboards, `stats server` player rankings, Discord posts, and
  public exports. Their deaths still count in **anonymous server totals**
  (cause counts) — this is stated in the opt-out confirmation message.
- Operators (`lookup`, admin export with the config flag) can still see
  them; the player always sees their own data.
- Coordinates are part of a death record and follow the same visibility as
  the record itself in 1.0.0 (no separate coordinate toggle).

## 9. Config summary (server config file)

`enabled` (true), `recordCreativeDeaths` (true), `maxRecordsPerPlayer`
(10000), `displayTimeZone` (""), `discordWebhookUrl` (""), 
`discordNotifyDeaths` (true), `discordLanguage` ("en_us"),
`discordDigestEnabled` (false), `discordDigestIntervalDays` (7),
`includeOptedOutInExports` (false), `autoBackupEnabled` (true — backup on
server stop, at most once per hour on world save, IML F10 pattern),
`autoBackupGenerations` (5).

All config accessors must be safe to call before the config is loaded
(return defaults), because unit tests run without a loaded config (IML
pattern).

## 10. Languages

Bundled `en_us.json` / `ja_jp.json` in `common/resources`. Every key used
in code must exist in English (a unit test enforces this, IML pattern);
Japanese translations for all keys in 1.0.0. Chat messages are translatable
components with bundled fallbacks (ServerLang pattern) so vanilla clients
get text in their reported language.

## 11. Testing and CI

- Unit tests (common): classifier table, stats/rankings (including opt-out
  filtering and rarest-cause ties), pagination, escaping, HTML/JSON/CSV
  renderers, Discord payload/digest logic, backup generation pruning,
  survival computation, lang-file completeness.
- Version tests: SavedData round-trip per version, command help/lang keys.
- CI (GitHub Actions): build + unit tests for all four versions against
  min and latest NeoForge (IML matrix pattern, simplified: no client,
  network or migration jobs are needed for a server-only mod with no
  payloads), plus a jar-content check (mods.toml expanded, lang files and
  logo present, no client classes required).
- A dedicated-server smoke check (jar starts, `/dc help` via RCON) is a
  release gate run before publishing, reusing IML's server-test scripts
  where practical.

## 12. Release (1.0.0)

- `CHANGELOG.md` with a `## 1.0.0` section, `CURSEFORGE.md` (en) and a
  Japanese description section, `docs/updates.json`, README status updated
  from "in development" to the release.
- Publish workflow (IML pattern, including its lessons: `--form-string`
  for metadata with semicolons, CurseForge metadata as a plain field,
  environment group versions included) uploads the four JARs to CurseForge
  and Modrinth as Release. Projects are created at release time; any step
  needing pen's own account action (project approval, tokens) is escalated
  to pen with everything else prepared.
- Release JARs are also backed up to the Google Drive folder
  「Muse バックアップ」 (no secrets).

## 13. Implementation stages

1. **Skeleton**: repo layout, Gradle per version (from IML, stripped of
   client/network/gametest machinery), CI, DESIGN.md, lang skeleton.
2. **Core**: common model + classifier + stats, version SavedData + death
   listener + config. Unit tests green on all versions.
3. **Commands**: full §6 tree with ServerLang and pagination.
4. **Sharing**: Discord notify/digest/test, exports, backup.
5. **Docs & release prep**: lang completion, CHANGELOG, CURSEFORGE.md,
   publish workflow; server smoke test; publish to review.
