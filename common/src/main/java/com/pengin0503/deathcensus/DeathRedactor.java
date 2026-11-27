package com.pengin0503.deathcensus;

import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
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
        return apply(entry, privacy, viewerIsSelfOrAdmin, HiddenIdentities.empty());
    }

    /**
     * As {@link #apply(DeathEntry, PlayerPrivacy, boolean)}, additionally
     * scrubbing every snapshot-wide hidden identity from the four personal text
     * fields (review S3). Self/admin viewers are never scrubbed.
     */
    public static DeathEntry apply(DeathEntry entry, PlayerPrivacy privacy, boolean viewerIsSelfOrAdmin,
                                   HiddenIdentities hidden) {
        if (entry == null) {
            return null;
        }
        PlayerPrivacy effective = privacy == null ? PlayerPrivacy.none() : privacy;
        if (viewerIsSelfOrAdmin) {
            return entry;
        }
        HiddenIdentities secrets = hidden == null ? HiddenIdentities.empty() : hidden;
        if (!secrets.isEmpty()) {
            entry = secrets.scrub(entry);
        }
        if (!effective.hideCoordinates() && !effective.hidePvpOpponent()) {
            return entry;
        }
        String killerName = effective.hidePvpOpponent() ? "" : entry.killerName();
        DeathContext context = contextView(entry, effective, false);
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
                context);
    }

    /**
     * Render-time context composition (DESIGN 1.4.0 §11): biome is location
     * derived, so a public viewer with {@code hideCoordinates} sees the existing
     * hidden marker instead. Registry ids for items/effects and the weather word
     * are not personal identifiers and stay visible. Self/admin see the truth;
     * the stored record is never modified.
     */
    public static DeathContext contextView(DeathEntry entry, PlayerPrivacy privacy,
                                           boolean viewerIsSelfOrAdmin) {
        if (entry == null) {
            return DeathContext.EMPTY;
        }
        DeathContext context = entry.context() == null ? DeathContext.EMPTY : entry.context();
        PlayerPrivacy effective = privacy == null ? PlayerPrivacy.none() : privacy;
        if (viewerIsSelfOrAdmin || !effective.hideCoordinates() || context.biome().isEmpty()) {
            return context;
        }
        return context.withBiome(COORDINATE_MARKER);
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
        return publicView(player, HiddenIdentities.empty());
    }

    /**
     * As above, with the snapshot-wide hidden-identity dictionary (S3). The
     * dictionary is always built from the FULL snapshot; {@code player} may be a
     * filtered subset of it.
     */
    public static PlayerDeaths publicView(PlayerDeaths player, HiddenIdentities hidden) {
        if (player == null) {
            return null;
        }
        PlayerPrivacy privacy = PlayerPrivacy.of(player);
        List<DeathEntry> redacted = new ArrayList<>(player.deaths().size());
        for (DeathEntry death : player.deaths()) {
            redacted.add(apply(death, privacy, false, hidden));
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
        return publicView(players, null);
    }

    /**
     * Redacted copies of {@code players} using hidden identities derived from
     * {@code fullSnapshot} (S3). Callers rendering a public projection pass the
     * full live snapshot so an opted-out player cannot be named in another
     * player's record. Anonymous totals are never computed from these copies.
     */
    public static List<PlayerDeaths> publicView(List<PlayerDeaths> players,
                                                Collection<PlayerDeaths> fullSnapshot) {
        if (players == null || players.isEmpty()) {
            return List.of();
        }
        HiddenIdentities hidden = HiddenIdentities.of(fullSnapshot == null ? players : fullSnapshot);
        List<PlayerDeaths> result = new ArrayList<>(players.size());
        for (PlayerDeaths player : players) {
            if (player != null) {
                result.add(publicView(player, hidden));
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
                + "(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder(text.length() + 16);
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(""));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Public seam for the opponent-name scrub reused by derived tables such as
     * nemesis (DESIGN 1.4.0 §4). It applies exactly the same Unicode-safe word
     * boundary helper as {@link #apply} uses for {@code hidePvpOpponent}; the
     * caller decides whether to substitute a lang-keyed anonymous marker for
     * the removed name. Self/admin callers never reach this method.
     */
    public static String scrubOpponent(String text, String name) {
        return name == null ? (text == null ? "" : text) : scrub(text, name);
    }
}
