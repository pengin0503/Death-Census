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
import net.minecraft.commands.Commands;
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
 * {@link UiContext} bound to the viewing player. Every permission and every
 * record lookup is resolved against live server state; only the short-lived UI
 * session is kept here. All dialog payloads are untrusted (DESIGN §3.2).
 */
public final class DialogUiContext implements UiContext {
    private static final UiSessionStore SESSIONS = new UiSessionStore();
    private static int dataVersion = 1;

    private final ServerPlayer player;

    public DialogUiContext(ServerPlayer player) {
        this.player = player;
    }

    private MinecraftServer server() {
        return player.level().getServer();
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
        CommandSourceStack source = player.createCommandSourceStack();
        return PermissionBridge.allowed(source, DeathCensusPermission.LOOKUP,
                Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source));
    }

    @Override
    public boolean isSelf(UUID uuid) {
        return uuid != null && uuid.equals(player.getUUID());
    }

    @Override
    public boolean isOptedOut(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        MinecraftServer server = server();
        if (server == null) {
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
                DeathCensusPermission.PRIVACY_OTHER,
                Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source));
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
    public boolean nemesisEnabled() {
        return Config.nemesisEnabled();
    }

    @Override
    public int nemesisMinMobCount() {
        return Config.nemesisMinMobCount();
    }

    @Override
    public int nemesisMinNamedCount() {
        return Config.nemesisMinNamedCount();
    }

    @Override
    public boolean hotspotsEnabled() {
        return Config.hotspotsEnabled();
    }

    @Override
    public int hotspotMinDeaths() {
        return Config.hotspotMinDeaths();
    }

    @Override
    public int hotspotChunkSize() {
        return Config.hotspotChunkSize();
    }

    @Override
    public boolean distributionsEnabled() {
        return Config.distributionsEnabled();
    }

    @Override
    public int distributionTimeBinHours() {
        return Config.distributionTimeBinHours();
    }

    @Override
    public int distributionYBandBlocks() {
        return Config.distributionYBandBlocks();
    }

    @Override
    public boolean loopsEnabled() {
        return Config.deathLoopEnabled();
    }

    @Override
    public boolean nearDeathEnabled() {
        return Config.nearDeathCaptureEnabled();
    }

    @Override
    public int deathLoopMinDeaths() {
        return Config.deathLoopMinDeaths();
    }

    @Override
    public int deathLoopWindowMinutes() {
        return Config.deathLoopWindowMinutes();
    }

    @Override
    public int deathLoopRadiusBlocks() {
        return Config.deathLoopRadiusBlocks();
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
        return CensusLogic.survivalTicks(player.getStats().getValue(Stats.CUSTOM, Stats.PLAY_TIME),
                lastDeath, baseline);
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
        // The dispatcher immediately opens Home after an expired session; a chat
        // line is used here so the feedback cannot be replaced by that render.
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
        }
        return status == CensusCommands.DELETE_OK;
    }

    @Override
    public void setOptOut(boolean optOut) {
        setPrivacyFlag(viewer(), PlayerPrivacy.Flag.FULL, optOut);
    }
}
