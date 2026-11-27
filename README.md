# Death Census

**Server-side death records, cause statistics and leaderboards for NeoForge.**

Death Census records every player death on your server — the cause, the
place, the moment, and how long the player had survived — and turns that
history into something the whole community can enjoy: personal death
timelines, server-wide leaderboards, and cause-of-death statistics.

It runs **entirely on the server**. Players join with a plain vanilla
client; nothing needs to be installed on the client side.

> **Status: 1.4.0 release.** See [CHANGELOG.md](CHANGELOG.md) for the
> release notes and [docs/DESIGN.md](docs/DESIGN.md) for the design.

## Why another death log?

Existing death-log mods are mostly personal, client-side tools ("where did
*I* die, and can I get my items back?"), and the best-known one is
Fabric/Quilt only. Death Census takes the opposite angle:

- **Server-wide, not personal.** Every death of every player is kept as a
  permanent server census, not just your own last few deaths.
- **Analytics, not just a log.** Death-cause rankings ("this server's #1
  killer is fall damage"), most-deaths leaderboards, rarest causes,
  per-player timelines and survival-time records.
- **Made to be shared.** Discord webhook announcements and digests, plus
  HTML / JSON / CSV exports you can post for your community.
- **NeoForge, modern versions.** Built for Minecraft 1.21.1 and the 26.x
  series, following the same multi-version approach as
  [Item Milestone Log](https://github.com/pengin0503/Item-Milestone-Log).

## Features

- Permanent per-player death history: cause (death message), dimension,
  coordinates, in-game day, real date/time, and playtime at death
- Leaderboards: most deaths, longest survival, fastest death, rarest cause
- Cause-of-death statistics for the whole server
- Personal commands to browse your own census and compare with others
- Discord webhook notifications and a periodic digest
- HTML summary export (standalone file, no JavaScript), JSON / CSV export
- Privacy controls: per-player opt-out of public listings
- Admin tools: lookup any player's history, data backup and restore
- English and Japanese localization
- Last words and excuses: a death records your most recent chat message
  (within 30 seconds) as last words, and /dc excuse <text> attaches a
  one-line excuse to your latest death; both appear in history and in
  HTML/JSON/CSV exports
- Press F to pay respects: typing F (or f) in chat within 30 seconds of
  another player's death adds a respect count to that death
- Milestone announcements: the first server death, a player's first death,
  every 100th server death and completing all 17 cause categories are
  announced in chat and on Discord (config milestoneAnnouncements, default
  on); counting uses lifetime totals
- Period rankings: /dc leaderboard <type> [today|7d|30d|all] [page] and
  /dc stats server [period]
- History filters: /dc history and /dc lookup accept a trailing filter —
  a cause category or an exact dimension id
- Operator tools (permission level 2): /dc delete <player> <#>,
  /dc reset player <name>, /dc reset all confirm and /dc doctor; delete and
  reset always write a safety backup first and abort if it fails
- Discord delivery: webhook sends now retry temporary failures and HTTP 429
  rate limits (honoring Retry-After) with exponential backoff and a bounded
  queue, and bursts of deaths are combined into one message instead of one
  post each
- Discord channels and filters: the digest can use its own webhook URL
  (/dc discordtest digest tests it), and realtime death posts can be limited
  by dimension allow-list and PvP-only / non-PvP modes; digests still count
  every death
- /dc digest season <from> <to> [html] posts a summary of any date range to
  the digest webhook, with an optional standalone HTML report attached
- /dc export html|json|csv [player] [--anonymize] writes a shareable export
  with Player A/B/... pseudonyms, rounded coordinates and player names
  scrubbed from messages
- Granular privacy: /dc privacy <coords|opponent|discord> <on|off> and the
  in-game privacy screen let players hide coordinates, hide the PvP
  opponent's name, or skip realtime Discord posts without hiding their
  whole history; anonymous totals stay unchanged
- /dc dev demo <count> generates up to 500 synthetic deaths for development
  only when both devMode and -Ddeathcensus.dev=true are set, with a backup
  first; /dc import vanilla [dry-run] adds pre-install vanilla death totals
  as a lifetime baseline for milestones without inventing history records
- Admin checks now use a permission-node bridge with names reserved for a
  future provider, but still use vanilla OP level 2 exactly as before

## 1.4.0 additions

The 1.4.0 pack adds public commands for witnesses (`/dc witnesses`), awards
(`/dc awards`), the world chronicle (`/dc chronicle`), nemeses (`/dc nemesis`),
hotspots (`/dc hotspots`), distributions (`/dc distribution`), death loops
(`/dc loops`), near-death saves (`/dc neardeath`) and two-player comparison
(`/dc compare <playerA> <playerB> [period] [--html]`). `/dc help` lists the
commands enabled by the config; the in-game Home → Rankings screen reaches
the same views, including the two-column compare view.

- Witnesses and per-death context (equipment, effects, biome, weather) are
  captured at death time; both are redacted with the existing privacy flags.
- Awards, chronicle, nemesis, hotspots, distributions and loops are derived
  from retained records. No award-winner history or longest-loop record is
  persisted; old saves and backups load with empty new fields.
- After death, a self-only return guide shows direction, distance, height
  difference and the item despawn countdown toward the death location.
- Near-death (totem/cancel) saves are bounded per player and server-wide.
- HTML/JSON/CSV exports gain opt-in chronicle and loop sections; a standalone
  compare HTML report can be written with `/dc compare ... --html`.

New config keys, all with safe defaults: `captureWitnesses` (true),
`witnessRadiusBlocks` (24), `witnessMaxPerDeath` (8), `witnessesInDiscord`
(false); `captureDeathContext` (true), `deathContextMaxEffects` (8),
`deathContextInDiscord` (false), `deathContextInExports` (true);
`awardsEnabled` (true), `awardsDefaultPeriod` (all), `awardsInDigest` (true),
`awardsMinCount` (1); `chronicleEnabled` (true), `chronicleMaxEvents` (200),
`chronicleInExport` (true), `chronicleInDiscord` (false); `nemesisEnabled`
(true), `nemesisMinMobCount` (3), `nemesisMinNamedCount` (2),
`nemesisInDigest` (false); `hotspotsEnabled` (true), `hotspotMinDeaths` (3),
`hotspotChunkSize` (16), `hotspotsInDigest` (false); `compareEnabled` (true),
`compareDefaultPeriod` (all), `compareInDiscord` (false); `distributionsEnabled`
(true), `distributionTimeBinHours` (2), `distributionYBandBlocks` (16),
`distributionsInDigest` (false); `deathLoopEnabled` (true),
`deathLoopMinDeaths` (3), `deathLoopWindowMinutes` (10),
`deathLoopRadiusBlocks` (48), `deathLoopNotifyVictim` (true), `loopsInExport`
(false), `loopsInDiscord` (false); `respawnGuideEnabled` (true),
`respawnGuideMaxTicks` (6000), `respawnGuideArrivalBlocks` (16),
`respawnGuideRefreshTicks` (20); `nearDeathCaptureEnabled` (true),
`nearDeathDedupWindowMillis` (2000), `nearDeathMaxPerPlayer` (50),
`nearDeathInDiscord` (false). `/dc doctor` reports the 1.4.0 feature and
seam states plus aggregate counts — never the webhook URL.

## GUI (`/dc`)

`/dc` (or `/deathcensus`) with no arguments opens an in-game menu; vanilla
clients are enough on every version:

- **26.1.2 / 26.2 / 26.3** — vanilla server-driven dialogs: Home, history and
  search, death detail, rankings, privacy and a read-only operator menu.
- **1.21.1** — the same views rendered as clickable chat lines (commands behind
  the buttons); there is no dialog system on this version.

After each recorded death the mod sends **one extra compact line** with
`[Details] [Excuse] [F]` buttons; the vanilla death message itself is never
changed. Each button targets that specific death. `[Details]`/`[Excuse]` are
only offered to viewers allowed to see the record, and `[F]` uses the existing
one-respect-per-sender dedupe. An opted-out player's death line goes only to
that player and operators; opted-out names never appear in public history,
rankings or death lines, while anonymous server totals still include them.

`/dc help` remains the plain text help on all versions.

## Targets

- Minecraft **1.21.1** and **26.x** (NeoForge), server-side only
- Vanilla clients can join servers running Death Census

## License

Custom license, all rights reserved. See [LICENSE](LICENSE).
