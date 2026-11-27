# Death Census

**Server-side death records, cause statistics and leaderboards for NeoForge.**

Death Census records every player death on your server — the cause, the
place, the moment, and how long the player had survived — and turns that
history into something the whole community can enjoy: personal death
timelines, server-wide leaderboards, and cause-of-death statistics.

It runs **entirely on the server**. Players join with a plain vanilla
client; nothing needs to be installed on the client side.

## Features

- **Permanent per-player death history**: cause (the vanilla death message),
  dimension, coordinates, in-game day, real date/time, playtime at death and
  survival time since the previous death
- **Leaderboards**: most deaths, longest survival, fastest death, rarest cause
- **Cause-of-death statistics** for the whole server (`/dc stats server`)
- **Personal commands** to browse your own census and compare with others
- **Discord webhook notifications** for each death, plus an optional
  periodic digest
- **Exports**: standalone HTML summary (no JavaScript), JSON and CSV
- **Privacy controls**: per-player opt-out of public listings (`/dc optout`)
- **Admin tools**: lookup any player's history, JSON backup and restore
- **English and Japanese** localization
- **Last words and excuses**: a death records the player's most recent chat
  message (within 30 seconds) as their last words; `/dc excuse <text>`
  attaches a one-line excuse to the latest death, and both appear in
  history and HTML/JSON/CSV exports
- **F respects**: typing `F` (or `f`) in chat within 30 seconds of another
  player's death adds a respect count to that death (once per sender)
- **Milestone announcements**: the first server death, a player's first
  death, every 100th death and completing all 17 cause categories are
  announced in chat and on Discord (`milestoneAnnouncements`, default on;
  counting uses lifetime totals)
- **Period rankings**: `/dc leaderboard <type> [today|7d|30d|all] [page]`
  and `/dc stats server [period]`
- **History filters**: `/dc history` and `/dc lookup` take a trailing filter
  — a cause category or an exact dimension id
- **Operator tools** (permission level 2): `/dc delete <player> <#>`,
  `/dc reset player <name>`, `/dc reset all confirm` and `/dc doctor`;
  delete and reset write a safety backup first and abort if it fails
- **Discord delivery**: webhook sends retry temporary failures and HTTP 429
  rate limits (`Retry-After`) with exponential backoff on a bounded queue,
  and bursts of deaths are sent as one combined message
- **Discord channels and filters**: the digest can use its own webhook URL
  (`discordDigestWebhookUrl`; `/dc discordtest digest` tests it), and
  realtime posts can be limited by dimension allow-list and PvP mode;
  digests still count every death
- **Season digest**: `/dc digest season <from> <to> [html]` posts a summary of
  any date range to the digest webhook, with an optional standalone HTML
  report attached
- **Anonymized exports**: `/dc export html|json|csv [player] [--anonymize]`
  writes a shareable file with `Player A/B/...` pseudonyms, rounded
  coordinates and player names scrubbed from messages
- **Granular privacy**: `/dc privacy <coords|opponent|discord> <on|off>` and
  the in-game privacy screen let players hide coordinates, the PvP opponent
  or realtime Discord posts without hiding their whole history; anonymous
  totals stay unchanged
- **Dev and vanilla import commands**: `/dc dev demo <count>` (1–500) needs
  both `devMode` and `-Ddeathcensus.dev=true` and writes a backup first;
  `/dc import vanilla [dry-run]` adds pre-install vanilla death totals as a
  lifetime baseline for milestones without inventing history records
- **Permission seam**: admin checks route through a bridge with node names
  reserved for a future provider, but still use vanilla OP level 2 exactly
  as before

## 1.4.0 additions

New public commands: `/dc witnesses`, `/dc awards`, `/dc chronicle`,
`/dc nemesis`, `/dc hotspots`, `/dc distribution`, `/dc loops`,
`/dc neardeath` and `/dc compare <playerA> <playerB> [period] [--html]`.
`/dc help` lists the enabled commands, and the GUI Home → Rankings screen
reaches every new ranking plus the two-column compare view.

- Witnesses and death context (equipment, effects, biome, weather) are
  captured at death time and redacted for public viewers with the existing
  privacy flags; old saves and backups still load.
