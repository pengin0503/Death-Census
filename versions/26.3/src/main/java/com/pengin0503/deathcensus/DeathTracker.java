package com.pengin0503.deathcensus;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import com.pengin0503.deathcensus.ui.DialogUi;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


/** Records player deaths (server side) and keeps name/baseline state fresh. */
public final class DeathTracker {
    private static final long CAPTURE_WINDOW_MILLIS = 30_000L;
    private static final int LAST_WORDS_MAX_CHARS = 200;

    private record LastChat(String message, long epochMillis) {
    }

    private static final class RecentDeath {
        final UUID victim;
        final long epochMillis;
        final Set<UUID> respects = ConcurrentHashMap.newKeySet();

        RecentDeath(UUID victim, long epochMillis) {
            this.victim = victim;
            this.epochMillis = epochMillis;
        }
    }

    /** Transient: last non-command chat message per player. */
    private static final Map<UUID, LastChat> LAST_CHATS = new ConcurrentHashMap<>();
    /** Transient: recent deaths that can still receive "F" respects. */
    private static final Set<RecentDeath> RECENT_DEATHS = ConcurrentHashMap.newKeySet();
    /** Transient: one respect per sender per identified death, shared by chat and GUI clicks. */
    private static final Set<String> RESPECTED = ConcurrentHashMap.newKeySet();
    private static final int MAX_RESPECTED_KEYS = 10_000;

    private DeathTracker() {
    }

    /** Clears transient chat/death capture state when the server stops. */
    static void resetTransientState() {
        LAST_CHATS.clear();
        RECENT_DEATHS.clear();
        RESPECTED.clear();
    }

