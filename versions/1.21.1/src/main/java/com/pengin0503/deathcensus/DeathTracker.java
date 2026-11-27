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
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingUseTotemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import com.pengin0503.deathcensus.ui.ChatUi;

import java.util.ArrayList;
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
    /** Transient: dedupe keys for UI/chat respects, shared with the UI adapter. */
    private static final Set<String> RESPECTED = ConcurrentHashMap.newKeySet();
    private static final int MAX_RESPECTED_KEYS = 10_000;
    /** PR-9 (§8): bounded per-player rings and one-notice-per-key dedupe; never persisted. */
    private static final DeathLoopTracker LOOP_TRACKER = new DeathLoopTracker();
    /** PR-10 (§9): transient, self-only return guide; no persisted state. */
    private static final RespawnGuideTracker GUIDE_TRACKER = new RespawnGuideTracker();

    /** PR-11 (§10): bounded dedupe keys for cancel/totem saves; transient only. */
    private static final java.util.Set<String> NEAR_DEATH_SEEN = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int MAX_NEAR_DEATH_KEYS = 2_000;

    private DeathTracker() {
    }

    /** Clears transient chat/death capture state when the server stops. */
    static void resetTransientState() {
        LAST_CHATS.clear();
        RECENT_DEATHS.clear();
        RESPECTED.clear();
        LOOP_TRACKER.clear();
        GUIDE_TRACKER.clear();
        NEAR_DEATH_SEEN.clear();
    }

    static int currentPlayTicks(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM, Stats.PLAY_TIME);
    }

    static long currentDay(MinecraftServer server) {
        return Math.floorDiv(server.overworld().getDayTime(), 24000L);
    }

    /**
     * S2: chronicle totals count only deaths recorded by this mod. Milestones
     * keep using the baseline-inflated {@link CensusData#lifetimeDeaths()}.
     */
    static int chronicleTotalBefore(CensusData data) {
        if (data == null) {
            return 0;
        }
        return (int) Math.min(Math.max(0L, data.storedLifetimeDeaths()), (long) Integer.MAX_VALUE);
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
        MinecraftServer server = player.getServer();
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
     * Shared by the chat path above and the UI respect button: both go through
     * {@link CensusData#addRespect(UUID, long)} with the same dedupe semantics.
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

        Entity killer = attacker != null ? attacker : direct;
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
            // Baseline unknown (e.g. record restored while online): count survival from now, never inflate it.
            pc.baselinePlayTicks = playTicks;
        }
        int survival = CensusLogic.survivalTicks(playTicks, pc.lastDeathPlayTicks, pc.baselinePlayTicks);

        String playerName = player.getGameProfile().getName() != null ? player.getGameProfile().getName() : "";
        long deathEpochMillis = System.currentTimeMillis();
        String lastWords = lastWordsFor(player.getUUID(), deathEpochMillis);
        List<WitnessRef> witnesses = captureWitnesses(server, player);
        DeathContext context = captureContext(player);
        DeathEntry entry = new DeathEntry(
                player.getUUID().toString(),
                playerName,
                deathEpochMillis,
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
                player.experienceLevel,
                "",
                lastWords,
                0,
                witnesses,
                context);

        // Lifetime counters/categories survive record trimming; retained lists do not.
        // recordDeath increments the counter, so snapshot it first (same ordering as before).
        // Milestones use the baseline-inflated total; chronicle rows use the stored one (S2).
        int totalDeathsBefore = (int) Math.min(data.lifetimeDeaths(), (long) Integer.MAX_VALUE);
        int chronicleDeathsBefore = chronicleTotalBefore(data);
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
                ChatUi.sendDeathLine(viewer, player.getUUID(), deathEpochMillis, playerName);
            }
        }
        // M14: the snapshot-wide dictionary is built only when a realtime post can
        // actually happen; opt-out-only users never pay for it.
        if (!pc.optOutPublic && !pc.discordOptOut
                && Config.discordNotifyDeaths() && DiscordNotifier.realtimeWebhookConfigured()) {
            DiscordNotifier.notifyDeath(entry, new PlayerPrivacy(
                    pc.optOutPublic, pc.hideCoordinates, pc.hidePvpOpponent, pc.discordOptOut),
                    HiddenIdentities.of(data.snapshot()));
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
        // PR-5: chronicle persistence is independent of announcements and opt-outs;
        // hidden names are scrubbed when a public surface renders the stored row.
        if (Config.chronicleEnabled()) {
            boolean netherSeen = data.chronicleEvents().stream()
                    .anyMatch(e -> e.kind() == ChronicleEvent.Kind.NETHER_FIRST);
            List<ChronicleEvent> additions = ChronicleRules.eventsFor(entry,
                    new ChronicleRules.Counters(chronicleDeathsBefore, netherSeen,
                            playerDistinctCategoriesBefore, pc.lifetimeCategories.size()));
            data.addChronicleEvents(additions);
        }
        // PR-9 (§8): feed only public-location deaths into the bounded ring. The
        // tracker is O(16) per death and the notice goes to the victim alone.
        if (Config.deathLoopEnabled() && !pc.optOutPublic && !pc.hideCoordinates) {
            DeathLoopSample mark = new DeathLoopSample(player.getUUID().toString(), playerName,
                    deathEpochMillis, player.getX(), player.getY(), player.getZ(),
                    entry.dimension(), entry.causeCategory());
            for (DeathLoop loop : LOOP_TRACKER.observe(mark, Config.deathLoopRadiusBlocks(),
                    Config.deathLoopWindowMillis(), Config.deathLoopMinDeaths())) {
                if (Config.deathLoopNotifyVictim()) {
                    player.sendSystemMessage(ServerLang.tr(player,
                            "command.deathcensus.loops.detected", loop.count(),
                            loop.dimension(), Config.deathLoopWindowMinutes(),
                            loop.positionLabel()));
                }
            }
        }

        // PR-10 (§9): a fresh death replaces the previous guide (the "second death"
        // stop condition). Privacy flags do not apply: this is the victim's own
        // position, sent only to the victim, never to a public surface.
        if (Config.enabled() && Config.respawnGuideEnabled()) {
            GUIDE_TRACKER.begin(player.getUUID(), entry.dimension(), player.getX(), player.getY(),
                    player.getZ(), deathEpochMillis, guideTick(player), Config.respawnGuideMaxTicks());
        } else {
            GUIDE_TRACKER.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return;
        }
        UUID uuid = player.getUUID();
        RespawnGuideState state = GUIDE_TRACKER.get(uuid);
        if (state == null) {
            return;
        }
        if (!Config.enabled() || !Config.respawnGuideEnabled()) {
            GUIDE_TRACKER.remove(uuid);
            return;
        }
        long tick = guideTick(player);
        if (state.expired(tick)) {
            GUIDE_TRACKER.remove(uuid);
            return;
        }
        String dimension = dimensionId(player);
        if (!state.sameDimension(dimension)) {
            // §9: show the dimension id; never invent a cross-dimension bearing.
            player.sendSystemMessage(ServerLang.tr(player,
                    "command.deathcensus.respawn.different_dimension",
                    state.dimension(), RespawnGuideText.remainingTime(state.remainingTicks(tick))));
            GUIDE_TRACKER.remove(uuid);
            return;
        }
        double dx = state.x() - player.getX();
        double dy = state.y() - player.getY();
        double dz = state.z() - player.getZ();
        int distance = RespawnGuideText.horizontalDistance(dx, dz);
        int height = RespawnGuideText.heightDifference(state.y(), player.getY());
        String remaining = RespawnGuideText.remainingTime(state.remainingTicks(tick));
        // The victim only: no broadcast, no witness data, no public-surface lookup.
        player.sendSystemMessage(ServerLang.tr(player, "command.deathcensus.respawn.chat",
                RespawnGuideText.compass(dx, dz), distance,
                RespawnGuideText.signedHeight(height), remaining));
        // 1.21.1 exposes displayClientMessage; the 26.x trees use the equivalent
        // sendSystemMessage(component, true) overlay overload (no custom packets).
        player.displayClientMessage(ServerLang.tr(player, "command.deathcensus.respawn.actionbar",
                RespawnGuideText.compass(dx, dz), distance,
                RespawnGuideText.signedHeight(height), remaining), true);
        state.markShown(tick);
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            GUIDE_TRACKER.remove(player.getUUID());
        }
    }

    /** Exact dimension id of the player's current level, per version API. */
    private static String dimensionId(ServerPlayer player) {
        return player.level().dimension().location().toString();
    }

    /** Server tick driving §9 expiry and the ActionBar throttle. */
    private static long guideTick(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return server != null ? server.getTickCount() : player.level().getGameTime();
    }

    /** Called from the existing ServerTickEvent.Post handler. */
    static void tickGuides(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (!Config.enabled() || !Config.respawnGuideEnabled()) {
            // A runtime config change also drops pending guides; never send stale ones.
            GUIDE_TRACKER.clear();
            return;
        }
        long tick = server.getTickCount();
        GUIDE_TRACKER.pruneExpired(tick);
        // Never iterate other players' guide states: look up each viewer's own UUID.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof FakePlayer) {
                continue;
            }
            UUID uuid = player.getUUID();
            RespawnGuideState state = GUIDE_TRACKER.get(uuid);
            if (state == null || !state.shown()) {
                continue;
            }
            if (state.expired(tick) || !state.sameDimension(dimensionId(player))) {
                GUIDE_TRACKER.remove(uuid);
                continue;
            }
            if (!state.dueForRefresh(tick, Config.respawnGuideRefreshTicks())) {
                continue;
            }
            double dx = state.x() - player.getX();
            double dz = state.z() - player.getZ();
            if (RespawnGuideText.arrived(dx, dz, Config.respawnGuideArrivalBlocks())) {
                player.sendSystemMessage(ServerLang.tr(player, "command.deathcensus.respawn.arrival"));
                GUIDE_TRACKER.remove(uuid);
                continue;
            }
            int distance = RespawnGuideText.horizontalDistance(dx, dz);
            int height = RespawnGuideText.heightDifference(state.y(), player.getY());
            String remaining = RespawnGuideText.remainingTime(state.remainingTicks(tick));
            player.displayClientMessage(ServerLang.tr(player, "command.deathcensus.respawn.actionbar",
                    RespawnGuideText.compass(dx, dz), distance,
                    RespawnGuideText.signedHeight(height), remaining), true);
            state.markRefreshed(tick);
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

    /**
     * PR-2 bounded witness scan (DESIGN 1.4.0 §1): one pass over the online
     * list, squared-distance comparison, same dimension, no fake players, no
     * chat/network/disk I/O. Both gates are checked before any allocation.
     */
    static List<WitnessRef> captureWitnesses(MinecraftServer server, ServerPlayer victim) {
        if (server == null || victim == null || !Config.captureWitnesses()) {
            return List.of();
        }
        int cap = Config.witnessMaxPerDeath();
        if (cap <= 0) {
            return List.of(); // escape hatch: no scan, no stored list
        }
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        if (online.isEmpty()) {
            return List.of();
        }
        double radius = (double) Config.witnessRadiusBlocks();
        double radiusSquared = radius * radius;
        String dimension = victim.level().dimension().location().toString();
        List<WitnessAnalysis.Candidate> candidates = new ArrayList<>();
        for (ServerPlayer other : online) {
            if (other == victim || other instanceof FakePlayer || other.level() == null) {
                continue;
            }
            if (!dimension.equals(other.level().dimension().location().toString())) {
                continue;
            }
            double dx = other.getX() - victim.getX();
            double dy = other.getY() - victim.getY();
            double dz = other.getZ() - victim.getZ();
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared > radiusSquared) {
                continue;
            }
            String name = other.getGameProfile().getName();
            candidates.add(new WitnessAnalysis.Candidate(
                    other.getUUID().toString(), name == null ? "" : name, distanceSquared));
        }
        return WitnessAnalysis.nearest(candidates, cap);
    }

    /**
     * PR-3 bounded death-context capture (DESIGN 1.4.0 §11). The version-local
     * helper reads only registry ids/level flags behind one config gate; common
     * code does the normalization and bounding. No I/O happens here.
     */
    static DeathContext captureContext(ServerPlayer player) {
        return DeathContextCapture.capture(player);
    }

    /** Optional server-aware overload for callers that already hold the server. */
    static DeathContext captureContext(MinecraftServer server, ServerPlayer player) {
        return captureContext(player);
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

    // ------------------------------------------------------- near death (§10)

    /**
     * PR-11: a cancelled death is a save, not a DeathEntry. receiveCanceled is
     * essential because the recording listener ignores cancelled death events.
     */
    @SubscribeEvent(receiveCanceled = true, priority = EventPriority.LOW)
    public static void onLivingDeathCancelled(LivingDeathEvent event) {
        if (!event.isCanceled()) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            recordNearDeath(player, event.getSource(), NearDeathEvent.Kind.CANCELLED);
        }
    }

    /** PR-11: the version's totem event is observed directly, never guessed. */
    @SubscribeEvent
    public static void onTotemUsed(net.neoforged.neoforge.event.entity.living.LivingUseTotemEvent event) {
        if (event.isCanceled()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Object itemKey = BuiltInRegistries.ITEM.getKey(event.getTotem().getItem());
        if (itemKey == null || !"minecraft:totem_of_undying".equals(itemKey.toString())) {
            return;
        }
        recordNearDeath(player, event.getSource(), NearDeathEvent.Kind.TOTEM);
    }

    /** Config-gated, bounded and deduped; never touches DeathEntry or counters. */
    private static void recordNearDeath(ServerPlayer player, DamageSource source, NearDeathEvent.Kind kind) {
        if (!Config.nearDeathCaptureEnabled() || player instanceof FakePlayer) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        String causeId = causeId(player, source);
        String category = CauseClassifier.classify(causeId,
                source.getEntity() instanceof net.minecraft.world.entity.player.Player);
        String name = player.getGameProfile().getName();
        NearDeathEvent event = new NearDeathEvent(player.getUUID().toString(),
                name == null ? "" : name, System.currentTimeMillis(),
                player.level().dimension().location().toString(), player.getX(), player.getY(), player.getZ(),
                category, causeId, kind);
        String key = NearDeathAnalysis.dedupeKey(event, Config.nearDeathDedupWindowMillis());
        if (NEAR_DEATH_SEEN.size() >= MAX_NEAR_DEATH_KEYS) {
            NEAR_DEATH_SEEN.clear(); // bounded transient state, never grows with playtime
        }
        if (!NEAR_DEATH_SEEN.add(key)) {
            return;
        }
        server.execute(() -> CensusData.get(server).addNearDeathEvent(event,
                Config.nearDeathMaxPerPlayer(), Config.nearDeathDedupWindowMillis()));
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
}