- Awards, chronicle, nemesis, hotspots, distributions and loops are derived
  from retained records — no persisted winners or loop records.
- A self-only respawn guide points back to your dropped items; near-death
  (totem/cancel) saves are capped per player and server-wide.
- HTML/JSON/CSV exports include the same 1.4.0 sections in the same order;
  `/dc compare ... --html` writes a standalone comparison report.

New config keys (defaults): `captureWitnesses` (true), `witnessRadiusBlocks`
(24), `witnessMaxPerDeath` (8), `witnessesInDiscord` (false);
`captureDeathContext` (true), `deathContextMaxEffects` (8),
`deathContextInDiscord` (false), `deathContextInExports` (true);
`awardsEnabled` (true), `awardsDefaultPeriod` (all), `awardsInDigest` (true),
`awardsMinCount` (1); `chronicleEnabled` (true), `chronicleMaxEvents` (200),
`chronicleInExport` (true), `chronicleInDiscord` (false); `nemesisEnabled`
(true), `nemesisMinMobCount` (3), `nemesisMinNamedCount` (2),
`nemesisInDigest` (false); `hotspotsEnabled` (true), `hotspotMinDeaths` (3),
`hotspotChunkSize` (16), `hotspotsInDigest` (false); `compareEnabled` (true),
`compareDefaultPeriod` (all), `compareInDiscord` (false);
`distributionsEnabled` (true), `distributionTimeBinHours` (2),
`distributionYBandBlocks` (16), `distributionsInDigest` (false);
`deathLoopEnabled` (true), `deathLoopMinDeaths` (3),
`deathLoopWindowMinutes` (10), `deathLoopRadiusBlocks` (48),
`deathLoopNotifyVictim` (true), `loopsInExport` (false), `loopsInDiscord`
(false); `respawnGuideEnabled` (true), `respawnGuideMaxTicks` (6000),
`respawnGuideArrivalBlocks` (16), `respawnGuideRefreshTicks` (20);
`nearDeathCaptureEnabled` (true), `nearDeathDedupWindowMillis` (2000),
`nearDeathMaxPerPlayer` (50), `nearDeathInDiscord` (false). `/dc doctor`
shows the feature/seam states and aggregate counts, never the webhook URL.

## Commands

All commands work under `/deathcensus` and the short alias `/dc`.

| Command | Description |
|---|---|
| `/dc history [player] [page]` | Death history, newest first |
| `/dc stats` | Your own death statistics |
| `/dc stats server` | Server-wide cause statistics |
| `/dc leaderboard deaths\|survival\|fastest\|rarest` | Leaderboards |
| `/dc optout` / `/dc optin` | Hide / show yourself in public listings |
| `/dc lookup <player>` | (OP) Any player's history, ignoring opt-out |
| `/dc export html\|json\|csv [player]` | (OP) Write an export file |
| `/dc backup` / `/dc restore <file>` | (OP) Back up / restore the data |
| `/dc discordtest` | (OP) Send a Discord test message |
| `/dc reload` | (OP) Re-check the digest schedule |

## In-game GUI (`/dc`)

Running `/dc` or `/deathcensus` with no arguments opens a menu — no client mod
is required:

- **26.1.2 / 26.2 / 26.3**: vanilla server-driven dialogs for Home, history and
  search, death detail, rankings, privacy and read-only operator shortcuts.
- **1.21.1**: the same views as clickable chat lines (the buttons run `/dc ui`
  commands); dialogs are not available on this version.

After a death is recorded, one extra compact line is sent with
`[Details] [Excuse] [F]` buttons for that specific death. The vanilla death
message is untouched. `[Details]`/`[Excuse]` are shown only to viewers allowed
to see the record; `[F]` reuses the existing one-per-sender respect dedupe.
Players who opted out receive their own death line, and operators can see it;
other viewers get no line, so a hidden record's existence, coordinates and
message are never disclosed. Opted-out names stay out of public history and
rankings, but their deaths still count in anonymous server totals.

`/dc help` keeps the original plain text help on every version.

## Configuration

