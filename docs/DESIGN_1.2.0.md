# Death Census 1.2.0 — GUI spec (research-backed; proceed on recommended defaults, 2026-11-26)

Base: main after 1.1.0 shipped. Server-side only, vanilla clients, MC 1.21.1 / 26.1.2 / 26.2 / 26.3. All *semantics* identical across the four versions; only the presentation adapter differs (26.x Dialog vs 1.21.1 interactive chat). en_us/ja_jp parity required for every new key (CensusLangTest enforces keys matching `command.deathcensus.*`). This spec follows the style of docs/DESIGN_1.1.0.md; where it conflicts with the Japanese product brief §2, the explicit scope decisions in §1 below win (options and reasons are given in §8).

## 0. API facts verified from local artifacts (not memory)

Tooling: `javap` from `/home/hatch/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2/bin/javap`. Artifacts inspected: `~/.gradle/caches/neoformruntime/artifacts/minecraft_26.{1.2,2,3}_client.jar` (named classes), `~/.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/26.3.0.76/*/neoforge-26.3.0.76-{universal,sources}.jar`, the merged moddev jars under `~/workspace/iml-work/versions/26.{1.2,2,3}/build/moddev/artifacts/`, and NeoForm `decompile_*_output.jar` sources. `unzip -l`/`unzip -p`, `javap -p/-c`, and source extraction were used; nothing below is recalled from documentation.

### 0.1 Dialog types (verified)

`net.minecraft.server.dialog.Dialog` is an **interface**; there are no builder classes in 26.x — use canonical record constructors / `MAP_CODEC`. Verified signature:

```
Codec<Integer> WIDTH_CODEC;                       // ExtraCodecs.intRange(1, 1024)
Codec<Dialog> DIRECT_CODEC;
Codec<Holder<Dialog>> CODEC;
Codec<HolderSet<Dialog>> LIST_CODEC;
StreamCodec<RegistryFriendlyByteBuf, Holder<Dialog>> STREAM_CODEC;
StreamCodec<ByteBuf, Dialog> CONTEXT_FREE_STREAM_CODEC;
CommonDialogData common(); MapCodec<? extends Dialog> codec(); Optional<Action> onCancel();
```

Registry ids registered by `net.minecraft.server.dialog.DialogTypes.bootstrap` (verified from bytecode string constants) and the exact records:

| id | class / constructor |
|---|---|
| `minecraft:notice` | `NoticeDialog(CommonDialogData common, ActionButton action)` (also `DEFAULT_ACTION`) |
| `minecraft:server_links` | `ServerLinksDialog(CommonDialogData common, Optional<ActionButton> exitAction, int columns, int buttonWidth)` |
| `minecraft:dialog_list` | `DialogListDialog(CommonDialogData common, HolderSet<Dialog> dialogs, Optional<ActionButton> exitAction, int columns, int buttonWidth)` |
| `minecraft:multi_action` | `MultiActionDialog(CommonDialogData common, List<ActionButton> actions, Optional<ActionButton> exitAction, int columns)` (+`actions()`, `exitAction()`, `columns()`) |
| `minecraft:confirmation` | `ConfirmationDialog(CommonDialogData common, ActionButton yesButton, ActionButton noButton)` |

Correction to brief §2.1: the id is **`server_links`**, not `server_list` (`Dialogs.SERVER_LINKS`, `data/minecraft/dialog/server_links.json`); use that spelling if the type is ever used. Subinterfaces: `SimpleDialog` (`List<ActionButton> mainActions()`), `ButtonListDialog` (`int columns()`, `Optional<ActionButton> exitAction()`, default `onCancel()`).

Shared data (verified):

```
CommonDialogData(Component title, Optional<Component> externalTitle, boolean canCloseWithEscape,
                 boolean pause, DialogAction afterAction, List<DialogBody> body, List<Input> inputs)
ActionButton(CommonButtonData button, Optional<Action> action)
CommonButtonData(Component label, Optional<Component> tooltip, int width)  // also (Component,int)
Input(String key, InputControl control)
```

Codec defaults: `can_close_with_escape=true`, `pause=true`, `after_action=close`, `body=[]`, `inputs=[]`; the codec rejects `pause && !afterAction.willUnpause()`.

### 0.2 Bodies, inputs, actions (verified)

- Bodies: `net.minecraft.server.dialog.body.DialogBody` (interface, `mapCodec()`, `DIALOG_BODY_CODEC`, `COMPACT_LIST_CODEC`); `PlainMessage(Component contents, int width)` (`minecraft:plain_message`); `ItemBody(ItemStackTemplate item, Optional<PlainMessage> description, boolean showDecorations, boolean showTooltip, int width, int height)` (`minecraft:item`).
- Inputs: `InputControl` interface; registrations `minecraft:text`, `minecraft:single_option`, `minecraft:boolean`, `minecraft:number_range`. Exact constructors:
  - `TextInput(int width, Component label, boolean labelVisible, String initial, int maxLength, Optional<TextInput.MultilineOptions> multiline)`; `MultilineOptions(Optional<Integer> maxLines, Optional<Integer> height)`.
  - `SingleOptionInput(int width, List<Entry> entries, Component label, boolean labelVisible)`; `Entry(String id, Optional<Component> display, boolean initial)`.
  - `BooleanInput(Component label, boolean initial, String onTrue, String onFalse)`.
