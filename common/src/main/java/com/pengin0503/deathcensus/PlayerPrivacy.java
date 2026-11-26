package com.pengin0503.deathcensus;

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
    public static PlayerPrivacy none() {
        return new PlayerPrivacy(false, false, false, false);
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
