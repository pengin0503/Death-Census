package com.pengin0503.deathcensus;

import com.mojang.logging.LogUtils;
import com.pengin0503.deathcensus.ui.DialogClickHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Death Census — server-side death records, cause statistics and
 * leaderboards for NeoForge. Vanilla clients can join; all tracking,
 * storage and commands run on the server only.
 */
@Mod(DeathCensus.MOD_ID)
public class DeathCensus {
    public static final String MOD_ID = "deathcensus";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DeathCensus(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        NeoForge.EVENT_BUS.register(DeathTracker.class);
        NeoForge.EVENT_BUS.register(CensusCommands.class);
        NeoForge.EVENT_BUS.register(DialogClickHandler.class);
        NeoForge.EVENT_BUS.addListener(DeathCensus::onServerStarted);
        NeoForge.EVENT_BUS.addListener(DeathCensus::onServerTick);
        NeoForge.EVENT_BUS.addListener(DeathCensus::onServerStopping);
        NeoForge.EVENT_BUS.addListener(DeathCensus::onServerStopped);
        NeoForge.EVENT_BUS.addListener(DeathCensus::onLevelSave);

        LOGGER.info("Death Census loaded");
    }

    private static void onServerStarted(ServerStartedEvent event) {
        DiscordNotifier.checkDigestOnServerStart(event.getServer());
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        DiscordNotifier.tickDigest(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        CensusExport.autoBackupOnStop(event.getServer());
        DiscordNotifier.shutdown();
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        DiscordNotifier.resetDigestThrottler();
        CensusExport.resetAutoBackupThrottler();
        DeathTracker.resetTransientState();
    }

    private static void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            CensusExport.autoBackupOnWorldSave(level.getServer());
        }
    }
}
