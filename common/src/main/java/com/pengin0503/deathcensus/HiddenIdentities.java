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
 * <p>The dictionary covers every fully opted-out player (current name, UUID and
 * recorded name aliases) plus the opponents hidden by another player's
 * {@code hidePvpOpponent} flag. Aggregates and stored records are never changed;
 * only rendered copies are scrubbed, and self/admin viewers keep the truth.
 */
public final class HiddenIdentities {
    /** Stand-in used in free text so a scrubbed line stays readable. */
    public static final String HIDDEN_MARKER = WitnessRedactor.HIDDEN_NAME_MARKER;

    private static final HiddenIdentities EMPTY =
            new HiddenIdentities(null, Map.of());

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
            if (player == null) {
                continue;
            }
            if (player.optOutPublic()) {
                register(byLowerName, player.name());
                register(byLowerName, player.uuid());
                for (DeathEntry death : player.deaths()) {
                    if (death == null) {
                        continue;
                    }
                    register(byLowerName, death.playerName());
                    for (WitnessRef witness : death.witnesses()) {
                        if (witness != null) {
                            register(byLowerName, witness.nameAtDeath());
                        }
                    }
                }
                for (NearDeathEvent event : player.nearDeaths()) {
                    if (event != null) {
                        register(byLowerName, event.name());
                        register(byLowerName, event.uuid());
                    }
                }
            }
            if (player.hidePvpOpponent()) {
                // The opponent is hidden from this player's public records; do
                // not let the same name leak through any other public surface.
                for (DeathEntry death : player.deaths()) {
                    if (death != null) {
                        register(byLowerName, death.killerName());
                    }
                }
            }
        }
        if (byLowerName.isEmpty()) {
            return EMPTY;
        }
        List<String> secrets = new ArrayList<>(byLowerName.keySet());
        // Longest first so "Alice, Jr." wins over "Alice"; alphabetical for
        // determinism. Word boundaries avoid partial matches (S3/M5).
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

    /** Scrubs the identity-bearing fields of a near-death record (§10). */
    public NearDeathEvent scrub(NearDeathEvent event) {
        if (event == null || pattern == null) {
            return event;
        }
        return new NearDeathEvent(
                scrub(event.uuid()),
                scrub(event.name()),
                event.epochMillis(),
                scrub(event.dimension()),
                event.x(), event.y(), event.z(),
                scrub(event.causeCategory()),
                scrub(event.causeId()),
                event.kind());
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
                entry.fCount(),
                entry.witnesses(),
                entry.context());
    }
}
