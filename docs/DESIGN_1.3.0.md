# Death Census 1.3.0 — Ops/Discord pack implementation spec

Status: **spec only — no code, no repo changes.** Working title 1.3.0; this document is
written locally and deliberately leaves the `death-census-work` repository untouched.

Sources read before writing this spec:

- `~/workspace/user/files/Death_Census_Ideas.md` §7.1 ("管理・プライバシー（OP向け）")
  and §7.2 ("Discord連携・HTMLレポート・外部出力"), plus §5.3 line 120 for the
  vanilla-statistics baseline.
- `~/workspace/death-census-tools/ROADMAP_POST_1.1.0.md`, the "Ops/Discord pack"
  paragraph (continuation order item 2).
- The current 1.2.0 code tree: `docs/DESIGN.md` §7 (Discord/exports), §8 (Privacy,
  including the **anonymous-totals rule**), §9–§11; `docs/DESIGN_1.1.0.md`;
  `docs/DESIGN_1.2.0.md`.
- Code: common `DiscordLogic`, `ExportRenderers`, `CensusStats`, `DeathEntry`,
  `PlayerDeaths`, `TextEscaping`, `CauseClassifier`, `ui/UiPrivacy`,
  `ui/UiActions`, `ui/PrivacyView`, `ui/UiContext`; version
  `DiscordNotifier`, `CensusExport`, `CensusCommands`, `Config`, `CensusData`,
  `DeathTracker`, `ServerLang`, both `en_us.json`/`ja_jp.json`, and the existing
  `DiscordLogicTest`/`ExportRenderersTest`/`CensusLangTest`.

## 0. Grounding: what exists today (facts the proposals build on)

### 0.1 Version trees

All four trees are at `mod_version=1.2.0`. They are not identical:

| Tree | UI adapter | Admin check | Notable API differences |
|------|-----------|-------------|-------------------------|
| `versions/1.21.1` | `ui/ChatUi` + hidden `/dc ui` tree | `src.hasPermission(2)` | `ResourceLocation`, `getDayTime()`, `getGameProfile().getName()`, `getServer()` |
| `versions/26.1.2` | `ui/DialogUi` | `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` | `Identifier`, `getOverworldClockTime()`, `name()`, `player.level().getServer()` |
| `versions/26.2` | same as 26.1.2 | same as 26.1.2 | same as 26.1.2 |
| `versions/26.3` | same as 26.1.2 | same as 26.1.2 | registers config as `ModConfig.Type.LOCAL` (others `COMMON`) |

Byte-identical across all four trees: `Config.java`, `CensusExport.java`,
`DiscordNotifier.java`, `ServerLang.java`. Divergent per tree:
`CensusCommands.java`, `CensusData.java`, `DeathTracker.java`, `DeathCensus.java`,
`ui/DialogUiContext.java` vs `ui/ChatUiContext.java`. Everything in `common/` is
shared. Therefore every proposal below names (a) `common/` changes once and
(b) the four version files that must be kept in sync, with the 1.21.1 idioms
called out explicitly.

### 0.2 Existing behavior relevant to all ten items

- `DiscordLogic` (common, pure) owns `deathContent`, `digestContent`,
  `labelsFor`, `isValidWebhookUrl` (only `discord.com`/`discordapp.com` HTTPS
  webhook prefixes), and `resolveWebhookUrl` (`env:VAR` support). Digest labels
  are hardcoded English/Japanese records in that class, not lang keys.
- `DiscordNotifier` (version code, identical in all four trees) owns a daemon
  single-thread `ExecutorService`, one `HttpClient` with a 10 s timeout,
  `payloadJson(content)` (truncates to 2000 chars, sends `allowed_mentions.parse=[]`),
  and `enqueue(uri, content)` which does exactly **one** POST and logs a non-2xx
  status or exception once. No retry, no batching, no filters, one webhook for
  everything. `shutdown()` calls `shutdownNow()`; digests are driven by
  `tickDigest`/`checkDigestOnServerStart` with an hour tick throttle and a
  `lastDigestEpochMillis` stored in `CensusData`.
- `DeathTracker` records the death then calls `DiscordNotifier.notifyDeath(entry)`
  only when `!pc.optOutPublic`. `killerType` is an entity-type id
  (`minecraft:player` for players); `CauseClassifier` maps player-caused deaths
  to the canonical category `player` (one of 17 categories).
- `Config` uses `ModConfigSpec`; every accessor catches `IllegalStateException`
  and returns a default because unit tests run with no loaded config (IML
  pattern). Current Discord keys: `discordWebhookUrl`, `discordNotifyDeaths`,
  `discordLanguage`, `discordDigestEnabled`, `discordDigestIntervalDays`.
- `CensusExport.writeExport(server, format, onlyPlayer)` filters opted-out
  players using `includeOptedOutInExports`, then delegates rendering to
  `ExportRenderers` (common). Renderers emit real UUIDs, names, exact
  coordinates, `deathMessage`, `killerName`, `excuse`, `lastWords`. Exports live
  in `<world>/deathcensus/exports`; backups in `<world>/deathcensus/backups`.
- `CensusCommands` is one Brigadier tree per version (26.x uses
  `Commands.hasPermission(LEVEL_GAMEMASTERS)`, 1.21.1 uses `hasPermission(2)`),
  with `/dc export html|json|csv [player]`, `/dc discordtest`, `/dc doctor`,
  `/dc reload`, `/dc optout`/`optin`. `ServerLang.formatForLang` renders Discord
  strings from lang keys; `CensusLangTest` enforces identical key sets in
  `en_us.json` and `ja_jp.json`.
- Privacy today: one `PlayerCensus.optOutPublic` boolean, mirrored into
  `PlayerDeaths.optOutPublic` snapshots. `UiPrivacy.canViewDetail` =
  self || admin || !optOut. DESIGN §8 rule: opted-out players are hidden from
  public listings, Discord posts and public exports, **but their deaths still
  count in anonymous server totals** (`CensusStats.causeCounts(..., true)`,
  `UiPrivacy.anonymousCauseTotals`, digest totals/cause counts); no opted-out
  name may be attached to those numbers. DESIGN 1.1.0 §12 extended this to the
  digest: total/cause counts include them, player rankings do not.
- No permission-node API or soft dependency exists in any `build.gradle`; all
  admin gating is vanilla OP level 2.

### 0.3 Cross-cutting rules for this release

1. **Pure logic in `common/`, platform glue in version code.** Every behavior
   whose correctness matters (retry decision, batch composition, filter
   predicate, season range parsing, anonymization, redaction, demo generation,
   vanilla-stat parsing, permission node names) gets a pure Java class in
   `common/` with unit tests. Version classes stay thin: Brigadier wiring,
   `HttpClient`, `SavedData`, and `ServerLang` lookups.
2. **Never log or chat a webhook URL.** This applies to every new webhook config
   and to all new failure messages. `/dc doctor` prints only status words.
3. **Config accessors must be safe before load** (return defaults), per DESIGN §9.
   New accessors follow the existing `try { X.get(); } catch (IllegalStateException) { default; }`
   shape and get a default-valued unit assertion.
4. **DESIGN §8 is invariant.** Any new named output (exports, Discord, GUI,
   digests) must keep anonymous totals counting *all* deaths and must not attach
   a hidden player's name to those totals. Redaction happens at render time;
   aggregates are computed from unredacted snapshots.
5. **All user-visible strings go into both lang files** with identical key sets;
   Discord strings continue to use `ServerLang.formatForLang`, except where a
   common class needs labels (then follow the `DiscordLogic.DigestLabels` record
   pattern with a common label record).
6. **Every mutating admin command writes a safety backup first** (existing
   delete/reset/restore convention), except where explicitly noted (demo data
   generation does too).
