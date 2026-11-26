package com.pengin0503.deathcensus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Render-time anonymization for exports (DESIGN 1.3.0 §6).
 *
 * <p>The mapping is derived only from the supplied data set: players are sorted
 * by UUID and assigned {@code Player A}, {@code Player B}, … in that order.
 * The same input therefore always yields the same pseudonyms across formats and
 * across the files of one export run. No aggregate ever consumes these copies.
 */
public final class Anonymizer {
    /** Coordinate blur grid, in blocks (DESIGN 1.3.0 §12, question 15). */
    public static final int GRID = 64;

    private Anonymizer() {
    }

    /** Returns a redacted copy; the input list and its records are never modified. */
    public static List<PlayerDeaths> apply(List<PlayerDeaths> players) {
        if (players == null || players.isEmpty()) {
            return List.of();
        }
        List<PlayerDeaths> sorted = new ArrayList<>();
        for (PlayerDeaths player : players) {
            if (player != null) {
                sorted.add(player);
            }
        }
        if (sorted.isEmpty()) {
            return List.of();
        }
        // The UUID order is the pseudonym contract (§12, question 14).
        sorted.sort(Comparator.comparing(PlayerDeaths::uuid));

        Map<String, String> pseudoByUuid = new HashMap<>();
        Map<String, String> anonUuidByUuid = new HashMap<>();
        List<String> secrets = new ArrayList<>();
        Map<String, String> secretReplacements = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            PlayerDeaths player = sorted.get(i);
            String pseudo = pseudonym(i);
            String anonUuid = "anon-" + (i + 1);
            pseudoByUuid.put(player.uuid(), pseudo);
            anonUuidByUuid.put(player.uuid(), anonUuid);
            // Duplicate names map to the first (lowest-UUID) pseudonym.
            if (!player.name().isEmpty()) {
                registerSecret(secrets, secretReplacements, player.name(), pseudoByUuid.get(player.uuid()));
            }
            if (!player.uuid().isEmpty()) {
                registerSecret(secrets, secretReplacements, player.uuid(), anonUuid);
            }
        }

        Pattern secretPattern = secretPattern(secrets);
        List<PlayerDeaths> result = new ArrayList<>(sorted.size());
        for (PlayerDeaths player : sorted) {
            String pseudo = pseudoByUuid.get(player.uuid());
            String anonUuid = anonUuidByUuid.get(player.uuid());
            List<DeathEntry> redactedDeaths = new ArrayList<>(player.deaths().size());
            for (DeathEntry d : player.deaths()) {
                redactedDeaths.add(new DeathEntry(
                        anonUuid,
                        pseudo,
                        d.epochMillis(),
                        d.gameDay(),
                        d.playTicks(),
                        d.survivalTicks(),
                        d.dimension(),
                        roundToGrid(d.x()),
                        roundToGrid(d.y()),
                        roundToGrid(d.z()),
                        d.causeCategory(),
                        d.causeId(),
                        scrub(d.deathMessage(), secretPattern, secretReplacements),
                        d.killerType(),
                        scrub(d.killerName(), secretPattern, secretReplacements),
                        d.gameMode(),
                        d.experienceLevel(),
                        scrub(d.excuse(), secretPattern, secretReplacements),
                        scrub(d.lastWords(), secretPattern, secretReplacements),
                        d.fCount()));
            }
            // Privacy flags are meaningless for pseudonyms and are normalized (§6).
            result.add(new PlayerDeaths(anonUuid, pseudo, false, false, false, false, redactedDeaths));
        }
        return result;
    }

    private static void registerSecret(List<String> secrets, Map<String, String> replacements,
                                       String secret, String replacement) {
        if (!replacements.containsKey(secret)) {
            secrets.add(secret);
            replacements.put(secret, replacement);
        }
    }

    private static Pattern secretPattern(List<String> secrets) {
        if (secrets.isEmpty()) {
            return null;
        }
        // Longest first so "Alice, Jr." wins over "Alice"; ties are
        // alphabetical for determinism. Word boundaries avoid partial matches.
        secrets.sort(Comparator.comparingInt((String s) -> -s.length()).thenComparing(s -> s));
        StringBuilder pattern = new StringBuilder("(?<![\\p{L}\\p{N}_])(?:");
        for (int i = 0; i < secrets.size(); i++) {
            if (i > 0) {
                pattern.append('|');
            }
            pattern.append(Pattern.quote(secrets.get(i)));
        }
        pattern.append(")(?![\\p{L}\\p{N}_])");
        return Pattern.compile(pattern.toString());
    }

    private static String scrub(String text, Pattern pattern, Map<String, String> replacements) {
        if (text == null || text.isEmpty() || pattern == null) {
            return text == null ? "" : text;
        }
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder(text.length() + 16);
        while (matcher.find()) {
            String replacement = replacements.get(matcher.group());
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String pseudonym(int index) {
        int n = index + 1;
        StringBuilder sb = new StringBuilder();
        while (n > 0) {
            n--;
            sb.insert(0, (char) ('A' + n % 26));
            n /= 26;
        }
        return "Player " + sb;
    }

    /** Nearest multiple of 64; exact halves go toward zero, so negatives mirror positives. */
    private static double roundToGrid(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        double units = value / GRID;
        double floor = Math.floor(units);
        double rounded;
        if (units - floor == 0.5) {
            rounded = units > 0.0 ? floor : Math.ceil(units);
        } else {
            rounded = Math.round(units);
        }
        double result = rounded * (double) GRID;
        return result == 0.0 ? 0.0 : result; // avoid -0.0 in JSON/CSV
    }
}