The server config (`deathcensus-server.toml` / common config depending on
version) lets you toggle recording, cap the records kept per player
(default 10,000), set the Discord webhook URL and digest interval, choose
the display timezone, and control whether opted-out players appear in
operator exports. The webhook URL can also be read from an environment
variable with `env:VAR_NAME` and is never printed to logs or chat. The 1.4.0
keys listed above are grouped by feature in the same file; `/dc doctor`
reports each feature and seam as an on/off state.

## Versions

NeoForge for Minecraft **1.21.1**, **26.1.2**, **26.2** and **26.3**.
Download the file matching your Minecraft version. Server-side only —
vanilla clients can join. The current release is **1.4.0** on every line.

## 1.4.0 release changelog

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

---

# Death Census（日本語）

**サーバー側のみで動く、NeoForge 向け死亡記録・死因統計・ランキング Mod です。**

サーバー全員の死亡を、死因（バニラのデスメッセージ）・ディメンション・
座標・ゲーム内 Day・実日時・死亡時のプレイ時間・前回の死亡からの生存時間
つきで永続記録し、個人の死亡タイムライン、サーバー全体のランキング、
死因統計として楽しめるようにします。

**完全にサーバー側のみ**で動作します。参加するプレイヤーはバニラ
クライアントのままで OK です。

## 主な機能

- プレイヤーごとの全死亡履歴（死因、場所、Day、日時、生存時間）
- ランキング：最多死亡／最長生存／最速死亡／レア死因
- サーバー全体の死因統計（`/dc stats server`）
- Discord Webhook への死亡通知と定期ダイジェスト（任意）
- HTML（JS なし単体ファイル）/ JSON / CSV エクスポート
- プライバシー用オプトアウト（`/dc optout`）
- 管理者向け lookup、JSON バックアップと復元
- 英語・日本語対応
- 最期の言葉と言い訳：死亡の30秒前以内のチャットが最期の言葉として記録され、
  `/dc excuse <一言>` で直近の死亡に一言を追記できます（履歴と
  HTML/JSON/CSV エクスポートに表示）
- F への敬意：他のプレイヤーの死亡から30秒以内に `F`（または `f`）と
  チャットすると、その死亡に敬意のカウントが加わります（送信者ごとに1回）
- マイルストーン告知：サーバー初の死亡、プレイヤー初の死亡、100回ごとの死亡、
  全17死因カテゴリの達成をチャットと Discord で告知（設定
  `milestoneAnnouncements`、既定は有効、集計は累計）
- 期間別ランキング：`/dc leaderboard <type> [today|7d|30d|all] [page]` と
  `/dc stats server [period]`
- 履歴フィルター：`/dc history` と `/dc lookup` は末尾に死因カテゴリまたは
  ディメンションIDのフィルターを指定できます
- 管理者コマンド（権限レベル2）：`/dc delete <player> <#>`、
  `/dc reset player <name>`、`/dc reset all confirm`、`/dc doctor`。
  削除・リセットは安全なバックアップを先に作成し、失敗した場合は変更しません
- Discord 配信：Webhook の送信は一時的なエラーと HTTP 429（レート制限、
  `Retry-After` に従う）を指数バックオフで再試行し、短時間に多数の死亡が
  発生した場合は1通のメッセージにまとめて送信します
- Discord のチャンネルとフィルター：ダイジェスト専用の Webhook URL
  （`discordDigestWebhookUrl`、`/dc discordtest digest` で確認可）を設定でき、
  リアルタイム通知はディメンションの許可リストや PvP モードで絞り込めます。
  ダイジェストは引き続きすべての死亡を集計します
- シーズンダイジェスト：`/dc digest season <from> <to> [html]` で任意の期間の
  統計をダイジェスト用 Webhook に投稿できます（`html` で単体 HTML レポートを添付）
- 匿名エクスポート：`/dc export html|json|csv [player] [--anonymize]` は
  `Player A/B/...` のような仮名、座標の丸め、メッセージ内のプレイヤー名の
  除去を行った共有用ファイルを出力します
- 詳細なプライバシー設定：`/dc privacy <coords|opponent|discord> <on|off>` と
  ゲーム内プライバシー画面で、座標、PvP の相手、リアルタイム Discord 通知を
  個別に非表示にできます（匿名の合計値は変更されません）