7. **Recommended defaults proceed.** Where a choice is ambiguous, this spec
   states a recommended default in the item and repeats it in §12; implementation
   should take the default unless a human overrides it.

---

## 1. Webhook send retry with backoff and 429 `Retry-After`

### Current behavior

`DiscordNotifier.enqueue` submits one lambda to a daemon single-thread executor.
The lambda builds an `HttpRequest` (10 s timeout, JSON content type), calls
`http().send(...)`, and logs one warning for non-2xx or exception. Failures are
never retried. The executor is an unbounded `LinkedBlockingQueue` via
`Executors.newSingleThreadExecutor`; `RejectedExecutionException` is caught and
logged as "queue is full; dropping a message". `payloadJson` caps content at
2000 characters and disables mentions/everyone parsing.

### Proposed behavior

- Add to `common/` a pure retry engine:
  - `WebhookResponse(int statusCode, String retryAfterHeader)` record and
    `WebhookSender` functional interface `WebhookResponse send(URI uri, String json) throws Exception`.
  - `WebhookRetryDecision(boolean retry, long delayMillis)` record.
  - `WebhookRetry.sendWithRetry(WebhookSender sender, URI uri, String json,
    RetryConfig config, Sleeper sleeper, LongSupplier clock)` returning a small
    terminal `WebhookSendResult(attempts, statusCode, exception, delivered)`.
    `Sleeper` and `LongSupplier` are injected so tests never sleep.
- Retry rules (concrete):
  - Retry on transport exceptions and HTTP status 429 or 5xx; do not retry any
    other 4xx (400/401/403/404 are permanent).
  - At most **4 total attempts** (initial + 3 retries): config
    `discordRetryMaxAttempts` (default `4`, range 1–6).
  - Exponential backoff base **1000 ms**, multiplier 2: 1 s, 2 s, 4 s; config
    `discordRetryBaseDelayMillis` (default `1000`, range 100–5000). No jitter in
    1.3.0 (deterministic tests); optional jitter is an open question (§12).
  - `Retry-After` wins over backoff. Parse integer delta-seconds first, then
    HTTP-date (`DateTimeFormatter.RFC_1123_DATE_TIME`) relative to the injected
    clock; clamp to `[0, 60000] ms`. Unparseable header → treat as absent.
    A header value of `0` means immediate retry without sleeping.
  - Terminal failure logs one warning with attempt count and status code, never
    the URI. Success (2xx) logs nothing, matching today.
- Replace the unbounded executor with a bounded `ThreadPoolExecutor(1, 1, 0,
  MILLISECONDS, new ArrayBlockingQueue<>(256), daemonFactory, dropHandler)` so a
  retry backlog cannot grow without limit. The drop handler logs and discards;
  it never runs on the caller thread (which may be a server tick thread).
- Backoff sleeps happen **only on the daemon Discord worker**, never on a tick
  thread. Document that a long `Retry-After` deliberately delays later webhook
  messages; batching (§2) and the 256-task cap bound the impact.
- Keep the 10 s per-attempt timeout, the 2000-char cap and `allowed_mentions`.
  The HTTP `WebhookSender` implementation stays in version code and uses the
  existing `HttpClient`.

### Files/classes touched per version tree

- `common/`: new `WebhookResponse.java`, `WebhookSender.java`,
  `WebhookRetryDecision.java`, `WebhookRetry.java`; new `WebhookRetryTest.java`.
- All four trees: `DiscordNotifier.java` (executor construction, `enqueue`,
  new sender implementation, log messages); `Config.java` (two new values +
  accessors).
- 1.21.1 vs 26.x: no API differences in this item (`DiscordNotifier` and
  `Config` are byte-identical today); keep them identical.

### Config/lang additions

- Config comments (both new keys live in every `Config.java`):
  `discordRetryMaxAttempts`, `discordRetryBaseDelayMillis`. No lang keys needed:
  retry outcomes are server-log only. `/dc doctor` may add a literal line
  `Discord retry: N attempts / X ms base` (no new key, matching existing literals).

### Test plan

- `WebhookRetryTest` (common, no network): 500→retry with 1000 ms; 503→retry
  2000 ms on third attempt; 429 + `Retry-After: 5`→5000 ms; 429 + HTTP-date in
  the future→clamped to ≤60000 ms; 429 without header→backoff; `Retry-After: 0`
  →zero sleep; 400/401/404/no-status-exception→correct decision; attempts
  exhausted→`delivered=false` with attempt count 4; `maxAttempts=1` disables
  retry. Verify injected `Sleeper` receives exactly the expected delays.
- Version: no live HTTP tests. Assert the new config accessors return defaults
  with no config loaded, and that `payloadJson` still truncates at 2000 chars.

### Risks

- Retry storms against a rate-limited webhook; mitigated by honoring
  `Retry-After`, capping attempts, and the bounded queue.
- Delayed delivery during a 60 s backoff; accepted and documented.
- `Retry-After` HTTP-date depends on server clock accuracy; delta-seconds is
  preferred by Discord in practice and is parsed first.
- Java `HttpClient` may retry idempotent requests internally; POST is not
  idempotent, so our explicit attempt count is the authoritative one for logs.
- Discord may return `retry_after` in the JSON body instead of the header;
  1.3.0 uses the header only (§12 open question).

---

## 2. Mass-death event batching for Discord notifications

### Current behavior

`DeathTracker` calls `DiscordNotifier.notifyDeath(entry)` once per death and
`notifyDeath` immediately enqueues one webhook POST. A server-wide event (e.g.,
a wither or lava flood killing many players in a few seconds) produces one POST
per death, each with its own 10 s HTTP round trip and its own rate-limit cost.

### Proposed behavior

- Add a common pure composer `DiscordBatch`:
  - `static String content(List<String> renderedLines, int totalDeaths,
    String header, String moreSuffix, int maxChars)` where `renderedLines` are
    already localized by version code via `ServerLang.formatForLang(...,
    "discord.deathcensus.death", ...)`.
  - Composition: a header line `"%s deaths in the last %s seconds"`; up to
    `discordBatchMaxDeaths` rendered lines in chronological order; then
    `"…and %s more"` when the buffer held more than the cap; hard truncation to
    `min(2000, maxChars)` characters preserving the closing "+N more" when
    possible. For a single death, return the single rendered line unchanged
    (no header) so ordinary notifications look exactly as today.
- Version `DiscordNotifier.notifyDeath` no longer enqueues directly. It appends
  the death to a synchronized bounded buffer (`ArrayDeque`, capped at 256 entries
  with drop-and-log) designated for the realtime webhook channel, and schedules
  one flush on the Discord worker after `discordBatchWindowMillis`
  (default `1500`, range 0–10000). First death in an empty buffer schedules the
  flush; further deaths in the window only append. The flush drains all buffered
  entries, composes via `DiscordBatch`, and calls the same retry-enabled sender
  from §1. `discordBatchWindowMillis = 0` restores one-POST-per-death behavior.
- Filters (§3) and the opt-out gate are applied **before** buffering, so hidden
  deaths never enter the batch. Milestone announcements, periodic/manual
  digests and `/dc discordtest` bypass batching and are sent immediately.
- `shutdown()` drops any unflushed buffer and logs the count (no send during
  shutdown); this matches today's `shutdownNow()` behavior for queued sends.

### Files/classes touched per version tree

- `common/`: new `DiscordBatch.java`, new `DiscordBatchTest.java`.
- All four trees: `DiscordNotifier.java` (buffer, flush scheduling, shutdown);
  `Config.java` (two new values/accessors). `DeathTracker.java` needs no change
  because batching is internal to `notifyDeath`.