    static int currentPlayTicks(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM, Stats.PLAY_TIME);
    }

    static long currentDay(MinecraftServer server) {
        return Math.floorDiv(server.overworld().getOverworldClockTime(), 24000L);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        CensusData.PlayerCensus pc = CensusData.get(server).player(player.getUUID());
        String name = player.getGameProfile().name();
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
    public static void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player == null || player instanceof FakePlayer || !Config.enabled()) {
            return;
        }
        String message = event.getRawText();
        if (message == null || message.startsWith("/")) {
            return;
        }
        long now = System.currentTimeMillis();
        UUID sender = player.getUUID();
        LAST_CHATS.put(sender, new LastChat(message, now));
        RECENT_DEATHS.removeIf(death -> now - death.epochMillis > CAPTURE_WINDOW_MILLIS);

        String trimmed = message.trim();
        if (!trimmed.equals("F") && !trimmed.equals("f")) {
            return;
        }
        RecentDeath latest = null;
        for (RecentDeath death : RECENT_DEATHS) {
            if (death.victim.equals(sender) || now - death.epochMillis > CAPTURE_WINDOW_MILLIS) {
                continue;
            }
            if (latest == null || death.epochMillis > latest.epochMillis) {
                latest = death;
            }
        }
        if (latest == null || latest.respects.contains(sender)) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        final RecentDeath target = latest;
        // ServerChatEvent may fire off the server thread; SavedData must be touched on it.
        // The ConcurrentHashMap captures stay safe, but marking the sender is moved here
        // so the "mark only when addRespect succeeded" semantics remain atomic.
        server.execute(() -> {
            if (payRespect(server, sender, target.victim, target.epochMillis)) {
                target.respects.add(sender);
            }
        });
    }

    /**
     * Applies one "F" to the death identified by {@code (victim, epochMillis)}.
     * Shared by the chat path above and the Dialog UI respect button: both go
     * through {@link CensusData#addRespect(UUID, long)} with the same dedupe
     * semantics, without sending a chat message.
     */
    public static boolean payRespect(MinecraftServer server, UUID sender, UUID victim, long epochMillis) {
        if (server == null || sender == null || victim == null || epochMillis < 0L) {
            return false;
        }
        String key = sender + "|" + victim + "|" + epochMillis;
        if (!RESPECTED.add(key)) {
            return false;
        }
        if (!CensusData.get(server).addRespect(victim, epochMillis)) {
            RESPECTED.remove(key);
            return false;
        }
        // Bounded transient state; cleared on server stop.
        if (RESPECTED.size() > MAX_RESPECTED_KEYS) {
            RESPECTED.clear();
        }
        return true;
    }

    // LOWEST so a death cancelled by another mod (revive/second-chance) is never
    // delivered here (cancelled events skip non-receiveCanceled listeners).
    @SubscribeEvent(priority = EventPriority.LOWEST)
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
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        DamageSource source = event.getSource();

        String causeId = causeId(player, source);
        Entity attacker = source.getEntity();
        Entity direct = source.getDirectEntity();
        boolean attackerIsPlayer = attacker instanceof net.minecraft.world.entity.player.Player;
        String category = CauseClassifier.classify(causeId, attackerIsPlayer);

        Entity killer = attacker != null ? attacker : direct;
        String killerType = "";
        String killerName = "";
        if (killer != null) {
            Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(killer.getType());
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
            // Baseline unknown (e.g. record restored while online): count survival from now, never inflate it.
            pc.baselinePlayTicks = playTicks;
        }
        int survival = CensusLogic.survivalTicks(playTicks, pc.lastDeathPlayTicks, pc.baselinePlayTicks);

        String playerName = player.getGameProfile().name() != null ? player.getGameProfile().name() : "";
        long deathEpochMillis = System.currentTimeMillis();
        String lastWords = lastWordsFor(player.getUUID(), deathEpochMillis);
        DeathEntry entry = new DeathEntry(
                player.getUUID().toString(),
                playerName,
                deathEpochMillis,
                currentDay(server),
                playTicks,
                survival,
                player.level().dimension().identifier().toString(),
                player.getX(), player.getY(), player.getZ(),
                category,
                causeId,
                source.getLocalizedDeathMessage(player).getString(),
                killerType,
                killerName,
                mode.getName(),
                player.experienceLevel,
                "",
                lastWords,
                0);

        // Lifetime counters/categories survive record trimming; retained lists do not.
        // recordDeath increments the counter, so snapshot it first (same ordering as before).
        int totalDeathsBefore = (int) Math.min(data.lifetimeDeaths(), (long) Integer.MAX_VALUE);
        int playerDeathsBefore = pc.deaths.size();
        int playerDistinctCategoriesBefore = pc.lifetimeCategories.size();
        boolean optedOut = pc.optOutPublic;

        data.recordDeath(entry, Config.maxRecordsPerPlayer());
        RECENT_DEATHS.removeIf(death -> deathEpochMillis - death.epochMillis > CAPTURE_WINDOW_MILLIS);
        RECENT_DEATHS.add(new RecentDeath(player.getUUID(), deathEpochMillis));
        DeathCensus.LOGGER.info("[DeathCensus] Recorded death of {} ({})", playerName, category);
        // PR-4: one compact, per-viewer extra line. The vanilla death message
        // is untouched; the adapter gates each viewer with UiPrivacy and only
        // includes the victim name plus (uuid, epoch) click targets.
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (!(viewer instanceof FakePlayer)) {
                DialogUi.sendDeathLine(viewer, player.getUUID(), deathEpochMillis, playerName);
            }
        }
        if (!pc.optOutPublic) {
            DiscordNotifier.notifyDeath(entry);
        }
        if (Config.milestoneAnnouncements()) {
            int playerDistinctCategoriesAfter = pc.lifetimeCategories.size();
            for (Milestones.Milestone milestone : Milestones.evaluate(
                    totalDeathsBefore,
                    playerDeathsBefore,
                    playerDistinctCategoriesBefore,
                    playerDistinctCategoriesAfter,
                    entry)) {
                announceMilestone(server, playerName, milestone, optedOut);
            }
        }
    }

    private static void announceMilestone(MinecraftServer server, String playerName,
                                          Milestones.Milestone milestone, boolean optedOut) {
        Milestones.Kind kind = milestone.kind();
        // CATEGORY_COMPLETE is derived from stored records (DESIGN §8): never disclose it
        // for a player who opted out of public record-derived views.
        if (optedOut && kind == Milestones.Kind.CATEGORY_COMPLETE) {
            return;
        }
        String key = kind.langKey();
        Object arg = kind == Milestones.Kind.TOTAL_COUNT
                ? Integer.valueOf(milestone.value())
                : playerName;
        // SERVER_FIRST/PERSONAL_FIRST only restate the vanilla death message everyone saw,
        // but the opt-out message promises no Discord posts. TOTAL_COUNT names nobody and
        // counts opted-out deaths in the anonymous total (DESIGN §8), so it posts normally.
        server.getPlayerList().broadcastSystemMessage(ServerLang.tr(key, arg), false);
        if (optedOut && (kind == Milestones.Kind.SERVER_FIRST || kind == Milestones.Kind.PERSONAL_FIRST)) {
            return;
        }
        DiscordNotifier.notifyMilestone(ServerLang.formatForLang(Config.discordLanguage(), key, arg));
    }

    private static String lastWordsFor(UUID player, long deathEpochMillis) {
        LastChat chat = LAST_CHATS.get(player);
        if (chat == null || deathEpochMillis < chat.epochMillis()
                || deathEpochMillis - chat.epochMillis() > CAPTURE_WINDOW_MILLIS) {
            return "";
        }
        String message = chat.message();
        return message.length() <= LAST_WORDS_MAX_CHARS
                ? message
                : message.substring(0, LAST_WORDS_MAX_CHARS);
    }

    private static String causeId(ServerPlayer player, DamageSource source) {
        try {
            Identifier key = player.level().registryAccess()
                    .lookupOrThrow(Registries.DAMAGE_TYPE).getKey(source.type());
            if (key != null) {
                return key.toString();
            }
        } catch (RuntimeException ignored) {
            // fall through to the message id
        }
        return "minecraft:" + source.type().msgId();
    }
}
