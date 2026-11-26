# Death Census 1.1.0 — Plan A spec (approved by pen 2026-11-26)

Base: main after PR #4/#5 (all 1.0.0 review fixes included). Version bump to 1.1.0 happens in the release-prep PR at the end. Server-side only, vanilla clients, MC 1.21.1 / 26.1.2 / 26.2 / 26.3. All features identical across the 4 versions except where APIs differ. en/ja lang parity required for every new key (CensusLangTest enforces).

## PR-1 — Death-record storytelling trio (data model + capture + commands)

DeathEntry gains 3 fields, appended at the END of the record (after experienceLevel):
- `excuse` String (default "") — the dead player's own one-line excuse.
- `lastWords` String (default "") — their last chat message shortly before dying.
- `fCount` int (default 0) — number of "F" respects paid by other players.

Storage compatibility (old saves/backups must load with defaults):
- 1.21.1 NBT: new keys `excuse`, `lastwords`, `fcount`; missing = default.
- 26.x manual ENTRY_CODEC: encode always; decode tolerant (missing = default) — the manual codec already defaults missing fields.
- JSON export/backup (ExportRenderers.toJson): include the 3 fields. Backup parser (CensusExport.parseBackupJson): read with defaults via existing str/integer helpers.
- CSV: append 3 columns at the end (excuse, last_words, f_count) — header updated.
- HTML: show excuse / last words / F count in the timeline rows when non-empty / > 0 (append to the death-message cell or its own line; keep the table structure, add after the message text: `<br>`-separated small text is fine — implementer's choice, must escape HTML as elsewhere).

Capture:
- Last words: listen to NeoForge ServerChatEvent in DeathTracker (all versions); keep a transient map UUID -> (message, epochMillis) in DeathTracker (static, cleared on server stop via existing shutdown path if convenient). On death, if the player's last chat was within 30 seconds before the death, store it as lastWords (truncate to 200 chars). Do not store commands (messages starting with "/").
- F count: on ServerChatEvent, if the trimmed message equals "F" or "f" (exactly), find the most recent death (any other player) within the last 30 seconds; if this sender has not already paid respects to that death, increment its fCount by 1 and mark (death identity = victim UUID + epochMillis). Transient pending state in DeathTracker; the increment must go through CensusData (mutate the stored DeathEntry — records are immutable, so replace the entry in the player's list with an updated copy; add a CensusData method `addRespect(UUID victim, long epochMillis)` returning boolean) and setDirty. No chat feedback (silent), except the F message itself stays a normal chat message.
- Excuse command: `/dc excuse <text...>` (greedy string). Applies to the sender's most recent death record; replaces any existing excuse; max 100 chars (truncate with a notice? simply truncate silently — no: if longer than 100, fail with a message key). If the player has no deaths, fail with a message. Add CensusData method `setExcuse(UUID, String)` operating on the last entry (replace with copy), returns boolean. Show excuse in history lines and stats where the death message is shown: history entry line appends ` · "<excuse>"` when present; also show lastWords in the history line when present (prefix `last words: ` is hard-coded English like the rest of the line — acceptable, lines are already literal-built; keep format consistent: append ` · last words: "..."` and ` · F x<n>` when fCount > 0).

New lang keys (en + ja): command.deathcensus.excuse.done, command.deathcensus.excuse.no_deaths, command.deathcensus.excuse.too_long, help line command.deathcensus.help.excuse. Add excuse to /dc help output.

Common-layer: DeathEntry canonical constructor calls across the codebase and tests must be updated (test helper factories too). Add unit tests: CensusLogic-free — DeathEntry defaults are handled by storage layers; add/extend tests where cheap: ExportRenderersTest (JSON/CSV include new fields), CensusDataTest per version (NBT/codec round-trip with new fields + loading an entry without them).

## PR-2 — Milestone announcements

Common logic (new class Milestones or static methods in CensusStats — implementer picks, keep pure): given the snapshot BEFORE recording and the new entry, determine triggered milestones:
- SERVER_FIRST: total deaths before == 0.
- TOTAL_COUNT: total after crosses a multiple of 100 (before < n*100 <= after).
- PERSONAL_FIRST: this player had 0 deaths before.
- CATEGORY_COMPLETE: after this death, the player's distinct causeCategory count == 17 (CauseClassifier.CATEGORIES size) and before < 17.
DeathTracker evaluates after recordDeath, broadcasts to all online players (chat, via ServerLang keys) and posts to Discord if configured (reuse DiscordNotifier.enqueue path — add a notifyMilestone(String content) that builds from a lang key + args like notifyDeath). Config: new boolean `milestoneAnnouncements` default true gates both chat and Discord.
Lang keys: command-independent `milestone.deathcensus.server_first` etc. — use keys: milestone.deathcensus.server_first (%s player), milestone.deathcensus.total_count (%s total), milestone.deathcensus.personal_first (%s player), milestone.deathcensus.category_complete (%s player). Discord uses the same keys via formatForLang with discordLanguage.
Tests: common unit tests for the trigger logic.

## PR-3 — Period leaderboards/stats + history filters

Periods: all / today / 7d / 30d. Filtering is by epochMillis >= sinceMillis (today = start of today in Config.displayTimeZone(); 7d/30d = now - days*86400000; all = 0).
Common: add since-filtered variants or a helper `CensusStats.filterSince(List<PlayerDeaths>, long sinceMillis)` returning copies with only deaths in range; commands apply it before calling existing stats methods. NOTE the DESIGN §8 rule still applies on the filtered set (anonymous totals include opted-out; rankings exclude).
Commands:
- `/dc leaderboard <type> [page]` gains literal period branches: `/dc leaderboard <type> <today|7d|30d|all> [page]` (literals, unambiguous with the int page arg).
- `/dc stats server [today|7d|30d|all]` literal optional arg.
- Header lines append the period label as a literal suffix (e.g. " (7d)") — fine.
History filter: `/dc history [player] [page] [filter]` and lookup likewise: filter is a word argument; if it matches a known cause category (CauseClassifier.isKnownCategory) filter by category; else treat as dimension id filter (exact match against stored dimension strings, e.g. minecraft:the_nether); else fail with command.deathcensus.filter_unknown. Suggestions: categories + distinct dimensions present in data. Filtering happens before pagination; the header total reflects the filtered count. Implementation detail: the existing history arg tree has page(int)/player(word) branches — add the filter word arg after page in both the self branch (page then filter) and the player branch (player, page, filter; also player then filter without page? keep: player -> filter OR page -> filter; simplest: add filter after page only, plus player->filter direct branch executing page 1). Brigadier ambiguity: after player(word), a word arg could be page? No — page is int, filter is word; a numeric word parses as int first (existing behavior). A non-numeric word after player = filter. That works: player branch gets BOTH an int child (page, which then has a word child filter) and a word child (filter, page=1).
Tests: common tests for filterSince.

## PR-4 — OP tools: delete / reset / doctor

- `/dc delete <player> <#>` (perm 2): # is the number shown by history/lookup (1 = oldest). Before deleting, write a backup (CensusExport.writeBackup); if backup fails, abort. Remove the entry (CensusData.removeDeath(UUID, int indexZeroBased) returns boolean), setDirty, confirm with player name + # + the death's date. Out of range → failure message.
- `/dc reset player <name>` (perm 2): backup first, then clear that player's deaths AND per-player state entirely (remove the PlayerCensus), confirm.
- `/dc reset all confirm` (perm 2): the literal `confirm` is required. Backup first, then replaceAll with empty list, confirm.
- `/dc doctor` (perm 2): prints — storage backend (NBT 1.21.1 / Codec 26.x — just print DATA_NAME/id string per version), player count, total death count, opted-out player count, oldest and newest death date (display timezone), Discord webhook status (not configured / invalid / configured — never the URL), last digest timestamp or "never", backup count + newest backup file name, bundled language count + en fallback key count. All literal+key mix consistent with existing style; add keys for the labels where reasonable (or reuse literal lines like serverStats cause lines — implementer keeps it simple: one header key + literal lines).
Lang keys (en+ja): delete.done, delete.not_found, reset.player_done, reset.all_done, doctor.header, help.admin line updated to include delete/reset/doctor.
Tests: CensusDataTest additions for removeDeath if cheap.

## Release prep (final PR)
- gradle.properties mod_version 1.0.0 -> 1.1.0 in all version projects (check where it lives).
- CHANGELOG.md: 1.1.0 section (fixes from PR #4/#5 + features PR-1..4).
- CURSEFORGE.md / README: mention new features briefly (en/ja where the files already are bilingual).
- updates.json if it carries a version entry.
- Then STOP: store submission needs pen's explicit release go-ahead. At that point also re-verify Modrinth public status (was still 404 on 2026-11-26 despite pen's release report).

## Out of scope for 1.1.0 (do not implement)
Dialog GUI, book UI, storage migration, awards, bingo, hotspots, witnesses, seasons, Fabric/Paper, Discord retry/429 handling, JSON export server-totals restructure, history-line localization.