- 1.21.1 vs 26.x: no API-specific changes; keep `DiscordNotifier` identical
  across all four trees.

### Config/lang additions

- Config: `discordBatchWindowMillis` (default `1500`), `discordBatchMaxDeaths`
  (default `10`, range 1–25). Both have explanatory comments ("0 disables
  batching").
- Lang keys in both `en_us.json` and `ja_jp.json`:
  `discord.deathcensus.batch.header` = `"%1$s deaths in the last %2$s seconds"`,
  `discord.deathcensus.batch.more` = `"…and %1$s more"`. Japanese equivalents
  (`"直近 %2$s 秒間に %1$s 件の死亡"`, `"…他 %1$s 件"`).

### Test plan

- `DiscordBatchTest` (common): empty→empty string; one line→the line unchanged;
  three lines→header + three ordered lines; more lines than cap→header + cap
  lines + `+N more`; long lines→result ≤2000 chars; `more` suffix survives
  truncation where possible; null/blank inputs handled.
- Version: verify config defaults; verify the opt-out gate still prevents a
  buffered death (existing `DeathTracker` behavior, no new test seam needed).

### Risks

- Up to 1.5 s extra latency per notification; acceptable for a digest-style
  channel, and `0` restores immediate mode.
- Interaction with the §1 retry worker: a long backoff delays the next flush;
  both are bounded and logged.
- Buffer is process-local: a crash loses unflushed deaths (already true for
  queued sends today).
- A mass-death batch could exceed Discord's 2000-char content limit; the
  composer truncates and reports a "+N more" count.

---

## 3. Notification filters (dimension allow-list, PvP-only / PvP-off)

### Current behavior

Every death of a non-opted-out player is posted. The only "filter" is the
opt-out check in `DeathTracker`. `DeathEntry` already carries everything needed:
`dimension` (exact stored id such as `minecraft:the_nether`), `causeCategory`
(`player` for player-caused deaths), `killerType` (`minecraft:player`) and
`killerName`.

### Proposed behavior

- Add a common pure predicate `DiscordLogic.shouldNotify(DeathEntry entry,
  Collection<String> allowedDimensions, PvpFilterMode mode)`:
  - `allowedDimensions` empty/null = all dimensions; otherwise exact
    `String.equals` against `entry.dimension()` (case-sensitive, matching the
    existing history/dimension filter convention in `CensusCommands`).
  - `PvpFilterMode` enum: `ALL` (default), `PVP_ONLY`, `OFF` (suppress PvP
    deaths). PvP is defined as `causeCategory.equals("player")`; the redundant
    `killerType.equals("minecraft:player")` check is **not** used because the
    category already covers indirect player attribution (§12 open question).
  - The opt-out gate is deliberately **not** in this predicate; it remains in
    `DeathTracker`/`notifyDeath` so §8 behavior stays single-sourced.
- Apply the predicate inside `DiscordNotifier.notifyDeath` before batching, so
  every caller (including future ones) obeys it. Digest, milestones and
  `/dc digest season` do not use it — those are aggregate channels and keep
  their existing anonymous-totals semantics.
- Config: `discordNotifyDimensions` (string list, default empty = all; validator
  rejects blank entries and whitespace so ids like `minecraft:the_end` stay
  well-formed) and `discordNotifyPvpMode` (string enum `all|pvp_only|off`,
  default `all`). Both are documented as realtime-death filters only.
- `/dc doctor` gains two literal lines summarising the active filters (no URLs,
  no new lang keys).

### Files/classes touched per version tree

- `common/`: `DiscordLogic.java` (new enum + predicate); `DiscordLogicTest.java`.
- All four trees: `Config.java` (list value + validator/accessor, enum value +
  validator/accessor); `DiscordNotifier.java` (predicate call); `CensusCommands.java`
  (doctor lines) — remember 1.21.1 uses `hasPermission(2)` idioms where touched.

### Config/lang additions

- Config keys as above with comments; no new lang keys required (doctor uses
  literal text like the rest of that command).

### Test plan

- `DiscordLogicTest` additions: empty list allows all; exact dimension match and
  non-match; `PVP_ONLY` keeps `causeCategory="player"` and drops `fall`;
  `OFF` drops `player` and keeps `fall`; combinations (wrong dimension + PvP)
  AND correctly; null entry/dimension list are safe; opted-out flag on a
  `PlayerDeaths` snapshot does not affect the predicate (opt-out handled elsewhere).
- Version config-default tests: list empty, mode `all` when config is unloaded.

### Risks

- Custom dimension ids are version/world-specific; users must enter the exact
  stored id (same convention as `/dc history <player> <filter>`).
- `PVP_ONLY` includes player-attributed explosion/ projectile deaths, which may
  surprise users expecting only melee; documented in the config comment.
- Filtering realtime deaths but not digests must be stated in `/dc help` (via
  updated help text) and in the config comment to avoid confusion.

---

## 4. Separate digest webhook URL config

### Current behavior

`DiscordNotifier.webhookUri()` always resolves `Config.discordWebhookUrl()`;
death notifications, milestones, digest, test message and reload-triggered
digest all use the same webhook. Digest delivery is otherwise unchanged from
DESIGN §7.1 (hour tick check, interval days, `lastDigestEpochMillis`).

### Proposed behavior

- Add `discordDigestWebhookUrl` (default `""`). Empty means **fall back** to
  `discordWebhookUrl` for backward compatibility. A non-empty but invalid URL
  means "digest channel disabled" — it does **not** silently fall back (so a
  typo is visible in `/dc doctor` rather than posting to the wrong channel).
- Introduce a version-local enum `WebhookChannel { REALTIME, DIGEST }` (or put it
  in common if the season command needs it — recommended: common, next to
  `DiscordLogic`). Change `webhookUri()` to `webhookUri(WebhookChannel)`:
  `DIGEST` resolves `discordDigestWebhookUrl` then falls back to
  `discordWebhookUrl` only when empty; `REALTIME` always resolves the original.
  Both still support `env:VAR` via `DiscordLogic.resolveWebhookUrl`.
- `checkAndSendDigest` uses `DIGEST`; `notifyDeath`, `notifyMilestone`,
  `sendTest`, batch flush and `/dc digest season` (§5) use their matching
  channel. Season digest uses `DIGEST` (with the same fallback).
- Extend `/dc discordtest [digest]`: the optional `digest` literal tests the
  digest channel, default remains realtime. Reuse existing success/failure lang
  keys. `/dc doctor` prints two status lines — `Discord webhook:` and
  `Discord digest webhook:` — each `not configured` / `configured` / `invalid URL`,
  never the URL itself.

### Files/classes touched per version tree

- `common/`: `DiscordLogic.java` may host `WebhookChannel` (pure enum); no logic
  changes otherwise.
- All four trees: `Config.java` (new value/accessor); `DiscordNotifier.java`
  (channel-aware URI resolution and call sites); `CensusCommands.java`
  (`discordtest digest` branch + doctor line) — 1.21.1 and 26.x differ only in
  permission idiom, which is untouched.

### Config/lang additions

- Config key `discordDigestWebhookUrl` with the same secret/`env:` comment as
  the primary URL.
- Lang: update `command.deathcensus.help.admin` in both languages to mention
  `/dc discordtest [digest]` and `/dc digest season` (the latter added by §5 —
  land both edits together in that PR or keep the help key generic). No new key
  is strictly required for item 4 alone.

### Test plan

- Common: `DiscordLogic.resolveWebhookUrl` tests already cover plain/env; add
  an assertion that both configured values resolve independently (pure helper).
- Version: config default test (`""`), doctor output contains both status words
  and never a URL (the URL is a fake local string in a test config); `discordtest`
  with no webhook returns failure, with a configured webhook returns "queued"
  (existing behavior), both channels.

### Risks

- Two webhooks double the secret-management surface; never log either URL.
- Misconfiguration (digest URL valid but pointing at another channel) cannot be
  detected locally; `/dc doctor` status is intentionally only syntactic.
- Existing installs keep one webhook: empty default preserves today's behavior.

---

## 5. `/dc digest season` — manual digest for an arbitrary period

### Current behavior

The only digest is automatic: `DiscordNotifier.tickDigest`/`checkAndSendDigest`
compares `now - lastDigestEpochMillis` against `discordDigestIntervalDays`,
builds a plain `content` message with `DiscordLogic.digestContent(snapshot, last,
zone, labels)` (total + top 3 causes + top 3 public players, opted-out deaths
counted anonymously), advances the timestamp even with zero deaths, and posts it
to the single webhook. There is no manual trigger, no arbitrary range, no HTML
output and no season summary/awards.

### Proposed behavior

- **Command:** `/dc digest season <from> <to> [html]`, admin-only (same gate as
  `/dc discordtest`). Both range arguments are `StringArgumentType.word()` so
  they can be either `yyyy-MM-dd` (interpreted at start-of-day in
  `Config.displayTimeZone()`) or `epoch:<millis>` for an exact instant. The
  optional `html` literal attaches a standalone HTML report to the same post.
  Invalid ranges fail with a lang message; `from >= to` is rejected.
- **Pure parser in common:** `SeasonRange.parse(String from, String to, ZoneId zone)`
  returning `SeasonRange(long fromMillisInclusive, long toMillisInclusive)` or a
  precise failure enum (`BAD_FORMAT`, `BAD_DATE`, `INVERTED`). Used by all four
  version trees; unit-tested without Minecraft.
- **Content:** new common `DiscordLogic.seasonContent(List<PlayerDeaths> players,
  SeasonRange range, ZoneId zone, DigestLabels labels, SeasonAwards awards)`
  filters deaths by `epochMillis` in `[from, to]`, then renders, in this order:
  1. header `Season digest: N death(s), yyyy-MM-dd → yyyy-MM-dd`;
  2. top 3 causes (reusing `CensusStats.causeCounts(players, true)` — anonymous
     totals include opted-out deaths);
  3. top 3 public players (`leaderboardDeaths(players, false)` — no opted-out
     names);
  4. "Most-respected last words" top 3: deaths with non-empty `lastWords`,
     sorted by `fCount` desc then `epochMillis` desc, shown with pseudonymous or
     public name only (opted-out players' entries are excluded from this named
     section entirely, but their deaths remain in the total/cause counts per
     DESIGN §8);
  5. award lines derived from existing data: all-17-category completers (from
     `PlayerCensus.lifetimeCategories`, public players only), longest survival
     in range, rarest cause in range.
  Empty range → a lang-keyed "no deaths in this period" message and no POST.
- **Embed + HTML attachment:** extend version `DiscordNotifier` with
  `postSeason(WebhookChannel.DIGEST, String content, @Nullable HtmlAttachment html)`.
  The JSON body gains an optional `embeds` array (title "Death Census — Season
  summary", one field per section above) built with Gson; existing sends keep the
  old `payloadJson` shape unchanged. When `html` is present, the POST uses
  `multipart/form-data` with `payload_json` and `files[0]` (filename
  `season-<yyyyMMdd>-<yyyyMMdd>.html`), reusing the retry engine from §1. Before
  posting, the command writes the HTML via a new
  `CensusExport.writeSeasonHtml(server, range)` overload which filters the
  snapshot to the range and calls `ExportRenderers.toHtml(...)` (standalone,
  no JavaScript, as today). Attachment cap 1 MB; if the file is larger or the
  multipart POST fails terminally, fall back to a content-only message and tell
  the invoking admin the local file path in chat (never a URL).
- Season posts do **not** touch `lastDigestEpochMillis`, are not rate-limited by
  the periodic digest throttle, and do not apply the realtime notification
  filters from §3 (aggregate channel, §0.3 rule 4).
- Both admin help texts and the doctor output mention the new command/status.

### Files/classes touched per version tree

- `common/`: new `SeasonRange.java`; `DiscordLogic.java` (season content +
  awards record); new `SeasonRangeTest.java` and `DiscordLogicSeasonTest.java`;
  `CensusStats.java` only if a small range-filter helper is factored out of
  `filterSince` (recommended: reuse `filterSince` with a range pair instead).
- All four trees: `CensusCommands.java` (command tree + range parse + html
  branch, 1.21.1 permission idiom), `CensusExport.java` (range HTML writer),
  `DiscordNotifier.java` (embed/multipart post), `CensusData.java` (read-only
  access to `lifetimeCategories` for all-category awards — no schema change),
  `ServerLang`/lang files (new keys).

### Config/lang additions

- No new config key: season uses the digest webhook from §4 and the existing
  `displayTimeZone`/`discordLanguage`.
- Lang keys in both files (identical key sets): `command.deathcensus.digest.season.bad_range`,
  `.empty`, `.done`, `.attached`, `.attachment_failed`, `.help` (plus updated
  `command.deathcensus.help.admin`); Discord-side strings use existing
  `DiscordLogic.DigestLabels` plus the new lang keys for header/last-words/awards
  so `discordLanguage` keeps working on dedicated servers.

### Test plan

- `SeasonRangeTest`: ISO date boundaries in `Asia/Tokyo` vs UTC; `epoch:` form;
  inverted/equal bounds; malformed input; leap-day handling.
- `DiscordLogicSeasonTest`: in-range vs out-of-range deaths; inclusive bounds;
  total/cause counts include opted-out deaths; no opted-out name appears in
  header, rankings or last-words; empty range→empty content; award lines with
  0/1/many qualifiers; 17-category completer count; language label parity.
- Version: command registration and permission gate (admin-only); `writeSeasonHtml`
  writes a file containing a filtered record set and no out-of-range deaths;
  `discordtest digest` unaffected; no change to `lastDigestEpochMillis` after a
  season post.

### Risks

- Multipart webhook support is new code in `DiscordNotifier`; keep it isolated
  behind `postSeason` and fall back to content-only.
- Large arbitrary ranges can produce huge HTML; cap the attachment at 1 MB and
  let the renderer's existing standalone format limit memory.
- "Last words best 3" is a judgement call; the deterministic `fCount` rule is
  documented in the lang text so users know what the ranking means.
- Opt-out leakage risk is highest here (last words are free text that may
  contain names); the named sections must filter opted-out players while
  anonymous totals still include them.
- Embed payload shape is not covered by existing tests; add a version-side
  serialization assertion that no URL/secret appears in it.

---

## 6. Anonymized export flag (`--anonymize`) for HTML/JSON/CSV

### Current behavior

`/dc export html|json|csv [player]` →
`CensusExport.writeExport(server, format, onlyPlayer)` → filter opted-out
players → `ExportRenderers.toHtml/toJson/toCsv`. All three renderers emit real
UUIDs, player names, exact `x/y/z`, `deathMessage`, `killerName`, `excuse`,
`lastWords`. Filenames include the player name for single-player exports. There
is no anonymization option; the only privacy control is excluding opted-out
players via `includeOptedOutInExports`.

### Proposed behavior

- Add a trailing literal `--anonymize` to the export command under each format
  and under the optional player branch:
  `/dc export <format> [player] [--anonymize]`. Pass a boolean through to
  `CensusExport.writeExport(server, format, onlyPlayer, anonymize)`; keep the
  old 3-arg overload delegating with `false` for compatibility.
- Add a common pure `Anonymizer`:
  - `static List<PlayerDeaths> apply(List<PlayerDeaths> players)` returns a
    redacted *copy*; mapping is deterministic: sort players by UUID and assign
    `Player A`, `Player B`, … (past Z: `Player AA`, …), stable across formats and
    repeated runs on the same data set.
  - UUID replaced with `anon-<n>`; `name` → pseudonym; `x/y/z` rounded to the
    nearest **64 blocks** toward zero (e.g., `x=130` → `128`) as doubles, so JSON
    stays numeric; death records keep time, day, cause, category, survival,
    levels and respect counts.
  - Replace every known real player name inside `deathMessage`, `lastWords`,
    `excuse` and `killerName` with that player's pseudonym (longest names first,
    word-boundary match) so player-vs-player deaths do not leak names. `killerType`
    (an entity type id) is not personal and remains.
  - Add `"anonymized": true` to the JSON root; keep `optOutPublic` present but
    normalized to `false` in anonymized output (the real flag is not meaningful
    for pseudonyms).
- Renderers gain `anonymize` overloads (or take the already-redacted list); no
  changes to escaping, RFC-4180 CSV quoting or the no-JavaScript HTML rule.
- Filenames for anonymized exports gain `-anonymized` before the extension and
  **never** include a player name, even for single-player exports.
- Opt-out filtering still runs **before** anonymization: an opted-out player is
  excluded unless `includeOptedOutInExports` is on. Anonymous totals (DESIGN §8)
  are unaffected — anonymization is render-time only and never feeds aggregates.
- Chat confirmation uses a new lang key stating the file is anonymized.

### Files/classes touched per version tree

- `common/`: new `Anonymizer.java`, new `AnonymizerTest.java`;
  `ExportRenderers.java` (overloads + JSON metadata); optionally `TextEscaping.java`
  if a safe name-replacement helper is shared.
- All four trees: `CensusCommands.java` (flag branches, 1.21.1 vs 26.x idioms);
  `CensusExport.java` (overload + filename suffix — file is identical across
  trees today, keep it so); `ServerLang`/lang files.

### Config/lang additions

- No config key (the flag is per invocation).
- Lang keys in both files: `command.deathcensus.export.anonymized` (confirmation),
  `command.deathcensus.help.export` or updated `help.admin` mentioning
  `[--anonymize]`, and `command.deathcensus.export.bad_combo` if needed (recommended:
  allow the flag with a single player, so no error key needed).

### Test plan

- `AnonymizerTest`: stable `Player A/B` mapping across formats; uniqueness when
  UUIDs sort differently from names; no original UUID/name survives in any
  rendered string; name replacement inside death messages and killer names;
  coordinate rounding to 64-block multiples; empty/null lists; `>26` players;
  JSON contains `anonymized:true`; opt-out flags normalized.
- Renderer parity: `ExportRenderersTest` additions for all three formats with
  anonymize on/off; CSV still RFC-4180 quoted; HTML still contains no `<script>`.
- Version: command parses `--anonymize` before/after player; filename contains
  `-anonymized` and no real name; opted-out players still excluded by default.

### Risks

- Free-text name replacement can over-match short/common names; mitigate with
  word boundaries, longest-first replacement and a test with overlapping names.
- Rounding coordinates to 64 blocks still reveals approximate base location;
  documented in the command help (§12 asks whether to use 256 blocks instead).
- Pseudonym order must be deterministic or cross-format comparisons break; the
  UUID sort is the contract.
- Anonymized exports are not backups; they live in `exports/` and must never be
  fed to `CensusExport.restore` (which reads `backups/` only).

---

## 7. Granular privacy toggles (coords-only, PvP opponent, Discord-only)

### Current behavior

One per-player boolean `PlayerCensus.optOutPublic` and its snapshot mirror
`PlayerDeaths.optOutPublic`. `/dc optout`/`optin` and the 1.2.0 GUI Privacy
screen toggle it. `UiPrivacy.canViewDetail` = self || admin || !optOut;
`DeathTracker` suppresses Discord posts for opted-out players; `CensusExport`
filters them; `CensusStats.causeCounts(..., true)` still counts their deaths in
anonymous totals (DESIGN §8). Coordinates follow the record with no separate
toggle (DESIGN §8 last bullet).

### Proposed behavior

- **Three new per-player booleans** on `PlayerCensus` in both version trees:
  `hideCoordinates`, `hidePvpOpponent`, `discordOptOut`. Codec encode/decode
  gains the three fields; absent fields decode as `false` (old saves keep working).
  Add the same three fields to the common `PlayerDeaths` snapshot record (with a
  compatibility 4-arg constructor defaulting them to `false`) and propagate them
  through `CensusStats.filterSince`, `DiscordLogic.digestContent` and every
  `new PlayerDeaths(...)` call site. Backup JSON (`ExportRenderers.toJson` /
  `CensusExport.parseBackupJson`) gains the fields, defaulting to `false` when
  absent.
- **Semantics (truth table):**

  | Flag | Public history/GUI detail/exports | Discord realtime | Anonymous totals (DESIGN §8) |
  |------|-----------------------------------|------------------|------------------------------|
  | `optOutPublic=true` (existing) | player hidden entirely (self/admin still see) | suppressed | death counted, never named |
  | `hideCoordinates=true` | coords shown as `~` marker; rest of record visible | coords omitted from death post | unchanged |
  | `hidePvpOpponent=true` | `killerName` hidden and opponent name scrubbed from `deathMessage`; cause/category kept | opponent name scrubbed the same way | unchanged |
  | `discordOptOut=true` | no change | realtime death post suppressed | unchanged; player still named in digests unless also opted out |

  `optOutPublic` remains the master switch: it alone hides a player from public
  listings/exports; the three granular flags are independent and **subordinate**
  (turning one on does not imply opt-out; turning opt-in on does not clear them).
  Discord suppression is `optOutPublic || discordOptOut`. Since `optOutPublic`
  already hides everything public, the granular flags matter mainly for self/admin
  views and for players who want only one field hidden.
- **Common redactor** `DeathRedactor.apply(DeathEntry entry, PlayerPrivacy privacy,
  boolean viewerIsSelfOrAdmin)` returns a copy with coordinates and/or opponent
  name redacted when the viewer is neither self nor admin and the matching flag
  is set. Use it in `CensusCommands.formatEntryLine`, `ui/UiActions` detail
  rendering, `ExportRenderers` (public export path) and `DiscordNotifier.notifyDeath`
  (Discord is always a "public" viewer; self/admin exemption does not apply).
  Redaction must never be applied to the snapshot used for `CensusStats`/
  `UiPrivacy.anonymousCauseTotals`/digests.
- **Commands:** keep `/dc optout`/`optin` unchanged. Add
  `/dc privacy <coords|opponent|discord> <on|off>` (player self-service, no admin
  gate) with lang feedback; the hidden 1.21.1 `/dc ui privacy` subtree gains
  matching input tokens so the chat GUI can toggle the same fields.
- **GUI:** `PrivacyView` record expands to four booleans + explanation;
  `UiContext` gains `getPrivacyFlags(UUID)`/`setPrivacyFlag(UUID, flag, boolean)`;
  `UiActions` handles `in_hide_coords`, `in_hide_opponent`, `in_discord_optout`,
  with `DialogUi`/`ChatUi` adapters rendering an extra toggle line each. All
  permission checks stay server-side via the existing `UiPrivacy` gate.
- **DESIGN §8 interaction (explicit):** granular flags never change totals.
  Opted-out deaths keep counting in server totals, server cause statistics,
  digest totals/cause counts and GUI anonymous totals lines; no name (real or
  pseudonym attached to a hidden player) may be attached to those numbers. The
  last-words award section of §5 must exclude any player with `optOutPublic` or
  `discordOptOut` from named entries.

### Files/classes touched per version tree

- `common/`: new `DeathRedactor.java` + `PlayerPrivacy` helper record;
  `PlayerDeaths.java`, `ExportRenderers.java`, `DiscordLogic.java`,
  `CensusStats.java` (propagation), `ui/PrivacyView.java`, `ui/UiActions.java`,
  `ui/UiContext.java`, `ui/UiPrivacy.java`; new tests `DeathRedactorTest.java`,
  `UiPrivacyTest` additions, `CensusStatsTest` additions.
- All four trees (each `CensusData.java` diverges, so all four must be edited):
  `PlayerCensus` fields + codec encode/decode + defaults for old saves;
  `CensusExport.java` backup JSON read/write; `CensusCommands.java`
  (`/dc privacy`, `formatEntryLine`, propagation), `DeathTracker.java` (pass
  flags to `notifyDeath`/redactor), `DiscordNotifier.java`, both UI adapters
  and their contexts; `ServerLang`/lang files.
- 1.21.1-specific: the `/dc ui privacy <toggle> on|off <token>` command branch
  and `ChatUi` privacy screen; 26.x-specific: `DialogUi` privacy screen inputs.

### Config/lang additions

- No global config key (all flags are per-player); optionally document them in
  the `includeOptedOutInExports` comment.
- Lang keys in both files: `command.deathcensus.privacy.coords.on/off`,
  `.opponent.on/off`, `.discord.on/off`, `.help`, `command.deathcensus.ui.input.*`
  for the three new toggles, `command.deathcensus.ui.privacy.*` labels/tooltips,
  and a marker for redacted coords (`command.deathcensus.ui.detail.coords_hidden`,
  e.g. `~ (hidden)`).

### Test plan

- `DeathRedactorTest`: each flag on/off; self/admin vs public viewer; combined
  flags; opponent-name scrubbing in `deathMessage`; null/blank safety; original
  `DeathEntry` untouched (immutability).
- `CensusDataTest` (all four trees): encode/decode round-trip of the three new
  flags; old save without fields decodes to `false`; backup JSON round-trip.
- `UiPrivacyTest`: truth table above; `anonymousTotalsLine` unchanged by flags.
- `CensusStatsTest`: `causeCounts(..., true)` counts deaths of players with any
  granular flag; named leaderboards still exclude `optOutPublic`.
- `DiscordLogicTest`: redacted death content contains no coords/opponent when
  flags set; digest totals still include those deaths and contain no hidden name.
- `CensusLangTest`: identical key sets in both languages after the additions.

### Risks

- Storage schema change touches four divergent `CensusData` codecs and backup
  JSON; a missed decode default would make old saves read `true`/garbage.
- Propagating new `PlayerDeaths` components to every constructor call is easy to
  miss; the compatibility constructor reduces but does not remove this risk.
- Free-text scrubbing of opponent names can over-match; same mitigation as §6.
- `discordOptOut` semantics (realtime only vs all Discord) is a product decision
  recorded in §12; the digest still names the player under the recommended default.
- GUI input-token additions must be re-evaluated server-side to avoid a client
  forging another player's flags (`UiActions` already re-checks self/admin).

---

## 8. Demo-data generation command (debug/dev only, gated)

### Current behavior

No command or config exists for generating sample data. Adding test records today
means editing a save file or killing players manually; there is no safe way to
populate a development world for GUI/export/Discord testing.

### Proposed behavior

- **Double gate (both required):** config `devMode` (default `false`, comment
  "development only — never enable on a production server") **and** JVM system
  property `deathcensus.dev=true` (start flag `-Ddeathcensus.dev=true`). A pure
  common helper `DemoGate.enabled(boolean configFlag, String systemProperty)`
  is the single decision point and is unit-tested. Missing either gate fails the
  command with a lang message; no data is written. This is deliberately stricter
  than a single config toggle.
- **Command:** `/dc dev demo <count>` (admin only, count 1–500 via
  `IntegerArgumentType.integer(1, 500)`). Writes a safety backup first (same as
  delete/reset) and aborts if that fails. Logs a loud warning
  `[DeathCensus] DEMO DATA GENERATED: N synthetic deaths` with the invoking
  source; no webhook or milestone announcements are sent.
- **Generator in common:** `DemoDataGenerator.generate(int count, long epochNow,
  ZoneId zone, long seed)` returns deterministic `DeathEntry` records with
  `SeededRandom(seed)`:
  - synthetic UUIDs in a fixed namespace and names `Demo Player 01`, `Demo Player 02`, …
    (never a real online player's name);
  - timestamps spread over the last `count` hours, game days derived from the
    timestamps, all 17 `CauseClassifier.CATEGORIES` covered when `count >= 17`;
  - the three vanilla dimension ids, plausible coordinates, survival/play ticks,
    non-empty `lastWords`/`excuse` on a subset, `fCount > 0` on a subset;
  - every generated player gets fresh default privacy flags (no opt-out).
  Insert via the existing `CensusData.recordDeath(entry, Config.maxRecordsPerPlayer())`
  so trimming/lifetime counters behave normally, then mark dirty as usual.
- Provide `/dc reset all confirm` (existing) as the documented cleanup path;
  demo records are ordinary records once inserted.

### Files/classes touched per version tree

- `common/`: new `DemoGate.java`, `DemoDataGenerator.java`, and their tests.
- All four trees: `Config.java` (`devMode`); `CensusCommands.java` (`/dc dev demo`
  branch + backup-first call, 1.21.1 vs 26.x permission idioms);
  `CensusExport.java` is used via its existing `writeBackup`; no export changes.

### Config/lang additions

- Config key `devMode` (default `false`) in every `Config.java`, with an explicit
  "do not enable in production" comment.
- Lang keys in both files: `command.deathcensus.dev.gated`,
  `command.deathcensus.dev.demo.done`, `command.deathcensus.dev.demo.count`,
  `command.deathcensus.dev.help` and an updated `help.admin`.

### Test plan

- `DemoGateTest`: four combinations of config/system property (only both true
  enables); null/blank property safe.
- `DemoDataGeneratorTest`: identical output for identical seed/input; bounds on
  count; all categories covered at count≥17; no real player names; all fields
  non-null and lengths within record constraints; no Discord/milestone side
  effects (these live in version code, so assert the command path does not call
  them by construction).
- Version: command absent/denied when a gate is off; backup written before
  insertion; count 0/501 rejected by Brigadier; lang keys present in both files.

### Risks

- Accidental production use: mitigated by the double gate, `Demo Player NN`
  names, a loud log line and the existing reset command.
- Save-file pollution and inflated lifetime counters; mitigated by backup-first
  and documenting cleanup.
- Generating 500 records touches all four `CensusData` codecs on save; bounded.
- A system property is process-wide; it cannot be toggled at runtime (documented).

---

## 9. Vanilla statistics import as a baseline (pre-install totals)

### Current behavior

Nothing reads vanilla `world/stats/*.json`. `CensusData` keeps a lifetime death
counter (`lifetimeDeaths`, used by milestone evaluation) and per-player
`lifetimeCategories`, initialized from retained records on first load; server
statistics are computed from retained `DeathEntry` records only. There is no
notion of "deaths before the mod was installed".

### Proposed behavior

- **Command:** `/dc import vanilla [dry-run]`, admin-only, backup-first for a
  real import. Reads `<world>/stats/<uuid>.json` only for UUIDs already known to
  `CensusData.allPlayers()` (ignores unrelated stat files); `dry-run` reports
  what would be imported and writes nothing.
- **Pure parser in common:** `VanillaStatsParser.parse(String json)` →
  `VanillaStats(int deaths, Map<String,Integer> killedBy)`. Reads
  `stats.minecraft:custom.minecraft:deaths` (number) and
  `stats.minecraft:custom.minecraft:killed_by` (object of entity id → count),
  tolerates missing keys as zero/empty, returns empty on malformed input, and
  never reads player names (only UUID filenames).
- **Baseline model:** add to `PlayerCensus` (all four trees) `vanillaDeathsBaseline`
  (int, default 0) and `vanillaBaselineCauses` (map of category → count, default
  empty), encoded in the codec with defaults for old saves. Import is
  **idempotent**: it replaces these fields, never adds to them; re-importing the
  same files does not double-count.
- **Where the baseline applies (recommended default):**
  - `CensusData.lifetimeDeaths()` reports `storedLifetime + sum(vanillaDeathsBaseline)`,
    so milestone thresholds ("every 100th death") account for pre-install play.
  - `/dc stats server` and `/dc doctor` gain a literal line
    `Vanilla baseline: N pre-install death(s)`; `/dc stats server` cause
    percentages still come from recorded deaths only, so the two totals are
    explicitly labelled.
  - No synthetic `DeathEntry` records are fabricated: history, per-player
    timelines, leaderboards and HTML/JSON/CSV exports remain unchanged. This
    avoids inventing timestamps/coordinates and keeps DESIGN §8 aggregate
    semantics intact (baseline deaths are anonymous totals only).
  - `killed_by` entity ids map to cause categories via `CauseClassifier.classify(id,
    false)` with an explicit override `minecraft:player → player` (the classifier
    otherwise cannot know the attacker was a player from vanilla stat data alone).
    The mapped baseline cause counts are shown only in the `vanilla baseline`
    diagnostic breakdown, not merged into live cause percentages.
- Report per player: UUID (or last known name only to an admin), vanilla deaths,
  top vanilla cause. Dry-run output is read-only and safe for any admin.

### Files/classes touched per version tree

- `common/`: new `VanillaStatsParser.java` + `VanillaStats.java` + tests;
  `CauseClassifier.java` only if the `minecraft:player` override is added there
  (recommended: keep the override in the parser).
- All four trees: `CensusData.java` (new fields, codec, accessor, import method,
  lifetime sum — **four separate edits**), `CensusCommands.java` (command branch,
  1.21.1 vs 26.x idioms), `CensusExport.java` (backup-first via existing method),
  `ServerLang`/lang files.

### Config/lang additions

- No config key (manual, admin-triggered).
- Lang keys in both files: `command.deathcensus.import.vanilla.done`,
  `.dry_run`, `.no_players`, `.failed`, `.per_player`, `.help`, plus updated
  `help.admin`.

### Test plan

- `VanillaStatsParserTest`: real-world-shaped JSON with `stats` nested object;
  missing `custom`/`deaths`/`killed_by`; string-vs-number tolerance; malformed
  JSON → empty; `minecraft:player` override; multiple causes.
- `CensusDataTest` (all four trees): baseline fields persist through save/load
  and backup/restore; old save defaults to 0/empty; `lifetimeDeaths()` includes
  baseline without mutating the stored counter; import is idempotent.
- Version: command admin-gated; dry-run writes nothing; real import writes a
  backup first; unrelated stat files ignored.

### Risks

- Vanilla stat key names/shape may differ across 1.21.1 vs 26.x; the parser is
  tolerant and the dry-run lets an operator verify before committing (§12 asks
  whether to verify against local game artifacts before coding).
- `lifetimeDeaths()` changing affects milestone cadence — intentional, but must
  be documented in the CHANGELOG.
- Baseline totals and live record totals are different numbers; label both in
  output to avoid confusion.
- Reading many stat files is bounded by `allPlayers()` (known players only).

---

## 10. Permission nodes (if the platform supports them) — explicit deferral

### Current behavior

All admin operations are gated by vanilla OP level 2:
`Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` in 26.1.2/26.2/26.3 and
`src.hasPermission(2)` in 1.21.1; `UiContext.isAdmin()` mirrors the same check.
No `build.gradle` declares a permissions API, no soft dependency is registered,
and `@Mod` has no permission-provider dependency. There is no named node such as
`deathcensus.export`.

### Proposed behavior

- **Platform finding:** NeoForge (all four trees) has **no built-in named
  permission-node API**. Supporting LuckPerms-style nodes would require a new
  optional dependency plus version-specific classloading care, which belongs to
  the later infra phase in `ROADMAP_POST_1.1.0.md` (step 4), not to this low-risk
  ops pack. Therefore: **explicit deferral — no functional node integration ships
  in 1.3.0.** This section documents the seam so the deferral is deliberate
  rather than an omission.
- Add a common enum `DeathCensusPermission` with the future node names:
  `deathcensus.export`, `deathcensus.lookup`, `deathcensus.digest`,
  `deathcensus.backup`, `deathcensus.reset`, `deathcensus.dev`,
  `deathcensus.import`, `deathcensus.privacy.other`. Names are the contract;
  they are not yet consulted for access control.
- Add a version-local `PermissionBridge` (one small class per tree) with
  `static boolean allowed(CommandSourceStack source, DeathCensusPermission node,
  boolean opLevelTwoFallback)`. In 1.3.0 it **always returns the fallback**, which
  is the current OP-level-2 result; every `.requires(...)` in `CensusCommands`
  and `UiContext.isAdmin()` routes through it. This is a pure refactor with no
  behavior change. A future infra PR replaces only the body with a soft lookup
  (recommended: check a permissions provider only when present, otherwise the
  vanilla fallback), without touching command or UI code.
- `/dc doctor` prints a literal line `Permissions: OP level 2 (node integration deferred)`.
- No config key (`permissionMode`) is added in 1.3.0 to avoid shipping a dead
  option; it is an open question (§12) for the infra phase.

### Files/classes touched per version tree

- `common/`: new `DeathCensusPermission.java` + a trivial name-set test.
- All four trees: new `PermissionBridge.java`; `CensusCommands.java` (replace
  each admin `.requires(...)` with `PermissionBridge.requires(node)`; 1.21.1
  passes `src.hasPermission(2)`, 26.x passes
  `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(...)`);
  `ui/DialogUiContext.java` / `ui/ChatUiContext.java` (`isAdmin()` via bridge);
  `CensusCommands.doctor` literal line.

### Config/lang additions

- No config or lang additions required (doctor uses an existing literal-line
  pattern). If a future phase adds node mode, both langs will need a status key.

### Test plan

- Common: enum contains exactly the documented node names and no duplicates.
- Version: existing command/UI tests must pass unchanged (proof of no behavior
  change); verify `PermissionBridge.allowed(..., true)` is `true` and
  `(..., false)` is `false` with the 1.3.0 body; confirm non-admin players still
  cannot run `/dc export`, `/dc digest season`, `/dc dev demo`, `/dc import vanilla`.

### Risks

- Refactoring every permission gate at once risks a missed node or an inverted
  fallback; mitigate by a mechanical one-to-one replacement and full test run on
  all four trees.
- Advertising node names without implementing them could mislead users; the
  doctor line and this spec state the deferral explicitly.
- A future soft dependency must remain optional across four Minecraft versions;
  the bridge keeps that risk isolated to one file per tree.

---

## 11. Phased PR plan (ordered, independently mergeable)

Every PR below is expected to leave `common` + all four version trees building and
their tests green, with identical `en_us`/`ja_jp` key sets (`CensusLangTest`) and
no repo-local spec/docs churn beyond that PR's own lang/config/comment changes.
"Depends on" means the earlier PR should land first; each PR is still a coherent,
revertible unit and can be merged in the listed order without feature flags.

| PR | Scope (spec section) | Depends on | Merge gate |
|----|----------------------|-----------|------------|
| 1 | Permission-node seam + deferral note (§10), including `PermissionBridge` in all four trees | — | Existing command/UI tests unchanged; doctor line added |
| 2 | Retry engine + bounded executor + two config keys (§1) | 1 | `WebhookRetryTest` green; no live HTTP; config defaults asserted |
| 3 | Separate digest webhook URL + `/dc discordtest [digest]` + doctor status (§4) | 2 | Both channels resolve independently; no URL ever logged |
| 4 | Notification filters: dimension allow-list + PvP mode (§3) | 2 | `DiscordLogicTest` predicate matrix; filters do not touch digest |
| 5 | Mass-death batching + batch lang keys (§2) | 2, 4 | `DiscordBatchTest`; 1 death renders as today; opt-out never buffered |
| 6 | `/dc digest season` with range parser, awards, embed + HTML attachment (§5) | 2, 3 | `SeasonRangeTest`/season content tests; S8 leak check; no digest timestamp change |
| 7 | Anonymized export flag + `Anonymizer` + renderer overloads (§6) | — | Cross-format pseudonym stability; no real names/UUIDs in output |
| 8a | Granular privacy: `PlayerCensus` fields, codec/backup schema, `PlayerDeaths` propagation, `DeathRedactor` (§7 core) | 1 | Old-save and old-backup defaults; S8 totals unchanged |
| 8b | `/dc privacy` command + GUI privacy screen for the three new toggles (§7 UI) | 8a | Truth-table tests; lang parity; server-side re-validation of toggles |
| 9 | Demo-data command with double gate + generator (§8) | 8a | `DemoGateTest`/`DemoDataGeneratorTest`; backup-first; no Discord side effects |
| 10 | Vanilla statistics import (§9) | 8a | Parser fixtures; idempotency; lifetime/milestone accounting documented |
| 11 | Release prep: version bump to 1.3.0 in all four `gradle.properties`, `CHANGELOG.md`, `docs/DESIGN.md` addenda, `docs/updates.json`, `CURSEFORGE.md`/README text, JARs, Drive backup, then **stop for pen's gate** before tag/store submission | 1–10 | Full CI matrix (min + latest NeoForge), lang parity, jar-content check, dedicated-server smoke via RCON |

Notes:

- PR-1 deliberately lands first so every later command/UI gate is written through
  `PermissionBridge` once rather than edited twice.
- PR-8a is the schema-heavy one; 8b is separated so a GUI regression cannot block
  the storage/redaction work.
- PR-6 is the only one that touches multipart webhook code; if it proves risky, it
  can degrade to content-only plus a local HTML path without affecting other PRs.
- No PR changes death capture semantics (`DeathTracker` record construction) except
  the read-only flag hand-off in 8a/8b; this keeps the save format changes additive.

## 12. Open questions and recommended defaults

Each question lists the realistic options and the **recommended default** this spec
assumes. Implementation proceeds on the default unless a human overrides it.

1. **Retry jitter?** Fixed backoff vs ±10% jitter. **Default: fixed (no jitter)** —
   deterministic tests, adequate for a single-server webhook.
2. **429 `retry_after` JSON body?** Header only vs also parse body. **Default:
   header only** in 1.3.0; add body parsing later if Discord is observed to omit
   the header.
3. **Retry budget?** 4 attempts / 1 s base vs 3 / 500 ms. **Default: 4 attempts,
   1000 ms base, 60 s `Retry-After` cap.**
4. **Batch window/cap?** Immediate, 1.5 s/10, 3 s/25. **Default: 1500 ms and 10
   deaths**, with `window=0` as an escape hatch to today's behavior.
5. **Single death in a batch window?** Send header anyway vs unchanged line.
   **Default: unchanged single line** (no visible change for normal play).
6. **Unflushed batch on shutdown?** Flush briefly vs drop. **Default: drop + log
   count**, matching today's `shutdownNow()` semantics.
7. **PvP definition?** `causeCategory == "player"` vs `killerType == "minecraft:player"`.
   **Default: category**, because it covers player-attributed explosions/projectiles
   and is version-independent.
8. **PvP filter option naming?** `all|pvp_only|off` vs two booleans. **Default: one
   enum** with those three values (`off` = suppress PvP deaths).
9. **Invalid digest webhook URL?** Fall back to primary vs disable digest.
   **Default: disable digest channel** so a typo is visible in `/dc doctor`.
10. **Season period syntax?** Two ISO dates, period preset, or two epochs.
    **Default: two `yyyy-MM-dd` words with optional `epoch:<millis>` form** —
    arbitrary ranges, no spaces, easy to parse and test.
11. **Season HTML delivery?** Multipart attachment, plain path only, or inline
    text. **Default: multipart attachment when `[html]` is given**, 1 MB cap,
    content-only fallback plus local path in chat.
12. **Season Embed?** Embed array vs plain content only. **Default: one simple
    embed** for the season post; all other posts keep the existing content-only
    payload.
13. **"Last words best 3" rule?** Most F's, longest text, newest. **Default:
    `fCount` desc then epoch desc**, labelled in the lang text so it is not mistaken
    for a quality ranking.
14. **Anonymized pseudonyms?** `Player A/B` sorted by UUID vs hashed names.
    **Default: `Player A/B…` by UUID order** (stable across formats, as the ideas
    file requests).
15. **Coordinate blur granularity?** 64, 100 or 256 blocks. **Default: 64** (round
    to nearest 64) as a middle ground between usefulness and privacy.
16. **Scrub names inside free text?** Yes vs only dedicated name fields.
    **Default: yes** — word-boundary, longest-name-first replacement in death
    messages, excuses and last words, because free text otherwise leaks names.
17. **`discordOptOut` scope?** Realtime only vs all Discord including digests.
    **Default: realtime only**; digest anonymous totals are unchanged and the
    player is still named in digest rankings unless `optOutPublic` is also set.
18. **Redact coords/opponent for self and admins?** Yes vs public-only.
    **Default: public-only** (self and admins keep full detail), matching the
    existing `UiPrivacy.canViewDetail` rule.
19. **GUI privacy toggles in 1.3.0?** Ship with core vs defer to a later GUI PR.
    **Default: ship in PR-8b**, after storage/redaction, so the feature is usable
    without opening a dialog/chat menu.
20. **Demo-data gate?** One config flag vs config + JVM property. **Default: both
    required** (clearly gated, as requested) with a loud log line.
21. **Vanilla baseline scope?** Lifetime counter + diagnostics vs synthetic death
    records. **Default: counter + diagnostics only** — no fabricated records, no
    history/export changes.
22. **Vanilla stat shape verification?** Trust the ideas file vs verify against
    local artifacts before coding. **Default: verify the exact nested key path
    against a local 1.21.1/26.x world stat file first**; keep the parser tolerant
    either way.
23. **Permission nodes now?** Implement LuckPerms soft dependency vs defer.
    **Default: defer** (platform has no built-in node API; infra phase owns it),
    with the `PermissionBridge` seam and explicit doctor/status note in 1.3.0.
24. **`permissionMode` config?** Add now vs later. **Default: later** — avoid a
    dead config value until a node provider actually exists.

## 13. Explicitly out of scope for 1.3.0 (from ideas §7.2)

SVG dashboard graphs, world 2-D death scatter, death-certificate cards,
"copy Discord Markdown" buttons, streamer overlay output, and the read-only
HTTP/JSON API/map integration are **not** in this pack. They remain in the
SMP/analysis and infra phases of `ROADMAP_POST_1.1.0.md`. Permission nodes (§10)
are likewise deferred with a documented seam rather than partially implemented.