- Actions: `net.minecraft.server.dialog.action.Action` — `MapCodec<? extends Action> codec()`, `Optional<ClickEvent> createAction(Map<String, Action.ValueGetter> parameters)`; `ValueGetter.asTag()`. `StaticAction(ClickEvent value)` wraps *all* static click events including `run_command` (no separate `RunCommand` action class); `CommandTemplate(ParsedTemplate)` = `minecraft:dynamic/run_command`; **`CustomAll(Identifier id, Optional<CompoundTag> additions)` = `minecraft:dynamic/custom`**. `CustomAll.createAction` copies `additions` into a `CompoundTag` and inserts every input value under its input key, then returns `new ClickEvent.Custom(id, Optional.of(tag))`.
- Input value tags verified from decompiled `InputControlHandlers`: text → `StringTag`, single_option → `StringTag(entry.id())`, boolean → `ByteTag`, number_range → `FloatTag`. Non-string types must be parsed defensively by our handler.
- `DialogAction` enum: `CLOSE("close")`, `NONE("none")`, `WAIT_FOR_RESPONSE("wait_for_response")`; `willUnpause()` true only for CLOSE and WAIT_FOR_RESPONSE. Decompiled `DialogScreen.runAction` maps `NONE -> this`, `CLOSE -> previousScreen`, `WAIT_FOR_RESPONSE -> new WaitingForResponseScreen(previousScreen)`. No server-side "response" completion callback was found; treat these as client screen-behaviour hints only.
- Click events: `ClickEvent.Custom(Identifier id, Optional<Tag> payload)`; `ClickEvent.RunCommand(String command)`; `ClickEvent.ShowDialog(Holder<Dialog> dialog)`. Verified `ClickEvent.Action.CUSTOM`, `SHOW_DIALOG`, `RUN_COMMAND` all have `allowFromServer=true`.

### 0.3 Sending / receiving (verified)

- Server → client: `net.minecraft.server.level.ServerPlayer#openDialog(Holder<Dialog>)`; body is literally `this.connection.send(new ClientboundShowDialogPacket(dialog));`. Packet: `net.minecraft.network.protocol.common.ClientboundShowDialogPacket(Holder<Dialog> dialog)` with `STREAM_CODEC` built on `Dialog.STREAM_CODEC`; clearing uses `ClientboundClearDialogPacket.INSTANCE`. There is **no** `showDialog`/`sendDialog` method and no `ClientboundShowDialog` class.
- Client → server: `net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket(Identifier id, Optional<Tag> payload)` (record, `handle(ServerCommonPacketListener)`), wire limits from source: length prefix 65,536 bytes and `NbtAccounter(32768L, 16)` (32 KiB NBT, depth 16).
- Receive hook: **`net.neoforged.neoforge.event.entity.player.CustomClickActionEvent`** (compiled javap + NeoForge sources), `extends Event implements ICancellableEvent`, with `getPlayer()` (**@Nullable ServerPlayer**, null during configuration), `getProfile()`, `getIdentifier()`, `getPayload()` (@Nullable Tag). Source docs: fired on the logical server for a custom click from a sign, dialog box or screen; handlers that act **must cancel** the event. NeoForge patch chain: `ServerCommonPacketListenerImpl.handleCustomClickAction` → `MinecraftServer.handleCustomClickAction` → `CommonHooks.onCustomClickAction` → `NeoForge.EVENT_BUS.post(new CustomClickActionEvent(...))`. No dialog-specific receive event exists in these jars.
- Cross-version: a 600-line concatenated `javap -p` dump of every `net.minecraft.server.dialog.*` class plus `ClientboundShowDialogPacket`, `ServerboundCustomClickActionPacket`, `ServerPlayer`'s dialog methods and `CustomClickActionEvent` is **byte-identical** (same SHA-256) for 26.1.2.71, 26.2.0.57 and 26.3.0.76. No signature differences were found.

### 0.4 1.21.1 (verified absence) and current code facts

