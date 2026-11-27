# Changelog

## 1.4.0

- Witnesses: each death now records a bounded list of nearby players, shown on
  the death detail and ranked with /dc witnesses; names follow the same
  privacy settings as the rest of the record
- Death context: equipped gear, active effects, biome and weather are captured
  at death and shown in history and exports, and can optionally be posted to
  Discord; old saves and backups load without the new fields
- Awards and titles: /dc awards picks deterministic winners from retained
  records and lifetime counters (most deaths, falls, lava, lightning, respects,
  rarest cause and more), with a configurable minimum count; no winner history
  is stored
- World chronicle: /dc chronicle is a capped, browsable timeline of firsts,
  dragon deaths, category completions and server death milestones, optionally
  included in exports or posted to Discord; entries rebuilt from older data
  are labelled and left undated instead of being given an invented day
- Nemesis and hotspots: /dc nemesis ranks mob species, named rivals and PvP
  rivals; /dc hotspots ranks chunks where deaths cluster and accepts full
  dimension ids such as minecraft:overworld; both use configurable minimums
- Distributions: /dc distribution breaks deaths down by time of day and by
  height band, with configurable time-bin and height-band sizes
- Death loops: /dc loops flags repeated nearby deaths inside a time window,
  with an optional one-time notice to the victim and an opt-in export section;
  only transient state is kept, never a longest-loop record
- Respawn return guide: after death, players get a self-only guide back to
  their dropped items with direction, distance, height difference and the
  despawn countdown; it is never posted for other players, to Discord or in
  exports
- Near-death saves: totem saves and cancelled deaths are counted separately
  with /dc neardeath and a line in /dc stats, bounded per player and
  server-wide; a player whose only records are near-death saves no longer
  makes /dc stats fail
- Player comparison: /dc compare <playerA> <playerB> [period] [--html] shows two
  players side by side in chat, in the GUI and in a standalone HTML report,
  with one-sided privacy opt-outs respected
- Exports and GUI: HTML/JSON/CSV exports gain the new sections in a stable
  order; the in-game Home → Rankings menu reaches every new view, including
  the two-column compare view on all versions
- Help and doctor: /dc help lists every enabled 1.4.0 command plus a
  respawn-guide line; /dc doctor reports feature states and aggregate counts as
  status words only, never the webhook URL
- Review fixes: F respects and excuses no longer drop witnesses or context on
  1.21.1; chronicle milestones count the stored server total and reconstructed
  rows honour the configured cap; rarest-cause winners now match across chat,
  GUI, exports and digests, and the awards GUI honours the configured minimum;
  hidden witnesses and rivals are still scrubbed after a player renames, and a
  real player called "Anonymous" is no longer mistaken for a hidden marker
- Review fixes (reliability): cancelled totem use is no longer saved as a
  near-death event; loop detection keeps tracking active players, and its
  minimum-deaths setting now agrees with what detection requires; crafted
  backups with oversized witness lists or wrong-typed fields are clamped or
  ignored with a warning; and the hidden-witness label is localized in the GUI

## 1.3.0

- Discord delivery: webhook sends are now retried on rate limits (HTTP 429,
  honoring Retry-After) and temporary server errors, with exponential
  backoff, at most four attempts and a bounded queue; several deaths in a
  short window are combined into one message instead of one post per death,
  while a single death still looks exactly the same as before
- Discord channels and filters: the periodic digest can have its own webhook
  URL (empty keeps the current channel) and can be tested with /dc discordtest
  digest; realtime death posts can be limited to an allow-list of dimension
  ids and to PvP-only or non-PvP deaths, while digests and season summaries
  keep counting every death
- Season digests: /dc digest season <from> <to> [html] posts a summary of any
  date range to the digest webhook, with top causes, public players,
  most-respected last words and awards, and can attach a standalone HTML
  report
- Shareable exports: /dc export html|json|csv [player] [--anonymize] replaces
  player names with Player A/B/..., rounds coordinates to 64 blocks and
  scrubs names from messages; anonymized file names never include a player
  name
- Granular privacy: /dc privacy <coords|opponent|discord> <on|off> and the
  in-game privacy screen let players hide their coordinates, hide the PvP
  opponent's name, or skip realtime Discord posts without hiding their whole
  history; anonymous totals and self/admin detail views stay unchanged
- Operator tools: /dc dev demo <count> generates up to 500 synthetic deaths
  for development, but only when both the devMode config option and
  -Ddeathcensus.dev=true are set, and always writes a safety backup first;
  /dc import vanilla [dry-run] adds pre-install vanilla death and killed-by
  statistics to lifetime totals for milestones without inventing history
  records, with dry-run writing nothing
- Permissions: admin checks now pass through a bridge with named node slots
  reserved for a future permissions provider, but 1.3.0 still uses vanilla OP
  level 2 exactly as before, so existing servers see no behavior change

## 1.2.0

- In-game GUI: /dc with no arguments now opens a menu — native dialogs on
  Minecraft 26.x (home, history with filters, death details, rankings,
  privacy settings and admin shortcuts) and a clickable chat menu with
  the same screens on 1.21.1; everything is still server-side only and
  vanilla clients need nothing extra
- Death-notification buttons: after each death, every viewer who may see
  the record gets one extra chat line with [Details] [Excuse] [F]
  buttons; for opted-out players the line goes only to the player
  themselves and to admins, so hidden records never leak
- The GUI delete flow confirms with the player's name, record number and
  date on every version, and always writes a safety backup first

## 1.1.0

- Last words and excuses: a death now records the player's most recent chat
  message (within 30 seconds) as their last words, and /dc excuse <text>
  attaches a one-line excuse to your latest death; both appear in history
  and in HTML/JSON/CSV exports
- Press F to pay respects: when another player dies, typing F (or f) in
  chat within 30 seconds adds a respect count to that death
- Milestone announcements (new config option milestoneAnnouncements,
  default on): first death on the server, a player's first death, every
  100th server death, and completing all 17 cause categories are announced
  in chat and on Discord; counting uses lifetime totals, and opted-out
  players are never named on Discord
- Period rankings: /dc leaderboard <type> [today|7d|30d|all] [page] and
  /dc stats server [period]
- History filters: /dc history and /dc lookup accept a trailing filter —
  a cause category or an exact dimension id
- Operator tools (permission level 2): /dc delete <player> <#>,
  /dc reset player <name>, /dc reset all confirm and /dc doctor; delete
  and reset always write a safety backup first and abort if it fails
- Robustness: partially corrupted save data now skips only the broken
  records instead of discarding whole players; restore can no longer
  delete or overwrite its own source file; the Discord digest counts
  opted-out deaths anonymously in totals and renders in the configured
  language and timezone

## 1.0.0

First release of Death Census.

- Records every player death on the server: cause (death message), dimension,
  coordinates, in-game day, real date/time, playtime at death and survival time
  since the previous death
- Personal death history and statistics with /deathcensus (alias /dc)
- Server-wide death-cause statistics and leaderboards: most deaths, longest
  survival, fastest death, rarest cause
- Discord webhook notifications for each death and an optional periodic digest
- HTML (standalone, no JavaScript), JSON and CSV exports
- Privacy opt-out from public listings (/dc optout), operator lookup,
  JSON backup and restore
- Server-side only for NeoForge 1.21.1 / 26.1.2 / 26.2 / 26.3; vanilla clients
  can join
- English and Japanese localization
