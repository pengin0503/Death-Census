package com.pengin0503.deathcensus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Snapshot-wide dictionary of identities that must not be named in a public
 * projection (review S3). It is built from the FULL live snapshot, not from the
 * filtered player list of one export: an opted-out player can still be named in
 * somebody else's death message, killer name, excuse or last words.
 *
 * <p>Death Census 1.2.0 has a single opt-out flag; there are no granular
 * privacy flags or anonymized export. Only fully opted-out players are hidden,
 * and only rendered copies are scrubbed — stored records and anonymous totals
 * are never changed. Self/admin viewers are never scrubbed by their callers.
 */
public final class HiddenIdentities {
    /** Stand-in used in free text so a scrubbed line stays readable. */
    public static final String HIDDEN_MARKER = "Anonymous";

    private static final HiddenIdentities EMPTY = new HiddenIdentities(null, Map.of());

    private final Pattern pattern;
    private final Map<String, String> replacements;

    private HiddenIdentities(Pattern pattern, Map<String, String> replacements) {
        this.pattern = pattern;
        this.replacements = replacements;
    }

    /** Empty dictionary; useful when no full snapshot is available. */
    public static HiddenIdentities empty() {
        return EMPTY;
    }

    /** Builds the dictionary from a full snapshot; null/empty yields {@link #empty()}. */
    public static HiddenIdentities of(Collection<PlayerDeaths> fullSnapshot) {
        if (fullSnapshot == null || fullSnapshot.isEmpty()) {
            return EMPTY;
        }
        Map<String, String> byLowerName = new LinkedHashMap<>();
        for (PlayerDeaths player : fullSnapshot) {
            if (player == null || !player.optOutPublic()) {
                continue;
            }
            register(byLowerName, player.name());
            register(byLowerName, player.uuid());
            for (DeathEntry death : player.deaths()) {
                if (death != null) {
                    register(byLowerName, death.playerName());
                }
            }
        }
        if (byLowerName.isEmpty()) {
            return EMPTY;
        }
        List<String> secrets = new ArrayList<>(byLowerName.keySet());
        // Longest first so "Alice, Jr." wins over "Alice"; alphabetical for
        // determinism. Word boundaries avoid partial matches.
        secrets.sort(Comparator.comparingInt((String s) -> -s.length()).thenComparing(s -> s));
        StringBuilder regex = new StringBuilder("(?<![\\p{L}\\p{N}_])(?:");
        for (int i = 0; i < secrets.size(); i++) {
            if (i > 0) {
                regex.append('|');
            }
            regex.append(Pattern.quote(secrets.get(i)));
        }
        regex.append(")(?![\\p{L}\\p{N}_])");
        Pattern pattern = Pattern.compile(regex.toString(),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return new HiddenIdentities(pattern, Map.copyOf(byLowerName));
    }

    private static void register(Map<String, String> byLowerName, String secret) {
        if (secret == null || secret.isBlank()) {
            return;
        }
        byLowerName.putIfAbsent(secret.toLowerCase(Locale.ROOT).trim(), HIDDEN_MARKER);
    }

    public boolean isEmpty() {
        return pattern == null;
    }

    /** Scrubs one free-text/name field; null stays an empty string. */
    public String scrub(String text) {
        if (text == null || text.isEmpty() || pattern == null) {
            return text == null ? "" : text;
        }
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder(text.length() + 16);
        while (matcher.find()) {
            String matched = matcher.group();
            String replacement = replacements.get(matched.toLowerCase(Locale.ROOT));
            if (replacement == null) {
                // Unicode case folding can produce a spelling that differs from
                // the lower-cased dictionary key; fall back to an equality test.
                for (Map.Entry<String, String> entry : replacements.entrySet()) {
                    if (entry.getKey().equalsIgnoreCase(matched)) {
                        replacement = entry.getValue();
                        break;
                    }
                }
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    replacement == null ? matched : replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** Scrubs the four personal text fields of an immutable death record. */
    public DeathEntry scrub(DeathEntry entry) {
        if (entry == null || pattern == null) {
            return entry;
        }
        return new DeathEntry(
                entry.playerUuid(),
                entry.playerName(),
                entry.epochMillis(),
                entry.gameDay(),
                entry.playTicks(),
                entry.survivalTicks(),
                entry.dimension(),
                entry.x(),
                entry.y(),
                entry.z(),
                entry.causeCategory(),
                entry.causeId(),
                scrub(entry.deathMessage()),
                entry.killerType(),
                scrub(entry.killerName()),
                entry.gameMode(),
                entry.experienceLevel(),
                scrub(entry.excuse()),
                scrub(entry.lastWords()),
                entry.fCount());
    }
}