- `unzip -l minecraft_1.21.1_{client,server}.jar | grep -i dialog` → 0 entries; `neoforge-21.1.1-*.jar` has no `CustomClickActionEvent`. Deobfuscated 1.21.1 `ClickEvent` has only `open_url`, `open_file`, `run_command`, `suggest_command`, `change_page`, `copy_to_clipboard` (no `CUSTOM`, no `SHOW_DIALOG`); `Style.withClickEvent(@Nullable ClickEvent)` / `withHoverEvent(...)` exist (no `Style.CLICK` constant in either 1.21.1 or 26.3).
- The repo currently has **zero** `ClickEvent`/`HoverEvent`/`Style` usage. All command output goes through `CensusCommands.send/sendLiteral/fail` and `ServerLang.tr(player, key, args)`; lines are `Component.literal` strings. `ServerLang` (identical in all four trees) exposes `tr(String,Object...)`, `tr(ServerPlayer,...)`, `tr(CommandSourceStack,...)`, `trForLang(String,...)`, `formatForLang(String,...)`, loads only `en_us`/`ja_jp` from `common/resources/assets/deathcensus/lang/`, and falls back to en_us. `CensusLangTest` asserts key-set parity and that every `(command|discord).deathcensus.*` literal used in source exists in en_us.
- `/dc` and `/deathcensus` currently both execute `CensusCommands.help` (`CensusCommands.java:39-54`); `/dc` redirects to the `deathcensus` root. `PAGE_SIZE=10`; `Pagination.of(...)`, `CensusStats.filterSince`, `causeCounts(...,boolean)`, `leaderboardDeaths/Survival/Fastest(...)` and `rarestCause(...,boolean)` already implement the required filtering/ranking semantics in pure Java. `CensusStats` callers pass `includeOptOut=false` for rankings and `true` for anonymous totals (DESIGN §8 / 1.1.0 PR-3). `CensusData.setExcuse(UUID,String)` and `addRespect(UUID,long)` exist; `DeathTracker` owns the transient one-respect-per-sender-per-death set. There is no mod-generated death notification line today (the vanilla death message is untouched); `DeathTracker` only broadcasts milestones.

### 0.5 UNVERIFIED (explicitly)

- Runtime behaviour: dialog flicker, `after_action` completion, and whether Quick Actions registration works when a mod contributes `data/minecraft/dialog/*` + `data/minecraft/tags/dialog/quick_actions.json` / `pause_screen_additions.json` (the vanilla tag JSONs were verified to be empty and the builtin dialog JSONs present, but no server was launched). Smoke-test required.
- No explicit clientbound *dialog* packet size cap was found (UNVERIFIED). Verified caps are for custom payloads (1 MiB) and serverbound custom click actions (64 KiB length prefix / 32 KiB NBT).
- Whether `Holder.direct(dialog)` takes the inline path on every network codec variant in practice (source/codec inspection suggests yes; `Dialog.CONTEXT_FREE_STREAM_CODEC` maps `Holder::value`/`Holder::direct`). Smoke-test required.
- No runtime verification of cancellation order with other mods' `CustomClickActionEvent` listeners.

## 1. Goals / non-goals

Goals:
1. Give 1.2.0 a **vanilla-client, server-side-only GUI**: on 26.x use the vanilla server-driven Dialog system; on 1.21.1 use interactive chat rendering the *same* common view models. No chest GUI, no client mod, no custom networking beyond vanilla-supported click events.
2. Cover the five screens in §2 plus a safe death-notification button line. Reuse 1.1.0 data, periods, filters, pagination, opt-out and backup-first delete — no new storage fields, no config schema changes.
3. Keep all selection/filter/permission/validation logic in pure-Java `common/` so both adapters are thin and unit-testable.

Non-goals (do not implement in 1.2.0):
- Chest/inventory GUIs, custom client screens, custom payloads to clients.
- Written Book / lectern UI (`/dc book`, brief §2.2) — separate feature, defer to 1.3.0.
- Awards/「身内アワード」screen, witnesses, hotspot/season features — no data exists; defer.
- Coordinate-only hiding and Discord-off toggles in the Privacy screen (brief §2.1) — these need new storage/config fields; keep the existing optout/optin boolean only.
- **Admin in-game settings screen (TOML/YAML hot reload) is OUT of 1.2.0 scope.** Recommendation: ship only a read-only "Admin tools" menu of shortcuts to existing commands (lookup/backup/restore/doctor/delete-via-detail); config editing has a much larger risk surface and the 26.3 config type already differs from 26.1/26.2. Revisit in 1.3.0.
- Adding or changing death records beyond the existing excuse/respect/delete/optout operations.

## 2. Screen inventory and navigation

All screens are stateless renders of a common view model plus a short-lived server-side session (§3.3). Every screen has a `[Back]`/`exit_action` returning to Home; `[Home]` is always available. `/dc help` keeps its current text-based output in all trees.