- 開発用・バニラ統計インポートコマンド：`/dc dev demo <count>`（1〜500）は
  `devMode` と `-Ddeathcensus.dev=true` の両方が必要で、事前にバックアップを
  作成します。`/dc import vanilla [dry-run]` は Mod 導入前のバニラの死亡数を
  累計に追加し（`dry-run` では書き込みなし）、マイルストーンの集計に反映します
- 権限ノードの準備：管理者チェックは将来の権限プロバイダー用の橋渡しを
  経由するようになりましたが、バニラの権限レベル2は従来どおりで、動作は
  変わりません

## 1.4.0 の追加機能

新しい公開コマンド：`/dc witnesses`、`/dc awards`、`/dc chronicle`、
`/dc nemesis`、`/dc hotspots`、`/dc distribution`、`/dc loops`、
`/dc neardeath`、`/dc compare <playerA> <playerB> [period] [--html]`。
`/dc help` は有効なコマンドだけを表示し、GUI のホーム → ランキングから
すべての新しいランキングと2列の比較画面に移動できます。

- 証言者と死亡時の状況（装備、エフェクト、バイオーム、天候）は死亡時に記録され、
  公開画面では既存のプライバシー設定で隠されます。古いセーブデータや
  バックアップもそのまま読み込めます
- 称号、年代記、宿敵、ホットスポット、分布、死亡ループは保持された記録から
  導出され、過去の受賞履歴やループ記録は保存されません
- 死亡後、落としたアイテムへの自宅用の帰還ガイドを表示します。
  トーテム／ニアデス（瀕死）の保存数はプレイヤーごととサーバー全体で上限があります
- HTML/JSON/CSV エクスポートは同じ順番で 1.4.0 のセクションを含み、
  `/dc compare ... --html` は比較用の単体 HTML レポートを出力します

新しい設定キー（既定値）：`captureWitnesses` (true)、`witnessRadiusBlocks` (24)、
`witnessMaxPerDeath` (8)、`witnessesInDiscord` (false)、`captureDeathContext` (true)、
`deathContextMaxEffects` (8)、`deathContextInDiscord` (false)、
`deathContextInExports` (true)、`awardsEnabled` (true)、`awardsDefaultPeriod` (all)、
`awardsInDigest` (true)、`awardsMinCount` (1)、`chronicleEnabled` (true)、
`chronicleMaxEvents` (200)、`chronicleInExport` (true)、`chronicleInDiscord` (false)、
`nemesisEnabled` (true)、`nemesisMinMobCount` (3)、`nemesisMinNamedCount` (2)、
`nemesisInDigest` (false)、`hotspotsEnabled` (true)、`hotspotMinDeaths` (3)、
`hotspotChunkSize` (16)、`hotspotsInDigest` (false)、`compareEnabled` (true)、
`compareDefaultPeriod` (all)、`compareInDiscord` (false)、
`distributionsEnabled` (true)、`distributionTimeBinHours` (2)、
`distributionYBandBlocks` (16)、`distributionsInDigest` (false)、
`deathLoopEnabled` (true)、`deathLoopMinDeaths` (3)、
`deathLoopWindowMinutes` (10)、`deathLoopRadiusBlocks` (48)、
`deathLoopNotifyVictim` (true)、`loopsInExport` (false)、`loopsInDiscord` (false)、
`respawnGuideEnabled` (true)、`respawnGuideMaxTicks` (6000)、
`respawnGuideArrivalBlocks` (16)、`respawnGuideRefreshTicks` (20)、
`nearDeathCaptureEnabled` (true)、`nearDeathDedupWindowMillis` (2000)、
`nearDeathMaxPerPlayer` (50)、`nearDeathInDiscord` (false)。
`/dc doctor` は機能と接続ポイントの状態と集計値を表示しますが、Webhook URL は
表示しません。

## ゲーム内 GUI（`/dc`）

`/dc` または `/deathcensus` を引数なしで実行するとメニューが開きます。
クライアント側の Mod は不要です。

- **26.1.2 / 26.2 / 26.3**：バニラのサーバー駆動ダイアログでホーム、履歴と
  検索、死亡の詳細、ランキング、プライバシー、管理者用ショートカットを表示
- **1.21.1**：同じ画面をクリック可能なチャット行として表示（ボタンは
  `/dc ui` コマンドを実行）。このバージョンにはダイアログがありません

