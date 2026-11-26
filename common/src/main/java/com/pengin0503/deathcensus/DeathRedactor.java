package com.pengin0503.deathcensus;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Render-time redaction for granular privacy flags (DESIGN 1.3.0 §7, §12 Q18).
 *
 * <p>This class only redacts fields; whole-player hiding (full opt-out) stays in
 * the visibility gate, and aggregate snapshots are never passed through here.
 * Self and admin viewers keep full detail. Discord is always a public viewer.
 */
public final class DeathRedactor {
    /** Visible stand-in for a hidden coordinate in history, exports and details. */
    public static final String COORDINATE_MARKER = "~";

    private DeathRedactor() {
    }

    /**
     * Returns a copy with hidden coordinates and/or opponent name when the
     * viewer is neither self nor admin and the matching flag is set. The input
     * record is immutable and is never modified; self/admin viewers get it back.
     */
    public static DeathEntry apply(DeathEntry entry, PlayerPrivacy privacy, boolean viewerIsSelfOrAdmin) {
        if (entry == null) {
            return null;
        }
        PlayerPrivacy effective = privacy == null ? PlayerPrivacy.none() : privacy;
        if (viewerIsSelfOrAdmin || (!effective.hideCoordinates() && !effective.hidePvpOpponent())) {
            return entry;
        }
        String killerName = effective.hidePvpOpponent() ? "" : entry.killerName();
        return new DeathEntry(
                entry.playerUuid(),
                entry.playerName(),
                entry.epochMillis(),
                entry.gameDay(),
                entry.playTicks(),
                entry.survivalTicks(),
                entry.dimension(),
                effective.hideCoordinates() ? Double.NaN : entry.x(),
                effective.hideCoordinates() ? Double.NaN : entry.y(),
                effective.hideCoordinates() ? Double.NaN : entry.z(),
                entry.causeCategory(),
                entry.causeId(),
                effective.hidePvpOpponent() ? scrub(entry.deathMessage(), killerNameOf(entry)) : entry.deathMessage(),
                entry.killerType(),
                killerName,
                entry.gameMode(),
                entry.experienceLevel(),
                entry.excuse(),
                entry.lastWords(),
                entry.fCount(),
                entry.witnesses(),
                entry.context());
    }

    /** True when the entry carries the NaN coordinate sentinel written by {@link #apply}. */
    public static boolean isCoordinateHidden(DeathEntry entry) {
        return entry != null
                && Double.isNaN(entry.x())
                && Double.isNaN(entry.y())
                && Double.isNaN(entry.z());
    }

    /** Redacted player copy for an ordinary public viewer (exports/history). */
    public static PlayerDeaths publicView(PlayerDeaths player) {
        if (player == null) {
            return null;
        }
        PlayerPrivacy privacy = PlayerPrivacy.of(player);
        List<DeathEntry> redacted = new ArrayList<>(player.deaths().size());
        for (DeathEntry death : player.deaths()) {
            redacted.add(apply(death, privacy, false));
        }
        return new PlayerDeaths(
                player.uuid(),
                player.name(),
                player.optOutPublic(),
                player.hideCoordinates(),
                player.hidePvpOpponent(),
                player.discordOptOut(),
                redacted);
    }

    /** Redacted copies of a whole snapshot; the input list and records are untouched. */
    public static List<PlayerDeaths> publicView(List<PlayerDeaths> players) {
        if (players == null || players.isEmpty()) {
            return List.of();
        }
        List<PlayerDeaths> result = new ArrayList<>(players.size());
        for (PlayerDeaths player : players) {
            if (player != null) {
                result.add(publicView(player));
            }
        }
        return result;
    }

    private static String killerNameOf(DeathEntry entry) {
        return entry.killerName() == null ? "" : entry.killerName();
    }

    /** Unicode-safe word-boundary scrub of an opponent name from free text. */
    private static String scrub(String text, String opponent) {
        if (text == null || text.isEmpty() || opponent.isBlank()) {
            return text == null ? "" : text;
        }
        Pattern pattern = Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(opponent)
                + "(?![\\p{L}\\p{N}_])");
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder(text.length() + 16);
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(""));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