| Screen | Contents | Navigation / actions |
|---|---|---|
| Home | Title, own total deaths, **current survival time**, server total deaths (anonymous total), last death one-liner (visibility-filtered per §6; generic when the latest death is not viewable). Buttons in 2 columns: `[Personal stats] [Server stats] [Rankings] [History & search] [Privacy]`; OP also gets `[Admin tools]`. | Each button opens the corresponding screen via a custom action (26.x) or `/dc ui ...` (1.21.1). "Personal stats"/"Server stats" render the existing stats lines into a plain-message body; no separate stats screen is needed. |
| History list + filters | Inputs: period (`all/today/7d/30d`), category (`all` + `CauseClassifier.CATEGORIES` + dimensions present in data), text query (name/keyword). Body: up to 10 rows `#n · date · dimension (x,y,z) · message (+ excuse/last words/F)`, header `total, page/pageCount`, filter summary. | `[Search]` re-renders with inputs; `[◀ Prev] [Next ▶]` with clamped page; one button per row opens Death detail. Stale/empty results show a notice line. |
| Death detail | Date/time (display timezone), coordinate, dimension, cause/message, killer, survival time, XP, last words, excuse, F count; `item` body with a cause-symbol item (version-side mapping, tooltip). | `[Excuse…]` (self, selected death), `[F]` (respect), `[Back]`, OP `[Delete]` → confirmation dialog. Non-viewable records show a generic "not available" notice, never the record. |
| Rankings | Inputs: period + type (`deaths/survival/fastest/rarest`). Body: formatted top-10 table with rank, name, value, page header. Anonymous server cause totals shown as a footer line (opted-out deaths included, no names). | `[Apply]`, `[◀ Prev] [Next ▶]`, `[Back]`. Types map 1:1 to `CensusStats`. |
| Privacy | Current optout state, the existing optout.done explanation, one `boolean` input "Hidden from public listings (optout)", `[Save]`. | Save calls the existing `/dc optout`/`/dc optin` logic (`pc.optOutPublic` + setDirty) and re-renders a confirmation notice. No other toggles. |
| Admin tools (OP only) | Shortcut buttons: `[Lookup player] [Backup] [Restore] [Doctor]` plus `history <player>` shortcut. Read-only; no config editing. | `run_command` shortcuts to the existing commands (Restore is `suggest_command`/tab-completed file argument); `[Back]`. |
| Notice / feedback | One plain-message line (`after_action` choose close/back). | `[OK]` reopens Home or previous screen. Used for "session expired", "backup failed", "no deaths", etc. |

Entry points:
- **26.x**: `/dc` and `/deathcensus` with no args call the version UI facade (`openHome(player)`) instead of `CensusCommands.help`; subcommands are unchanged. `/dc help` remains text help. Optionally (stretch, PR-4) a Home dialog JSON + `data/minecraft/tags/dialog/quick_actions.json` / `pause_screen_additions.json` entry registers Home in vanilla Quick Actions / pause menu; UNVERIFIED in game, so `/dc` is the primary supported entry.
- **1.21.1**: `/dc` with no args prints the interactive chat menu — clickable `[Personal stats] [Server stats] [Rankings] [History]` (and `[Privacy]`, OP `[Admin tools]`) lines plus the same help text. No dialogs exist; every action is a clickable chat command.
- Death-notification buttons (all trees, PR-4): after recording, `DeathTracker` sends one compact extra system line per online viewer (vanilla death message is left untouched). Components: `[Details] [Excuse] [F]`; for 26.x these are `ClickEvent.Custom` actions, for 1.21.1 `ClickEvent.Action.RUN_COMMAND` actions (`/dc ui detail ...`, `/dc excuse <...>`, `/dc ui respect ...`). `[Details]`/`[Excuse]` are only shown to self / admins / viewers allowed by §6; `[F]` is shown to everyone who may see the record. For opted-out victims, only the victim and admins receive the line.

## 3. Common view-model design (`common/`, pure Java, Java 21 compatible)

New package `com.pengin0503.deathcensus.ui` under `common/src/main/java`. No Minecraft imports; all records are immutable/copying like the existing common classes. Text is represented as raw data + `UiText(key, Object[] args)` descriptors resolved by `ServerLang` in the version adapter, so both trees localize identically per viewer.

