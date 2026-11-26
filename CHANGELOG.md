# Changelog

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
