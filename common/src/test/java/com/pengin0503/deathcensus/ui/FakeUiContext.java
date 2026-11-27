package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.ChronicleEvent;
import com.pengin0503.deathcensus.DeathEntry;
import com.pengin0503.deathcensus.PlayerDeaths;
import com.pengin0503.deathcensus.PlayerPrivacy;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Test double: all Minecraft-free, with a live session store and action log. */
final class FakeUiContext implements UiContext {
    final UUID viewer;
    final List<PlayerDeaths> players;
    final UiSessionStore sessions;
    final List<String> notices = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    boolean admin;
    int dataVersion = 1;
    int survivalTicks = 42;
    long nowMillis = 1_700_000_000_000L;
    ZoneId zone = ZoneId.of("UTC");
    boolean excuseResult = true;
    boolean respectResult = true;
    boolean deleteResult = true;
    boolean optOut;
    boolean chronicleEnabled = true;
    List<ChronicleEvent> chronicleEvents = List.of();
    boolean privacyOtherAllowed;
    final Map<UUID, PlayerPrivacy> privacy = new HashMap<>();

    FakeUiContext(UUID viewer, List<PlayerDeaths> players) {
        this.viewer = viewer;
        this.players = new ArrayList<>(players);
        this.sessions = new UiSessionStore(() -> nowMillis);
    }

    @Override
    public UUID viewer() {
        return viewer;
    }

    @Override
    public List<PlayerDeaths> snapshot() {
        return List.copyOf(players);
    }

    @Override
    public boolean isAdmin() {
        return admin;
    }

    @Override
    public boolean isSelf(UUID uuid) {
        return viewer.equals(uuid);
    }

    @Override
    public boolean isOptedOut(UUID uuid) {
        return uuid != null && UiPrivacy.optOutFor(players, uuid.toString());
    }

    @Override
    public boolean canManagePrivacy(UUID target) {
        return UiPrivacy.canManagePrivacy(viewer, target, privacyOtherAllowed || admin);
    }

    @Override
    public boolean chronicleEnabled() {
        return chronicleEnabled;
    }

    @Override
    public List<ChronicleEvent> chronicleEvents() {
        return chronicleEvents;
    }

    @Override
    public String label(String key) {
        if (key == null) {
            return "";
        }
        return switch (key) {
            case "command.deathcensus.chronicle.row" -> "%s — %s · day %s";
            case "command.deathcensus.chronicle.hidden" -> "Anonymous";
            case "command.deathcensus.chronicle.reconstructed" -> "reconstructed";
            case "command.deathcensus.nemesis.row" -> "%s: %s — %s";
            case "command.deathcensus.nemesis.anonymous" -> "Anonymous";
            case "command.deathcensus.nemesis.table.mob" -> "Mob species";
            case "command.deathcensus.nemesis.table.named" -> "Named rival";
            case "command.deathcensus.nemesis.table.pvp" -> "PvP rival";
            case "command.deathcensus.distribution.row" -> "%s — %s death(s) · top: %s";
            case "command.deathcensus.loops.row" -> "%s: %s deaths in %s near %s · top: %s";
            default -> key.contains(".chronicle.kind.")
                    ? key.substring(key.lastIndexOf('.') + 1) : key;
        };
    }

    @Override
    public PlayerPrivacy getPrivacyFlags(UUID uuid) {
        return privacy.getOrDefault(uuid, UiPrivacy.privacyFor(players, uuid == null ? "" : uuid.toString()));
    }

    @Override
    public void setPrivacyFlag(UUID uuid, PlayerPrivacy.Flag flag, boolean value) {
        calls.add("privacy:" + uuid + ":" + flag.token() + ":" + value);
        privacy.put(uuid, PlayerPrivacy.withFlag(getPrivacyFlags(uuid), flag, value));
        if (flag == PlayerPrivacy.Flag.FULL) {
            calls.add("optout:" + value);
            optOut = value;
        }
        dataVersion++;
    }

    @Override
    public int dataVersion() {
        return dataVersion;
    }

    @Override
    public int currentSurvivalTicks(UUID uuid) {
        return survivalTicks;
    }

    @Override
    public ZoneId zoneId() {
        return zone;
    }

    @Override
    public long nowMillis() {
        return nowMillis;
    }

    @Override
    public UiSession currentSession() {
        return sessions.current(viewer);
    }

    @Override
    public UiSession newSession(HistoryFilter filter, UiSession.RankingSelection ranking) {
        return sessions.issue(viewer, filter, ranking, dataVersion);
    }

    @Override
    public UiSession touchSession(UiSession presented, HistoryFilter filter, UiSession.RankingSelection ranking) {
        return sessions.rotate(viewer, presented, filter, ranking, dataVersion);
    }

    @Override
    public void invalidateSession() {
        sessions.clear(viewer);
    }

    @Override
    public void sendNotice(UiText text) {
        notices.add(text.key());
    }

    @Override
    public boolean setExcuse(UUID uuid, long epoch, String text) {
        calls.add("excuse:" + uuid + ":" + epoch + ":" + text);
        return excuseResult;
    }

    @Override
    public boolean payRespect(UUID sender, UUID victim, long epoch) {
        calls.add("respect:" + sender + ":" + victim + ":" + epoch);
        return respectResult;
    }

    @Override
    public boolean deleteAfterBackup(UUID uuid, long epoch) {
        calls.add("delete:" + uuid + ":" + epoch);
        return deleteResult;
    }

    @Override
    public void setOptOut(boolean value) {
        calls.add("optout:" + value);
        optOut = value;
        dataVersion++;
    }

    String token() {
        UiSession session = currentSession();
        return session == null ? "" : session.token();
    }
}
