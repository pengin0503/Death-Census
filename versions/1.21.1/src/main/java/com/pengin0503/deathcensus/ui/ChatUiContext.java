package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.ChronicleEvent;
import com.pengin0503.deathcensus.CensusCommands;
import com.pengin0503.deathcensus.CensusData;
import com.pengin0503.deathcensus.CensusLogic;
import com.pengin0503.deathcensus.Config;
import com.pengin0503.deathcensus.DeathCensusPermission;
import com.pengin0503.deathcensus.DeathTracker;
import com.pengin0503.deathcensus.PermissionBridge;
import com.pengin0503.deathcensus.PlayerDeaths;
import com.pengin0503.deathcensus.PlayerPrivacy;
import com.pengin0503.deathcensus.ServerLang;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 1.21.1 {@link UiContext}: the same live-data bridge and session semantics as
 * the 26.x DialogUiContext, with the version's {@code hasPermission(2)} idiom.
 * Chat click payloads are untrusted, so every permission and record lookup is
 * re-resolved here at execution time.
 */
public final class ChatUiContext implements UiContext {
    private static final UiSessionStore SESSIONS = new UiSessionStore();
    private static int dataVersion = 1;

    private final ServerPlayer player;

    public ChatUiContext(ServerPlayer player) {
        this.player = player;
    }

    private MinecraftServer server() {
        return player == null ? null : player.getServer();
    }

    @Override
    public UUID viewer() {
        return player.getUUID();
    }

    @Override
    public List<PlayerDeaths> snapshot() {
        MinecraftServer server = server();
        return server == null ? List.of() : CensusData.get(server).snapshot();
    }

    @Override
    public boolean isAdmin() {
        // §10: same vanilla fallback as the chat commands, resolved via the bridge.
        if (player == null) {
            return false;
        }
        CommandSourceStack source = player.createCommandSourceStack();
        return PermissionBridge.allowed(source, DeathCensusPermission.LOOKUP, source.hasPermission(2));
    }

    @Override
    public boolean isSelf(UUID uuid) {
        return uuid != null && uuid.equals(player.getUUID());
    }

    @Override
    public boolean isOptedOut(UUID uuid) {
        MinecraftServer server = server();
        if (uuid == null || server == null) {
            return false;
        }
        CensusData.PlayerCensus pc = CensusData.get(server).playerOrNull(uuid);
        return pc != null && pc.optOutPublic;
    }

    @Override
    public boolean canManagePrivacy(UUID target) {
        if (target == null) {
            return false;
        }
        if (isSelf(target)) {
            return true;
        }
        CommandSourceStack source = player == null ? null : player.createCommandSourceStack();
        return source != null && PermissionBridge.allowed(source,
                DeathCensusPermission.PRIVACY_OTHER, source.hasPermission(2));
    }

    @Override
    public PlayerPrivacy getPrivacyFlags(UUID uuid) {
        MinecraftServer server = server();
        if (uuid == null || server == null) {
            return PlayerPrivacy.none();
        }
        CensusData.PlayerCensus pc = CensusData.get(server).playerOrNull(uuid);
        return pc == null ? PlayerPrivacy.none() : new PlayerPrivacy(
                pc.optOutPublic, pc.hideCoordinates, pc.hidePvpOpponent, pc.discordOptOut);
    }

    @Override
    public boolean awardsEnabled() {
        return Config.awardsEnabled();
    }

    @Override
    public boolean chronicleEnabled() {
        return Config.chronicleEnabled();
    }

    @Override
    public List<ChronicleEvent> chronicleEvents() {
        MinecraftServer server = server();
        return server == null ? List.of() : CensusData.get(server).chronicleEvents();
    }

    @Override
    public Map<String, Set<String>> lifetimeCategories() {
        MinecraftServer server = server();
        if (server == null) {
            return Map.of();
        }
        Map<String, Set<String>> categories = new LinkedHashMap<>();
        for (Map.Entry<UUID, CensusData.PlayerCensus> entry
                : CensusData.get(server).allPlayers().entrySet()) {
            if (!entry.getValue().optOutPublic) {
                categories.put(entry.getKey().toString(), entry.getValue().lifetimeCategories());
            }
        }
        return categories;
    }

