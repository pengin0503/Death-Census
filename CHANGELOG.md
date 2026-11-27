# Changelog

## 1.3.1

This is a maintenance patch for 1.3.0: it hardens saved data, closes privacy
gaps and fixes Discord and UI issues. There are no new features and no config
or language-key changes; existing worlds and settings keep working as before.

- Saved-data safety: a malformed or wrong-typed `players` container keeps the
  original data and pauses saving until an operator has taken a backup, instead
  of silently erasing the census; corrupt player entries and death records are
  skipped with a warning while valid neighbours are kept (all four versions)
- Vanilla death import: lifetime baselines no longer double-count deaths that
  were trimmed or deleted, and a dry run writes nothing at all on a fresh world
- Backups and restores: safety backups keep hidden coordinates and PvP
  opponents exactly as stored, so a restore no longer destroys private data;
  safety backup file names are unique even when several actions happen in the
  same second
- Privacy: opted-out players are now scrubbed from every public view, including
  other players' history, the GUI, Discord posts, exports and season reports;
  anonymized exports build their name dictionary from the whole save, so names
  and old aliases cannot leak in single-player or opted-out-killer exports, and
  name scrubbing is case-insensitive
- Discord delivery: a full worker queue can no longer strand batched death
  messages; a failed or dropped digest keeps its whole time window and is
  retried instead of losing an interval; workers and timers never restart after
  shutdown, the batch header reports its real time window, invalid digest URLs
  warn only once (and show in /dc doctor), multipart boundaries cannot be forged
  by player text, and a shutdown interrupt stops retrying immediately
- Season posts: the admin is told the report is queued until the upload result
  is known, instead of being told it was attached too early
- UI: the 1.21.1 history "prev"/"next" buttons now work, dialog pagination
  accepts whitespace-padded payloads, and delete confirmations report the
  correct death number

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
