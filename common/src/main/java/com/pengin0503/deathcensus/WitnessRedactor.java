package com.pengin0503.deathcensus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Render-time privacy composition for witnesses (DESIGN 1.4.0 §1, §8, §15 Q3).
 *
 * <p>The captured {@link WitnessRef} list stays on the immutable death record so
 * self and admins keep the truth. Every public surface resolves the witness name
 * from the current snapshot's {@code lastKnownName} when available and replaces
 * an opted-out witness with {@link #HIDDEN_NAME_MARKER}. No new per-player flag
 * is introduced; {@code optOutPublic} remains the only identity gate.
 */
public final class WitnessRedactor {
    /** Public stand-in for a witness who has since opted out of named views. */
    public static final String HIDDEN_NAME_MARKER = "Anonymous";

    private WitnessRedactor() {
    }

    /** The shared "Witnesses: A, B" rendering used by chat, GUI, CSV and HTML. */
    public static String joinNames(List<String> names) {
        return names == null || names.isEmpty() ? "" : String.join(", ", names);
    }

    /** Current UUID → last-known-name map; built from the full live snapshot. */
    public static Map<String, String> namesById(Collection<PlayerDeaths> players) {
        Map<String, String> names = new LinkedHashMap<>();
        if (players == null) {
            return Map.of();
        }
        for (PlayerDeaths player : players) {
            if (player != null && !player.uuid().isEmpty() && !player.name().isEmpty()) {
                names.put(player.uuid(), player.name());
            }
        }
        return Map.copyOf(names);
    }

    /** Current UUID → four privacy flags map; built from the full live snapshot. */
    public static Map<String, PlayerPrivacy> privacyById(Collection<PlayerDeaths> players) {
        Map<String, PlayerPrivacy> privacy = new LinkedHashMap<>();
        if (players == null) {
            return Map.of();
        }
        for (PlayerDeaths player : players) {
            if (player != null && !player.uuid().isEmpty()) {
                privacy.put(player.uuid(), PlayerPrivacy.of(player));
            }
        }
        return Map.copyOf(privacy);
    }

    /**
     * Names in capture order (nearest first) for one death. Self/admin always see
     * the captured names; a public viewer sees current names with opted-out
     * witnesses replaced by {@link #HIDDEN_NAME_MARKER}. The death is immutable.
     */
    public static List<String> displayNames(DeathEntry entry, Map<String, String> names,
                                            Map<String, PlayerPrivacy> privacy, boolean viewerIsSelfOrAdmin) {
        if (entry == null || entry.witnesses().isEmpty()) {
            return List.of();
        }
        Map<String, String> currentNames = names == null ? Map.of() : names;
        Map<String, PlayerPrivacy> currentPrivacy = privacy == null ? Map.of() : privacy;
        List<String> result = new ArrayList<>(entry.witnesses().size());
        for (WitnessRef ref : entry.witnesses()) {
            if (ref == null || ref.isEmpty()) {
                continue;
            }
            PlayerPrivacy flags = currentPrivacy.get(ref.uuid());
            if (!viewerIsSelfOrAdmin && flags != null && flags.hidesPublicIdentity()) {
                result.add(HIDDEN_NAME_MARKER);
                continue;
            }
            String resolved = currentNames.get(ref.uuid());
            result.add(resolved != null && !resolved.isBlank()
                    ? resolved
                    : ref.nameAtDeath().isEmpty() ? ref.uuid() : ref.nameAtDeath());
        }
        return List.copyOf(result);
    }

    /** Convenience overload that composes both maps from a snapshot. */
    public static List<String> displayNames(DeathEntry entry, Collection<PlayerDeaths> players,
                                            boolean viewerIsSelfOrAdmin) {
        return displayNames(entry, namesById(players), privacyById(players), viewerIsSelfOrAdmin);
    }

    /** Public-view convenience: never reveals an opted-out witness name. */
    public static List<String> publicNames(DeathEntry entry, Collection<PlayerDeaths> players) {
        return displayNames(entry, players, false);
    }

    /**
     * Same composition as {@link #displayNames}, but keeps the UUID alongside
     * each rendered name for surfaces that need a stable identity (e.g. export
     * rows). Opted-out witnesses keep their UUID so counts stay truthful while
     * the name becomes the hidden marker.
     */
    public static List<WitnessRef> viewRefs(DeathEntry entry, Map<String, String> names,
                                            Map<String, PlayerPrivacy> privacy, boolean viewerIsSelfOrAdmin) {
        if (entry == null || entry.witnesses().isEmpty()) {
            return List.of();
        }
        Map<String, String> currentNames = names == null ? Map.of() : names;
        Map<String, PlayerPrivacy> currentPrivacy = privacy == null ? Map.of() : privacy;
        List<WitnessRef> result = new ArrayList<>(entry.witnesses().size());
        for (WitnessRef ref : entry.witnesses()) {
            if (ref == null || ref.isEmpty()) {
                continue;
            }
            PlayerPrivacy flags = currentPrivacy.get(ref.uuid());
            if (!viewerIsSelfOrAdmin && flags != null && flags.hidesPublicIdentity()) {
                result.add(new WitnessRef(ref.uuid(), HIDDEN_NAME_MARKER));
                continue;
            }
            String resolved = currentNames.get(ref.uuid());
            result.add(new WitnessRef(ref.uuid(), resolved != null && !resolved.isBlank()
                    ? resolved
                    : ref.nameAtDeath().isEmpty() ? ref.uuid() : ref.nameAtDeath()));
        }
        return List.copyOf(result);
    }
}
