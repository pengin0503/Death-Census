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
variable with `env:VAR_NAME` and is never printed to logs or chat.

## Versions

NeoForge for Minecraft **1.21.1**, **26.1.2**, **26.2** and **26.3**.
Download the file matching your Minecraft version. Server-side only —
vanilla clients can join.

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
  経由するようになりましたが、1.3.0 では従来どおりバニラの権限レベル2を
  使用し、動作は変わりません

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
コマンドは `/deathcensus`（短縮形 `/dc`）です。