死亡が記録されると、その死亡を指す `[詳細] [言い訳] [F]` ボタン付きの
簡潔な行が追加で1行だけ送信されます。バニラのデスメッセージは変更され
ません。`[詳細]` と `[言い訳]` はその記録を閲覧できるプレイヤーにのみ
表示され、`[F]` は既存の「送信者ごとに1回」の敬意カウントを使います。
オプトアウト中のプレイヤーの死亡行は本人と管理者にだけ届き、他の
プレイヤーには存在・座標・メッセージも漏れません。オプトアウト中の
名前は公開履歴やランキングに出ませんが、匿名のサーバー合計には
数えられます。

`/dc help` はすべてのバージョンで従来のテキストヘルプのままです。

対象は NeoForge の Minecraft **1.21.1 / 26.1.2 / 26.2 / 26.3** です。
コマンドは `/deathcensus`（短縮形 `/dc`）です。現在のリリースは全ライン
共通で **1.4.0** です。

## 1.4.0 リリースノート

- 証言者：死亡時に近くにいたプレイヤーを上限つきで記録し、死亡の詳細と
  `/dc witnesses` のランキングに表示します。名前は既存のプライバシー設定に
  従って隠されます
- 死亡時の状況：装備、エフェクト、バイオーム、天候を記録して履歴と
  エクスポートに表示します（Discord への投稿は任意）。古いデータも
  そのまま読み込めます
- 称号：`/dc awards` は保持された記録と累計から受賞者を決めます
  （死亡数、落下、溶岩、雷、敬意、最も珍しい死因など）。最低件数は設定可能で、
  受賞履歴は保存しません
- 年代記：`/dc chronicle` は初記録、エンダードラゴン、カテゴリ制覇、
  サーバー死亡数の節目を上限つきで閲覧できる年表です。エクスポートへの
  掲載や Discord への投稿は任意で、古いデータから復元した行には日付を
  でっち上げずその旨を表示します
- 宿敵とホットスポット：`/dc nemesis` はモブ、名前付きの宿敵、PvP の
  相手をランキングし、`/dc hotspots` は死亡が集中する場所を表示します。
  どちらも保持された記録から導出し、名前空間付きのディメンションIDを
  指定できます
- 分布：`/dc distribution` は時間帯と高度帯ごとの死亡数を表示し、
  それぞれの幅は設定で変更できます
- 死亡ループ：`/dc loops` は短い時間内に近い場所で繰り返す死亡を検出し、
  本人への通知とエクスポートへの掲載を任意で行います。
  永続化される記録はありません
- 帰還ガイド：死亡後、落としたアイテムまでの方角、距離、標高差と
  アイテムの消去までの時間を本人にだけ表示します。他のプレイヤー、
  Discord、エクスポートには出ません
- ニアデス記録：トーテムによる復活と死亡のキャンセルを `/dc neardeath` で
  確認できます（プレイヤーごととサーバー全体に上限あり）。ニアデス記録しか
  ないプレイヤーでも `/dc stats` が動作するようになりました
- 比較：`/dc compare <playerA> <playerB> [period] [--html]` で2人の記録を
  チャット、GUI、単体 HTML レポートで並べて表示します。片方だけの
  オプトアウトも尊重します
- ヘルプ・GUI・doctor：`/dc help` は有効な 1.4.0 コマンドをすべて表示し、
  新しい画面はホーム → ランキングから移動できます。`/dc doctor` は
  機能と接続ポイントの状態、集計値を表示しますが、Webhook URL は
  表示しません
- レビュー修正：1.21.1 で `[F]` や言い訳を付けたときに証言者と死亡時の状況が
  失われないようにしました。年代記の節目は保存済みのサーバー死亡数を使い、
  最も珍しい死因の順位はチャット・GUI・エクスポート・ダイジェストで一致します。
  称号 GUI は設定した最低件数を守り、証言者や宿敵は名前ではなくデータで
  判定するため、「Anonymous」という名前のプレイヤーが誤って隠されません。
  キャンセルしたトーテム使用は保存されず、死亡ループの追跡と最低件数設定も
  検出側と一致するようになりました
