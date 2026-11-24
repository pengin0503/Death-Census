# Death Chronicle

**Server-side death records, cause statistics and leaderboards for NeoForge.**

Death Chronicle records every player death on your server — the cause, the
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
Fabric/Quilt only. Death Chronicle takes the opposite angle:

- **Server-wide, not personal.** Every death of every player is kept as a
  permanent server chronicle, not just your own last few deaths.
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
- Personal commands to browse your own chronicle and compare with others
- Discord webhook notifications and a periodic digest
- HTML summary export (standalone file, no JavaScript), JSON / CSV export
- Privacy controls: per-player opt-out of public listings
- Admin tools: lookup any player's history, data backup and restore
- English and Japanese localization

## Targets

- Minecraft **1.21.1** and **26.x** (NeoForge), server-side only
- Vanilla clients can join servers running Death Chronicle

## License

Custom license, all rights reserved. See [LICENSE](LICENSE).