Records (final names are implementer's choice but keep these fields):
- `HomeView(int totalDeaths, int currentSurvivalTicks, int serverTotalDeaths, String lastDeathLine, boolean admin)` — `currentSurvivalTicks` computed version-side as current `PLAY_TIME - PlayerCensus.lastDeathPlayTicks` (or baseline if no death), so it is offline-safe; presented as play-time survival.
- `HistoryFilter(String period, String category, String dimension, String query)` with `normalize()` and `matches(DeathEntry)`; period handling mirrors `CensusCommands.sinceMillis`, category/dimension match mirrors `filterPredicate` (known category first, exact dimension id second). `query` matches player name/cause message case-insensitively, max 64 chars.
- `HistoryRow(long epochMillis, String targetUuid, String displayName, String displayLine)`; `HistoryView(List<HistoryRow> rows, int page, int pageCount, int total, HistoryFilter filter, String sessionToken)`.
- `DeathDetailView(String targetUuid, long epochMillis, String displayName, String dateTime, String dimension, int x, int y, int z, String causeCategory, String deathMessage, String lastWords, String excuse, int fCount, long survivalTicks, int experienceLevel, String killerName, boolean canEditExcuse, boolean canDelete)` — built from a `DeathEntry` plus viewer permissions; no raw record is ever sent to a client adapter without the visibility check.
- `RankingView(String type, String period, int page, int pageCount, List<String> rows, String anonymousTotalsLine)`.
- `PrivacyView(boolean optOut, UiText explanation)`.
- `NoticeView(UiText message, String backAction)` — session expiry, backup failure, missing record, etc.
- `ConfirmationView(UiText title, UiText body, UiActionRef yes, UiActionRef no)`.
- `UiActionRef(String action, Map<String,String> params)` and `UiRequest(UUID viewer, String action, String token, int page, String targetUuid, long deathEpoch, Map<String,String> inputs)`.
- `UiResult` (sealed/switchable): `Open(HomeView|HistoryView|DeathDetailView|RankingView|PrivacyView|ConfirmationView|NoticeView)`, `SideEffect`, `NoChange` — returned by the dispatcher to the adapter.
- `UiContext` interface implemented per version: data snapshot access, `isAdmin()`, `isSelf(UUID)`, `isOptedOut(UUID)`, `canViewDetail(...)`, `setExcuse(uuid, epoch, text)`, `payRespect(sender, victim, epoch)`, `deleteAfterBackup(uuid, epoch)`, `setOptOut(boolean)`, `zoneId()`, `sendNotice(UiText)`, `newSession(...)`, `touchSession(...)`. Keep it narrow; the 26.x and 1.21.1 implementations are the only Minecraft-touching pieces.
- `UiActions.handle(UiRequest, UiContext)` is the **single** dispatcher for both adapters. Common unit tests exercise it with a fake `UiContext`.

### 3.1 Action-id scheme for custom clicks

- Identifier namespace `deathcensus`, path `ui/<action>`: `deathcensus:ui/home`, `ui/history`, `ui/history_search`, `ui/history_page`, `ui/detail`, `ui/excuse_open`, `ui/excuse_save`, `ui/respect`, `ui/rankings`, `ui/ranking_page`, `ui/privacy`, `ui/privacy_save`, `ui/delete_ask`, `ui/delete_confirm`, `ui/admin`. Static admin shortcuts may use `StaticAction(new ClickEvent.RunCommand("/dc backup"))` instead.
- 26.x payload is a `CompoundTag` built by `CustomAll(id, Optional.of(additions))`:
  - `v: IntTag` schema version = 1; `a: StringTag` action path; `s: StringTag` session token; `p: IntTag` page (default 1); `t: StringTag` target UUID; `d: LongTag` death epochMillis; `f: StringTag` serialized filter (optional, consumed only from a session).
  - Dialog inputs use **prefixed** keys to avoid collisions with the fields above and to make types explicit: `in_period`, `in_category`, `in_dimension`, `in_query`, `in_excuse`, `in_optout`. Boolean inputs arrive as `ByteTag`, others as `StringTag`; parse with bounded helpers.
  - Target size < 1 KiB; no raw chat text or death message in a payload.
- 1.21.1 actions are Brigadier-typed commands using the same action names: `/dc ui home`, `/dc ui history [page]`, `/dc ui detail <uuid> <epoch>`, `/dc ui rankings <type> <period> [page]`, `/dc ui respect <uuid> <epoch>`, `/dc ui delete <uuid> <epoch> confirm`, `/dc ui privacy`, `/dc ui admin`; excuse entry reuses `/dc excuse <text...>` (extended to accept the selected death), privacy save reuses `/dc optout`/`/dc optin`. These are parsed into the same `UiRequest` and dispatched through `UiActions.handle`.

### 3.2 Validation and permission checks (server-side, always)

1. Ignore events whose identifier namespace is not `deathcensus`, action is unknown, or `getPlayer()` is null (configuration-phase clicks). Cancel the event **after** handling so vanilla logging is suppressed (`CustomClickActionEvent` is cancelable and the source docs require cancellation when acting).
2. Require `payload instanceof CompoundTag`, `v == 1`, known action; parse every field with bounds: page `1..10000`, period in `PERIODS`, category in `CauseClassifier.CATEGORIES ∪ {all}` or a dimension present in data, text ≤ 64 chars (excuse ≤ 100 chars), UUID parseable, epochMillis ≥ 0. Reject/ignore malformed input; never throw on a malicious payload.
3. Permissions are re-evaluated from the **live** server state, never from payload flags: admin actions require `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` (26.x) / `hasPermission(2)` (1.21.1) via `UiContext.isAdmin()`; excuse/respect/privacy changes require self (or admin for delete only). Run_command chat clicks on 1.21.1 execute as the player, so they are *not* a permission boundary.
4. Resolve the death by `(uuid, epochMillis)` in `CensusData` at execution time; never trust a payload-supplied index. Missing record → notice, no side effect.
5. Respect dedupe and one-backup-first semantics come from `CensusData.addRespect` / the PR-4 delete flow; do not reimplement them in an adapter.

### 3.3 Expiry / staleness / pagination state

- `UiContext` keeps `Map<UUID, UiSession>` where `UiSession(String token, long createdAtMillis, HistoryFilter filter, RankingSelection ranking, long dataVersion)`; TTL 5 minutes (recommended default). A new token is issued for every Home/History/Rankings render and rotated on navigation (`ui/history_page` consumes the old token and issues a new one); mutating actions consume and invalidate the token.
- Filter and ranking selection live **in the session**, not in the click payload; the payload carries only `s` + `p`. This prevents a forged filter from changing what a viewer sees and keeps chat links short.
- `dataVersion` is a server-side counter bumped on record/delete/excuse/respect/optout mutations. When a read action finds a changed version, re-resolve and re-render the same screen from current data (clamped page via `Pagination.of`) instead of showing stale rows. On token expiry/mismatch: no side effects; send the `ui.session_expired` key and render a fresh Home.
- Page numbers are always clamped server-side before display; page size stays 10.

## 4. 26.x Dialog adapter

Files (per 26.x tree): `versions/<v>/src/main/java/com/pengin0503/deathcensus/ui/DialogUi.java` (view → Dialog conversion + `openHome`), `DialogClickHandler.java` (`@SubscribeEvent` on `CustomClickActionEvent`), `DialogUiContext.java` (`UiContext` impl); wire the handler in `DeathCensus.java`, replace the no-arg branch in `CensusCommands.java`, and add a `CensusData.setExcuse(UUID,long,String)` overload and `DeathTracker.payRespect(...)` helper. The three 26.x copies are byte-identical except imports where required; verified APIs are identical across all three trees (§0.3).

Conversion rules:
- `CommonDialogData(ServerLang.tr(player, titleKey), Optional.empty(), true, false, DialogAction.WAIT_FOR_RESPONSE, bodies, inputs)`. `pause=false` keeps servers and singleplayer/LAN un-paused and sidesteps the codec validation; WAIT_FOR_RESPONSE is used consistently to avoid screen-flicker on navigation (runtime flicker UNVERIFIED). `NoticeDialog` may use `DialogAction.CLOSE`.
- Open with `player.openDialog(Holder.direct(dialog))` (verified `ServerPlayer.openDialog`; payload streamed by `ClientboundShowDialogPacket`). Do not cache `Holder<Dialog>` objects across players: all components are built per viewer through `ServerLang.tr(player, key, args)` so language and privacy are correct.
- Buttons: `new ActionButton(new CommonButtonData(label, Optional.of(tooltip), width), Optional.of(new CustomAll(new Identifier("deathcensus","ui/<action>"), Optional.of(payload))))`; use `StaticAction(new ClickEvent.RunCommand("/dc backup"))` only for the read-only admin shortcuts. `exitAction` = `[Back]` with `ui/home` (or `ui/detail` for Detail), 2 columns on Home/History/Rankings.
- Body lines use `PlainMessage(Component, width)`; Detail adds `ItemBody` with a cause-symbol `ItemStackTemplate` and tooltip. Cap body to 10 rows and truncate each display line to 80 chars (§9 risk mitigations).
- Inputs exactly as §2: History `in_period`/`in_category`/`in_dimension` = `SingleOptionInput`, `in_query` = `TextInput(maxLength=64)`; Rankings `in_period`/`in_type` = `SingleOptionInput`; Privacy `in_optout` = `BooleanInput(label, initial, "true", "false")`; excuse entry `in_excuse` = `TextInput(maxLength=100)` on the detail/excuse dialog. For "Search"/"Save" buttons, `CustomAll` injects all current input values into the payload automatically — parse by key and validate.
- Types per screen: Home/History/Detail/Rankings/Privacy/Admin/Notice → `MultiActionDialog`; delete → `ConfirmationDialog` (yes = `ui/delete_confirm`, no = `ui/detail`); unexpected feedback where no navigation is needed → `NoticeDialog` (`NoticeDialog.DEFAULT_ACTION` style custom OK). `DialogListDialog` and `ServerLinksDialog` are not used in 1.2.0 (verified but unnecessary); Quick Actions stretch uses vanilla's own `dialog_list` JSON mechanism, not our adapter.
- Death-notification buttons use `ClickEvent.Custom` with the same action/payload as dialog buttons (26.x), appended to a compact `ServerLang.tr(player, ...)` line; no `show_dialog` click event is required for buttons because the handler opens the dialog server-side.

## 5. 1.21.1 fallback design

Files: `versions/1.21.1/src/main/java/com/pengin0503/deathcensus/ui/ChatUi.java`, `ChatUiContext.java`, plus new `/dc ui ...` branches in `CensusCommands.java`; `DeathTracker.java` gets the same death-button line with `RUN_COMMAND` components. No Dialog classes are referenced (verified absent in 1.21.1). Add a small output helper (none exists today):

```
Component button(String label, String command) =
    Component.literal("[" + label + "]").withStyle(Style.EMPTY
        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, ServerLang.tr(player, "..."))));
```

Chat rendering uses the same `HomeView`/`HistoryView`/`DeathDetailView`/`RankingView`/`PrivacyView`: header + numbered lines, then button rows. `/dc ui history <page>` truly paginates (commands, not scrolling chat); filter inputs from the Dialog version are represented by short commands `/dc ui history filter <period> [category] [query]` stored in the session, with the same validation. Death detail prints the same fields as text lines (no item icon/tooltip). Privacy uses `/dc optout`/`/dc optin` buttons.

Explicitly degraded vs 26.x (acceptable for 1.2.0):
- No native forms: filters are command arguments, not dropdowns/text boxes; excuse entry uses the existing `/dc excuse <text...>` chat command.
- No item icons/tooltips in detail; no visual modal/confirmation screen (confirmation is the literal `confirm` argument in `/dc ui delete ...`, preceded by a warning line).
- No Quick Actions/pause-menu registration; `/dc` is the only entry point.
- Navigation buttons are chat lines that scroll away; history pages are limited to 10 rows, same as 26.x.
- Buttons are `run_command` and therefore visible/copyable in chat; tokens/UUIDs are not secret, and nothing security-relevant is inferred from them (§3.2).

## 6. Privacy (DESIGN §8) inside the GUI

- `canViewDetail(viewer, target)` = self OR admin OR `!optOutPublic`. The same predicate gates the `[Details]`/`[F]` buttons in the death line, history row buttons, lookup shortcuts and tab suggestions (the existing `CensusCommands.playerNames` filter already does this for commands).
- Opted-out players never appear by name in History (other viewers), Rankings, Admin tools' public lists, or the death line; clicking a stale link to them yields a generic "record not available" notice, with no confirmation that the player exists. The player always sees their own records; admins keep the existing `lookup` privileges.
- Anonymous totals (Home server total, `stats server` cause counts, Rankings footer) **include** opted-out players' deaths, per DESIGN §8; never attach a name to those counts. The Privacy screen repeats the existing optout.done explanation verbatim (via key), including the anonymous-totals caveat.
- The OP-only `[Delete]` button appears on Death detail only for admins; confirming reuses the PR-4 flow exactly: `CensusExport.writeBackup()` first (abort with a notice if it fails), then `CensusData.removeDeath(uuid, indexZeroBased)`, setDirty, confirm with player name + 1-based index + date, then re-render History from current data (Detail no longer exists). Index is resolved from the `(uuid, epoch)` identity at execution time, never from a payload index.

## 7. Phased PR plan (5 PRs; recommended defaults proceed without waiting)

**PR-1 — Common view models + action protocol (no user-visible change).**
Scope: §3 records, `UiActionRef`, `UiRequest`, `UiResult`, `UiContext`, `UiActions.handle`, validation/expiry helpers, session record. Files: `common/src/main/java/com/pengin0503/deathcensus/ui/*`, `common/src/test/java/com/pengin0503/deathcensus/ui/*Test.java`. Tests: pure JUnit on filter/period matching, page clamping, action id/payload validation, permission/visibility decisions with a fake `UiContext`, session expiry/rotation, anonymous-total inclusion; run in all four version test tasks via existing `sourceSets` wiring.

**PR-2 — 26.x Dialog adapter + five screens.**
Scope: §4 conversion, Home/History/Detail/Rankings/Privacy/Admin/Notice, `/dc` no-args opens Home, `CustomClickActionEvent` handler, excuse-by-epoch and respect helpers. Files: `versions/26.{1.2,2,3}/.../ui/DialogUi.java`, `DialogClickHandler.java`, `DialogUiContext.java`, `CensusCommands.java`, `DeathCensus.java`, `CensusData.java`, `DeathTracker.java`, new lang keys in `common/resources/.../lang/{en_us,ja_jp}.json`. Tests: common suite re-run; `CensusLangTest`; **in-game smoke only** for actual dialog rendering, input values, after_action, custom-click receipt and all three 26.x versions.

**PR-3 — 1.21.1 interactive-chat fallback.**
Scope: §5 button helper, `/dc ui ...` tree, chat rendering of the same view models, optout/excuse/respect/delete actions. Files: `versions/1.21.1/.../ui/ChatUi.java`, `ChatUiContext.java`, `CensusCommands.java`, `DeathCensus.java` (if needed), shared lang keys. Tests: JUnit for command parsing into `UiRequest` and common dispatch; `CensusLangTest`; smoke-test clickable chat on a 1.21.1 server with vanilla client.

**PR-4 — Death-notification buttons, privacy/delete hardening, Quick Actions stretch.**
Scope: per-viewer death line with `[Details] [Excuse] [F]` in all four trees (26.x custom clicks / 1.21.1 run_command), S8 gating, backup-first delete confirmation end-to-end, optional quick-actions/pause-menu dialog registration on 26.x (drop if smoke fails), README/docs notes. Files: all four `DeathTracker.java`, 26.x `DialogUi.java`, 1.21.1 `ChatUi.java`, shared lang JSON, `README.md`/`docs` (repo docs only in this PR, not by this spec writer). Tests: common suite + lang parity; **manual in-game matrix**: death line with public/opted-out victim, self vs other viewer vs admin, F dedupe, excuse save, delete with backup success/failure, `/dc` entry on every tree.

**PR-5 — Release prep.**
Scope: `gradle.properties` 1.1.0 → 1.2.0 in all four version projects, CHANGELOG.md 1.2.0 section, CURSEFORGE.md/README mentions, `updates.json` if it carries a version entry. Tests: build all four jars; STOP before submission — needs pen's explicit release go-ahead (same rule as 1.1.0).

## 8. Open questions (options + recommended default; project proceeds on the recommendation)

1. **Admin settings screen**: (a) full TOML/YAML editor with hot reload, (b) read-only Admin tools shortcuts, (c) nothing extra. **Default: (b)** — keeps config differences (26.3 type) out of 1.2.0.
2. **"Current survival days"**: (a) wall-clock since last death, (b) current `PLAY_TIME - lastDeathPlayTicks`. **Default: (b)**, labelled as play-time survival; offline time does not inflate it.
3. **Death notification buttons**: (a) modify vanilla death message, (b) one extra compact per-viewer line, (c) victim-only message. **Default: (b)**, with §6 gating; vanilla death message untouched.
4. **F button**: (a) send literal chat "F" (reuse `onServerChat`), (b) call `DeathTracker.payRespect` from the click handler. **Default: (b)** — same dedupe and `CensusData.addRespect`, no chat pollution.
5. **Excuse target**: (a) always the most recent death (current `/dc excuse`), (b) any selected death via new `(uuid, epoch)` overload. **Default: (b)** for GUI and `/dc excuse`, with the existing command still defaulting to the most recent death when no target is given.
6. **Awards screen / "身内アワード"**: (a) full screen, (b) fold milestone summaries into Home, (c) defer. **Default: (c)** — no stored award data; milestone announcements already shipped in 1.1.0.
7. **Written Book `/dc book`**: (a) ship in 1.2.0, (b) defer to 1.3.0. **Default: (b)** — separate UI/data-format feature.
8. **Quick Actions registration**: (a) required, with data-pack dialog + tag, (b) stretch in PR-4, (c) skip. **Default: (b)**; `/dc` remains the guaranteed entry; drop if in-game smoke fails.
9. **Session TTL**: (a) 2 min, (b) 5 min, (c) 15 min, (d) no expiry. **Default: (b)** with rotation on navigation and invalidation on mutation.
10. **`after_action` value**: (a) `CLOSE` everywhere, (b) `WAIT_FOR_RESPONSE` everywhere except notices, (c) `NONE`. **Default: (b)** with `pause=false`; re-check flicker in smoke and switch to CLOSE only if WAIT_FOR_RESPONSE strands players on a waiting screen.

## 9. Risks and mitigations

- **Dialog API differences across 26.1.2 / 26.2 / 26.3**: a 600-line javap dump is byte-identical across all three trees (§0.3), so one adapter source per tree is safe at compile time. Runtime registry/datapack behaviour is not verified; each PR-2/PR-4 change must be smoke-tested on all three versions. Keep all version-specific imports inside the adapter; never add a version check to common code. Note 26.3's config type differs (DESIGN §2) — another reason the admin config screen is out of scope.
- **Packet size / long histories**: clientbound dialog packet cap UNVERIFIED; verified serverbound custom-click limits are 64 KiB wire / 32 KiB NBT (depth 16). Mitigate by 10-row pages, 80-char line truncation, ≤64-char query, ≤100-char excuse, no death messages/NBT item data in click payloads, and payloads < 1 KiB. If a row would overflow, shorten the line rather than sending an oversized dialog.
- **Localization**: all user-visible strings, including button labels and tooltips, must come from `ServerLang.tr(player, key, args)` at render time (per-viewer language). New keys go into **both** en_us and ja_jp with identical key sets; use `command.deathcensus.ui.*` so CensusLangTest picks them up. Client language changes while a dialog is open will not retroactively re-render it — accept, or reopen with `/dc` (documented). `ItemBody` descriptions and `HoverEvent` text also need keys.
- **Privacy leaks**: opt-out names must not leak through row buttons, hover text, suggestion providers or death lines; anonymous totals must stay anonymous. Covered by the single `canViewDetail` predicate and unit tests (PR-1).
- **Custom-click trust**: payloads are client-controlled; everything is validated and re-resolved server-side (§3.2). Cancel the event only after successful handling so other mods still see unhandled clicks.
- **Vanilla client compatibility**: use only vanilla-supported click events (`CUSTOM`, `RUN_COMMAND`); no client mod, no custom payload channel. Mod resources must stay server-side (COMMON dist) — compile checks plus vanilla-client smoke test.

## 10. Out of scope for 1.2.0 (do not implement)

Dialog on 1.21.1, chest GUI, client mod, book/lectern UI, awards/bingo/hotspots/seasons, witnesses, coordinate-only hide or Discord-off privacy toggles, admin config hot-reload screen, storage migration, Fabric/Paper, new death-record fields, Discord retry/429 handling.
