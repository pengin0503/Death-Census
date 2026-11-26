# Death Census

**Server-side death records, cause statistics and leaderboards for NeoForge.**

Death Census records every player death on your server — the cause, the
place, the moment, and how long the player had survived — and turns that
history into something the whole community can enjoy: personal death
timelines, server-wide leaderboards, and cause-of-death statistics.

It runs **entirely on the server**. Players join with a plain vanilla
client; nothing needs to be installed on the client side.

> **Status: 1.0.0.** See [CHANGELOG.md](CHANGELOG.md) for the release notes
> and [docs/DESIGN.md](docs/DESIGN.md) for the design.

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
