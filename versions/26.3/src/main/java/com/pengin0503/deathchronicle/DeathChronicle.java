package com.pengin0503.deathchronicle;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Death Chronicle — server-side death records, cause statistics and
 * leaderboards for NeoForge. Vanilla clients can join; all tracking,
 * storage and commands run on the server only.
 */
@Mod(DeathChronicle.MOD_ID)
public class DeathChronicle {
    public static final String MOD_ID = "deathchronicle";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DeathChronicle(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.LOCAL, Config.SPEC);

        NeoForge.EVENT_BUS.register(DeathTracker.class);
        NeoForge.EVENT_BUS.register(ChronicleCommands.class);
        NeoForge.EVENT_BUS.addListener(DeathChronicle::onServerStarted);
        NeoForge.EVENT_BUS.addListener(DeathChronicle::onServerTick);
        NeoForge.EVENT_BUS.addListener(DeathChronicle::onServerStopping);
        NeoForge.EVENT_BUS.addListener(DeathChronicle::onServerStopped);

        LOGGER.info("Death Chronicle loaded");
    }

    private static void onServerStarted(ServerStartedEvent event) {
        DiscordNotifier.checkDigestOnServerStart(event.getServer());
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        DiscordNotifier.tickDigest(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        ChronicleExport.autoBackupOnStop(event.getServer());
        DiscordNotifier.shutdown();
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        DiscordNotifier.resetDigestThrottler();
    }
}
