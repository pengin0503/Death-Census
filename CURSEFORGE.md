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

対象は NeoForge の Minecraft **1.21.1 / 26.1.2 / 26.2 / 26.3** です。
コマンドは `/deathcensus`（短縮形 `/dc`）です。
