package com.pengin0503.deathcensus;

import com.pengin0503.deathcensus.ui.UiPrivacy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Pure side-by-side player comparison for DESIGN 1.4.0 §6. Everything is derived
 * from retained {@link DeathEntry} records; no storage, event or hot-path code
 * is touched and the input snapshot and records are never mutated.
 *
 * <p>Names resolve case-insensitively against current names and recorded player
 * names (the same aliases {@code /dc history} uses); an ambiguous name takes the
 * first stored match. There is no wins field: the only PvP-shaped figure is the
 * count of {@code causeCategory="player"} deaths.
 *
 * <p>Privacy composition is strict. A public viewer who cannot see either side
 * gets {@link CompareResult.Failure#HIDDEN_PLAYER}; callers render that exactly
 * like {@link CompareResult.Failure#UNKNOWN_PLAYER} so an opted-out account is
 * not disclosed. Self and admin rights are evaluated per side, so a self-view
 * cannot unlock another opted-out player. Opponent names use the victim's
 * {@code hidePvpOpponent} flag through {@link DeathRedactor} and the opponent's
 * own {@code optOutPublic} through the full-snapshot hidden-identity dictionary.
 */
public final class CompareAnalysis {
    /** Canonical category for the "deaths caused by players" count (§15 Q15). */
    public static final String PLAYER_CAUSE = "player";
    /** Maximum accepted length of a supplied player name. */
    public static final int MAX_NAME_LENGTH = 64;

    private CompareAnalysis() {
    }

    /** Public-viewer convenience overload: any opted-out side is hidden. */
    public static CompareResult compare(List<PlayerDeaths> players, String nameA, String nameB,
                                        long sinceMillis, Map<String, PlayerPrivacy> privacyById) {
        return compare(players, nameA, nameB, sinceMillis, privacyById, null, false);
    }

    /**
     * Full comparison. {@code viewer} is the requesting player (null for console)
     * and {@code admin} is the already-resolved LOOKUP/OP decision; both are
     * checked against each side independently through {@link UiPrivacy}.
     */
    public static CompareResult compare(List<PlayerDeaths> players, String nameA, String nameB,
                                        long sinceMillis, Map<String, PlayerPrivacy> privacyById,
                                        UUID viewer, boolean admin) {
        String first = cleanName(nameA);
        String second = cleanName(nameB);
        if (first.isEmpty() || second.isEmpty()) {
            return CompareResult.unknown(first, second);
        }
        List<PlayerDeaths> filtered = CensusStats.filterSince(players, sinceMillis);
        PlayerDeaths playerA = resolve(filtered, first);
        PlayerDeaths playerB = resolve(filtered, second);
        if (playerA == null || playerB == null) {
            return CompareResult.unknown(first, second);
        }
        PlayerPrivacy privacyA = privacyFor(playerA, privacyById);
        PlayerPrivacy privacyB = privacyFor(playerB, privacyById);
        if (!canView(viewer, playerA, privacyA, admin) || !canView(viewer, playerB, privacyB, admin)) {
            // Deliberately the same reason for both sides: never disclose which
            // one exists (§15 Q16).
            return CompareResult.hidden(first, second);
        }
        HiddenIdentities hidden = admin ? HiddenIdentities.empty() : HiddenIdentities.of(players);
        // Full opt-outs are secrets for every non-admin viewer. Opponents hidden
        // only by somebody else's granular flag are handled by DeathRedactor for
        // that victim's own public rows, not treated as global identities.
        HiddenIdentities optOuts = admin ? HiddenIdentities.empty() : optOutIdentities(players);
        CompareResult.Row rowA = row(playerA, privacyA, viewer, admin, hidden, optOuts);
        CompareResult.Row rowB = row(playerB, privacyB, viewer, admin, hidden, optOuts);
        return CompareResult.success(first, second, rowA, rowB);
    }

    /** Case-insensitive current-name / recorded-name / uuid resolution, first match wins. */
    private static PlayerDeaths resolve(List<PlayerDeaths> players, String name) {
        if (players == null || name.isEmpty()) {
            return null;
        }
        for (PlayerDeaths player : players) {
            if (player == null) {
                continue;
            }
            if (name.equalsIgnoreCase(player.name()) || name.equalsIgnoreCase(player.uuid())) {
                return player;
            }
            for (DeathEntry death : player.deaths()) {
                if (death != null && name.equalsIgnoreCase(death.playerName())) {
                    return player;
                }
            }
        }
        return null;
    }

    private static String cleanName(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        return trimmed.length() > MAX_NAME_LENGTH ? "" : trimmed;
    }

    private static PlayerPrivacy privacyFor(PlayerDeaths player, Map<String, PlayerPrivacy> privacyById) {
        PlayerPrivacy fallback = PlayerPrivacy.of(player);
        if (privacyById == null || privacyById.isEmpty() || player.uuid().isEmpty()) {
            return fallback;
        }
        PlayerPrivacy supplied = privacyById.get(player.uuid());
        if (supplied == null) {
            for (Map.Entry<String, PlayerPrivacy> entry : privacyById.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(player.uuid())) {
                    supplied = entry.getValue();
                    break;
                }
            }
        }
        if (supplied == null) {
            return fallback;
        }
        // The stored master switch dominates a stale override: an opted-out
        // player can never be named by supplying an outdated privacy map.
        return fallback.optOutPublic()
                ? new PlayerPrivacy(true, supplied.hideCoordinates(),
                        supplied.hidePvpOpponent(), supplied.discordOptOut())
                : supplied;
    }

    /** §8 gate: self OR admin OR !optOutPublic, with console treated as a public viewer. */
    private static boolean canView(UUID viewer, PlayerDeaths player, PlayerPrivacy privacy, boolean admin) {
        if (privacy.optOutPublic()) {
            return admin || (viewer != null && viewer.toString().equalsIgnoreCase(player.uuid()));
        }
        if (viewer == null || player.uuid().isEmpty()) {
            return true;
        }
        return UiPrivacy.canViewDetail(viewer, player.uuid(), false, admin);
    }

    /** Hidden-identity dictionary containing full opt-outs but no granular flags. */
    private static HiddenIdentities optOutIdentities(List<PlayerDeaths> players) {
        if (players == null || players.isEmpty()) {
            return HiddenIdentities.empty();
        }
        List<PlayerDeaths> optOuts = new ArrayList<>(players.size());
        for (PlayerDeaths player : players) {
            if (player == null) {
                continue;
            }
            optOuts.add(new PlayerDeaths(player.uuid(), player.name(), player.optOutPublic(),
                    player.hideCoordinates(), false, player.discordOptOut(), player.deaths(),
                    player.vanillaDeathsBaseline(), player.vanillaBaselineCauses(), player.nearDeaths()));
        }
        return HiddenIdentities.of(optOuts);
    }

    private static CompareResult.Row row(PlayerDeaths player, PlayerPrivacy privacy,
                                         UUID viewer, boolean admin,
                                         HiddenIdentities hidden, HiddenIdentities optOuts) {
        List<DeathEntry> deaths = new ArrayList<>();
        for (DeathEntry death : player.deaths()) {
            if (death != null) {
                deaths.add(death);
            }
        }
        if (deaths.isEmpty()) {
            return CompareResult.Row.empty(player.uuid(), player.name());
        }
        long survivalSum = 0L;
        int pvpDeaths = 0;
        Map<String, Integer> opponents = new LinkedHashMap<>();
        Map<String, DeathEntry> samples = new LinkedHashMap<>();
        for (DeathEntry death : deaths) {
            survivalSum += Math.max(0L, death.survivalTicks());
            if (!PLAYER_CAUSE.equalsIgnoreCase(death.causeCategory())) {
                continue;
            }
            pvpDeaths++;
            String rival = death.killerName();
            if (rival.isBlank()) {
                continue;
            }
            String key = rival.toLowerCase(Locale.ROOT);
            opponents.merge(key, 1, Integer::sum);
            samples.putIfAbsent(key, death);
        }
        String topKey = opponents.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("");
        int topCount = opponents.getOrDefault(topKey, 0);
        DeathEntry sample = samples.get(topKey);
        String topOpponent = "";
        boolean opponentHidden = false;
        if (sample != null) {
            boolean self = viewer != null && viewer.toString().equalsIgnoreCase(player.uuid());
            DeathEntry rendered = DeathRedactor.apply(sample, privacy, admin || self, hidden);
            String raw = sample.killerName();
            String shown = rendered.killerName();
            // M11: detect a redaction by comparing before/after, never by
            // comparing a display string: a real rival named "Anonymous" must
            // not be misclassified as hidden.
            String scrubbed = optOuts.scrub(raw);
            boolean scrubbedElsewhere = !admin && !scrubbed.equals(raw);
            boolean hiddenByVictim = !admin && !self && privacy.hidePvpOpponent();
            opponentHidden = shown.isBlank() || !shown.equals(raw)
                    || scrubbedElsewhere || hiddenByVictim;
            topOpponent = opponentHidden ? HiddenIdentities.HIDDEN_MARKER : raw;
        }
        List<CensusStats.PlayerSummary> summaries = CensusStats.summaries(List.of(player), true);
        CensusStats.PlayerSummary summary = summaries.isEmpty()
                ? new CensusStats.PlayerSummary(player.uuid(), player.name(), deaths.size(),
                        0, -1, 0L, 0L, "")
                : summaries.get(0);
        String topCause = CensusStats.causeCounts(List.of(player), true).stream()
                .findFirst().map(Map.Entry::getKey).orElse("");
        return new CompareResult.Row(player.uuid(), player.name(), summary,
                survivalSum / deaths.size(), topCause, pvpDeaths,
                topOpponent, topCount, opponentHidden);
    }
}