    @Override
    public String label(String key) {
        return ServerLang.formatForLang(player != null && player.clientInformation() != null
                ? player.clientInformation().language() : ServerLang.DEFAULT_LANG, key);
    }

    @Override
    public void setPrivacyFlag(UUID uuid, PlayerPrivacy.Flag flag, boolean value) {
        MinecraftServer server = server();
        if (uuid == null || server == null || flag == null) {
            return;
        }
        CensusData data = CensusData.get(server);
        CensusData.PlayerCensus pc = data.player(uuid);
        switch (flag) {
            case FULL -> pc.optOutPublic = value;
            case COORDS -> pc.hideCoordinates = value;
            case OPPONENT -> pc.hidePvpOpponent = value;
            case DISCORD -> pc.discordOptOut = value;
        }
        data.setDirty();
        bumpDataVersion();
    }

    @Override
    public int dataVersion() {
        return dataVersion;
    }

    private static void bumpDataVersion() {
        dataVersion++;
    }

    @Override
    public int currentSurvivalTicks(UUID uuid) {
        MinecraftServer server = server();
        if (server == null) {
            return 0;
        }
        CensusData.PlayerCensus pc = CensusData.get(server).playerOrNull(uuid);
        int lastDeath = pc == null ? -1 : pc.lastDeathPlayTicks;
        int baseline = pc == null ? -1 : pc.baselinePlayTicks;
        // DESIGN §8 default (2): play-time survival, not wall-clock time.
        return CensusLogic.survivalTicks(
                player.getStats().getValue(Stats.CUSTOM, Stats.PLAY_TIME), lastDeath, baseline);
    }

    @Override
    public ZoneId zoneId() {
        return Config.displayTimeZone();
    }

    @Override
    public UiSession currentSession() {
        return SESSIONS.current(viewer());
    }

    @Override
    public UiSession newSession(HistoryFilter filter, UiSession.RankingSelection ranking) {
        return SESSIONS.issue(viewer(), filter, ranking, dataVersion());
    }

    @Override
    public UiSession touchSession(UiSession presented, HistoryFilter filter, UiSession.RankingSelection ranking) {
        return SESSIONS.rotate(viewer(), presented, filter, ranking, dataVersion());
    }

    @Override
    public void invalidateSession() {
        SESSIONS.clear(viewer());
    }

    @Override
    public void sendNotice(UiText text) {
        // The dispatcher immediately opens Home after expiry; a chat line keeps
        // the feedback visible after that render, as in the Dialog adapter.
        player.sendSystemMessage(ServerLang.tr(player, text.key(), text.args()));
    }

    @Override
    public boolean setExcuse(UUID uuid, long epoch, String text) {
        MinecraftServer server = server();
        if (server == null || uuid == null) {
            return false;
        }
        boolean applied = CensusData.get(server).setExcuse(uuid, epoch, text);
        if (applied) {
            bumpDataVersion();
        }
        return applied;
    }

    @Override
    public boolean payRespect(UUID sender, UUID victim, long epoch) {
        MinecraftServer server = server();
        if (server == null || victim == null) {
            return false;
        }
        boolean applied = DeathTracker.payRespect(server, sender, victim, epoch);
        if (applied) {
            bumpDataVersion();
        }
        return applied;
    }

    @Override
    public boolean deleteAfterBackup(UUID uuid, long epoch) {
        MinecraftServer server = server();
        if (server == null || uuid == null) {
            return false;
        }
        int status = CensusCommands.deleteDeathAfterBackup(server, uuid, -1, epoch);
        if (status == CensusCommands.DELETE_OK) {
            bumpDataVersion();
            // UiActions renders the precise name/index/date confirmation.
        }
        return status == CensusCommands.DELETE_OK;
    }

    @Override
    public void setOptOut(boolean optOut) {
        setPrivacyFlag(viewer(), PlayerPrivacy.Flag.FULL, optOut);
    }

    /** Token of the viewer's live session, used by chat-only commands that reuse UiActions. */
    public static String currentToken(ServerPlayer player) {
        UiSession session = player == null ? null : new ChatUiContext(player).currentSession();
        return session == null ? "" : session.token();
    }
}
