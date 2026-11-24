package com.pengin0503.deathcensus;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import javax.annotation.Nullable;

/** Records player deaths (server side) and keeps name/baseline state fresh. */
public final class DeathTracker {
    private DeathTracker() {
    }

    static int currentPlayTicks(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM, Stats.PLAY_TIME);
    }

    static long currentDay(MinecraftServer server) {
        return Math.floorDiv(server.overworld().getDayTime(), 24000L);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        CensusData.PlayerCensus pc = CensusData.get(server).player(player.getUUID());
        String name = player.getGameProfile().getName();
        boolean changed = false;
        if (name != null && !name.equals(pc.lastKnownName)) {
            pc.lastKnownName = name;
            changed = true;
        }
        if (pc.baselinePlayTicks < 0) {
            pc.baselinePlayTicks = currentPlayTicks(player);
            changed = true;
        }
        if (changed) {
            CensusData.get(server).setDirty();
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return;
        }
        if (!Config.enabled()) {
            return;
        }
        GameType mode = player.gameMode.getGameModeForPlayer();
        if (!Config.recordCreativeDeaths() && (mode == GameType.CREATIVE || mode == GameType.SPECTATOR)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        DamageSource source = event.getSource();

        String causeId = causeId(player, source);
        Entity attacker = source.getEntity();
        Entity direct = source.getDirectEntity();
        boolean attackerIsPlayer = attacker instanceof net.minecraft.world.entity.player.Player;
        String category = CauseClassifier.classify(causeId, attackerIsPlayer);

        Entity killer = direct != null ? direct : attacker;
        String killerType = "";
        String killerName = "";
        if (killer != null) {
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(killer.getType());
            killerType = key != null ? key.toString() : "";
            killerName = killer.getDisplayName() != null ? killer.getDisplayName().getString() : "";
            if (killer == player) {
                killerType = "";
                killerName = "";
            }
        }

        CensusData data = CensusData.get(server);
        CensusData.PlayerCensus pc = data.player(player.getUUID());
        int playTicks = currentPlayTicks(player);
        if (pc.baselinePlayTicks < 0) {
            pc.baselinePlayTicks = 0;
        }
        int survival = CensusLogic.survivalTicks(playTicks, pc.lastDeathPlayTicks, pc.baselinePlayTicks);

        String playerName = player.getGameProfile().getName() != null ? player.getGameProfile().getName() : "";
        DeathEntry entry = new DeathEntry(
                player.getUUID().toString(),
                playerName,
                System.currentTimeMillis(),
                currentDay(server),
                playTicks,
                survival,
                player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(),
                category,
                causeId,
                source.getLocalizedDeathMessage(player).getString(),
                killerType,
                killerName,
                mode.getName(),
                player.experienceLevel);

        data.recordDeath(entry, Config.maxRecordsPerPlayer());
        DeathCensus.LOGGER.info("[DeathCensus] Recorded death of {} ({})", playerName, category);
        if (!pc.optOutPublic) {
            DiscordNotifier.notifyDeath(entry);
        }
    }

    private static String causeId(ServerPlayer player, DamageSource source) {
        try {
            ResourceLocation key = player.level().registryAccess()
                    .registryOrThrow(Registries.DAMAGE_TYPE).getKey(source.type());
            if (key != null) {
                return key.toString();
            }
        } catch (RuntimeException ignored) {
            // fall through to the message id
        }
        return "minecraft:" + source.type().msgId();
    }

    @Nullable
    static ServerPlayer unused() {
        return null;
    }
}
