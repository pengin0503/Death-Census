# Changelog

## 1.0.0

First release of Death Chronicle.

- Records every player death on the server: cause (death message), dimension,
  coordinates, in-game day, real date/time, playtime at death and survival time
  since the previous death
- Personal death history and statistics with /deathchronicle (alias /dc)
- Server-wide death-cause statistics and leaderboards: most deaths, longest
  survival, fastest death, rarest cause
- Discord webhook notifications for each death and an optional periodic digest
- HTML (standalone, no JavaScript), JSON and CSV exports
- Privacy opt-out from public listings (/dc optout), operator lookup,
  JSON backup and restore
- Server-side only for NeoForge 1.21.1 / 26.1.2 / 26.2 / 26.3; vanilla clients
  can join
- English and Japanese localization
