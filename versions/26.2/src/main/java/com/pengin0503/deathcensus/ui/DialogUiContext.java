package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.CensusCommands;
import com.pengin0503.deathcensus.CensusData;
import com.pengin0503.deathcensus.CensusLogic;
import com.pengin0503.deathcensus.Config;
import com.pengin0503.deathcensus.DeathTracker;
import com.pengin0503.deathcensus.PlayerDeaths;
import com.pengin0503.deathcensus.ServerLang;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

import java.time.ZoneId;
import java.util.List;
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
        // Same permission idiom as the chat commands (Commands.LEVEL_GAMEMASTERS).
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack());
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
        MinecraftServer server = server();
        if (server == null) {
            return;
        }
        CensusData data = CensusData.get(server);
        data.player(viewer()).optOutPublic = optOut;
        data.setDirty();
        bumpDataVersion();
    }
}
