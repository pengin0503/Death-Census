package com.pengin0503.deathcensus;

import java.util.UUID;

/**
 * Per-player privacy flags carried from version storage into the common model
 * (DESIGN 1.3.0 §7). All granular flags are subordinate to {@code optOutPublic}:
 * they never imply it, and clearing it never clears them.
 */
public record PlayerPrivacy(
        boolean optOutPublic,
        boolean hideCoordinates,
        boolean hidePvpOpponent,
        boolean discordOptOut
) {
    /** The four user-facing privacy settings; names are the command/UI token contract. */
    public enum Flag {
        FULL("full"),
        COORDS("coords"),
        OPPONENT("opponent"),
        DISCORD("discord");

        private final String token;

        Flag(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        /** Accepts the canonical tokens plus the longer field names used by the GUI inputs. */
        public static Flag parse(String raw) {
            if (raw == null) {
                return null;
            }
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "full", "optout", "public" -> FULL;
                case "coords", "coordinate", "coordinates", "hide_coords", "hidecoordinates" -> COORDS;
                case "opponent", "pvp", "hide_opponent", "hidepvpopponent" -> OPPONENT;
                case "discord", "discord_optout", "discordoptout" -> DISCORD;
                default -> null;
            };
        }

        /** Bounded input key used by the 1.2.0 GUI and the hidden chat-UI subtree. */
        public String inputKey() {
            return switch (this) {
                case FULL -> "in_optout";
                case COORDS -> "in_hide_coords";
                case OPPONENT -> "in_hide_opponent";
                case DISCORD -> "in_discord_optout";
            };
        }
    }

    /** Parses the {@code on|off} argument of {@code /dc privacy} (also accepts true/false/1/0). */
    public static Boolean parseState(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "on", "true", "1", "yes", "enable", "enabled" -> Boolean.TRUE;
            case "off", "false", "0", "no", "disable", "disabled" -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
     * Permission matrix shared by commands and GUI adapters: a viewer may always
     * read/change their own flags; another player requires the PRIVACY_OTHER
     * decision (vanilla fallback OP level 2 in 1.3.0).
     */
    public static boolean canAccess(UUID viewer, UUID target, boolean otherAllowed) {
        return target != null && (otherAllowed || (viewer != null && viewer.equals(target)));
    }

    public static PlayerPrivacy none() {
        return new PlayerPrivacy(false, false, false, false);
    }

    /** Returns a copy with one setting changed; the other three are never implied or cleared. */
    public static PlayerPrivacy withFlag(PlayerPrivacy current, Flag flag, boolean value) {
        PlayerPrivacy base = current == null ? none() : current;
        if (flag == null) {
            return base;
        }
        return switch (flag) {
            case FULL -> new PlayerPrivacy(value, base.hideCoordinates, base.hidePvpOpponent, base.discordOptOut);
            case COORDS -> new PlayerPrivacy(base.optOutPublic, value, base.hidePvpOpponent, base.discordOptOut);
            case OPPONENT -> new PlayerPrivacy(base.optOutPublic, base.hideCoordinates, value, base.discordOptOut);
            case DISCORD -> new PlayerPrivacy(base.optOutPublic, base.hideCoordinates, base.hidePvpOpponent, value);
        };
    }

    public static PlayerPrivacy of(PlayerDeaths player) {
        return player == null ? none() : new PlayerPrivacy(
                player.optOutPublic(),
                player.hideCoordinates(),
                player.hidePvpOpponent(),
                player.discordOptOut());
    }

    /** The master switch: this player is hidden from public listings and exports. */
    public boolean hidesPublicIdentity() {
        return optOutPublic;
    }

    /** Realtime Discord posts are suppressed for a full opt-out or a Discord-only opt-out. */
    public boolean suppressesRealtimeDiscord() {
        return optOutPublic || discordOptOut;
    }
}
