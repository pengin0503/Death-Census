package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.DeathEntry;
import com.pengin0503.deathcensus.PlayerDeaths;
import com.pengin0503.deathcensus.PlayerPrivacy;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiDeleteIndexTest {

    /**
     * Real adapters remove the deleted record from the live snapshot as part of
     * a successful delete; FakeUiContext only logs the call. This wrapper gives
     * the delete-confirmation path that adapter behaviour without changing the
     * shared fixture used by the rest of the UI tests.
     */
    private static final class DeletingUiContext implements UiContext {
        private final FakeUiContext delegate;
        private final List<PlayerDeaths> beforeDelete;
        private final List<PlayerDeaths> afterDelete;
        private boolean deleted;

        DeletingUiContext(FakeUiContext delegate, UUID uuid, long epoch) {
            this.delegate = delegate;
            this.beforeDelete = delegate.snapshot();
            List<PlayerDeaths> remaining = new ArrayList<>();
            for (PlayerDeaths player : beforeDelete) {
                if (player != null && player.uuid().equalsIgnoreCase(uuid.toString())) {
                    List<DeathEntry> deaths = player.deaths().stream()
                            .filter(death -> death.epochMillis() != epoch).toList();
                    remaining.add(new PlayerDeaths(player.uuid(), player.name(), player.optOutPublic(),
                            player.hideCoordinates(), player.hidePvpOpponent(), player.discordOptOut(),
                            deaths, player.vanillaDeathsBaseline(), player.vanillaBaselineCauses(),
                            player.nearDeaths()));
                } else {
                    remaining.add(player);
                }
            }
            this.afterDelete = List.copyOf(remaining);
        }

        @Override
        public UUID viewer() {
            return delegate.viewer();
        }

        @Override
        public List<PlayerDeaths> snapshot() {
            return deleted ? afterDelete : beforeDelete;
        }

        @Override
        public boolean isAdmin() {
            return delegate.isAdmin();
        }

        @Override
        public boolean isSelf(UUID uuid) {
            return delegate.isSelf(uuid);
        }

        @Override
        public boolean isOptedOut(UUID uuid) {
            return delegate.isOptedOut(uuid);
        }

        @Override
        public boolean canManagePrivacy(UUID targetUuid) {
            return delegate.canManagePrivacy(targetUuid);
        }

        @Override
        public PlayerPrivacy getPrivacyFlags(UUID uuid) {
            return delegate.getPrivacyFlags(uuid);
        }

        @Override
        public void setPrivacyFlag(UUID uuid, PlayerPrivacy.Flag flag, boolean value) {
            delegate.setPrivacyFlag(uuid, flag, value);
        }

        @Override
        public int dataVersion() {
            return delegate.dataVersion();
        }

        @Override
        public int currentSurvivalTicks(UUID uuid) {
            return delegate.currentSurvivalTicks(uuid);
        }

        @Override
        public ZoneId zoneId() {
            return delegate.zoneId();
        }

        @Override
        public long nowMillis() {
            return delegate.nowMillis();
        }

        @Override
        public UiSession currentSession() {
            return delegate.currentSession();
        }

        @Override
        public UiSession newSession(HistoryFilter filter, UiSession.RankingSelection ranking) {
            return delegate.newSession(filter, ranking);
        }

        @Override
        public UiSession touchSession(UiSession presented, HistoryFilter filter, UiSession.RankingSelection ranking) {
            return delegate.touchSession(presented, filter, ranking);
        }

        @Override
        public void invalidateSession() {
            delegate.invalidateSession();
        }

        @Override
        public void sendNotice(UiText text) {
            delegate.sendNotice(text);
        }

        @Override
        public boolean setExcuse(UUID uuid, long epoch, String text) {
            return delegate.setExcuse(uuid, epoch, text);
        }

        @Override
        public boolean payRespect(UUID sender, UUID victim, long epoch) {
            return delegate.payRespect(sender, victim, epoch);
        }

        @Override
        public boolean deleteAfterBackup(UUID uuid, long epoch) {
            boolean applied = delegate.deleteAfterBackup(uuid, epoch);
            if (applied) {
                deleted = true;
            }
            return applied;
        }

        @Override
        public void setOptOut(boolean optOut) {
            delegate.setOptOut(optOut);
        }
    }

    @Test
    void deleteConfirmationReportsIndexOfDeletedDeathNotOne() {
        List<PlayerDeaths> players = List.of(TestData.player(TestData.BOB, "Bob", true,
                TestData.death(TestData.BOB, "Bob", 1_000L, "fall"),
                TestData.death(TestData.BOB, "Bob", 2_000L, "lava"),
                TestData.death(TestData.BOB, "Bob", 3_000L, "drown"),
                TestData.death(TestData.BOB, "Bob", 4_000L, "mob")));
        FakeUiContext base = new FakeUiContext(TestData.ALICE, players);
        base.admin = true;
        base.newSession(HistoryFilter.DEFAULT, UiSession.DEFAULT_RANKING);
        DeletingUiContext ctx = new DeletingUiContext(base, TestData.BOB, 4_000L);

        UiRequest confirm = new UiRequest(TestData.ALICE, "delete_confirm", base.token(), 1,
                TestData.BOB.toString(), 4_000L, Map.of());
        UiResult.SideEffect deleted = assertInstanceOf(UiResult.SideEffect.class,
                UiActions.handle(confirm, ctx));

        assertTrue(deleted.applied());
        assertTrue(base.calls.contains("delete:" + TestData.BOB + ":4000"));
        assertEquals("command.deathcensus.delete.done", deleted.feedback().key());
        assertEquals(Integer.valueOf(4), deleted.feedback().args()[1]);
        // Guard the premise: the target is absent from the live snapshot after
        // deletion, so a post-delete lookup cannot recover the original index.
        assertTrue(ctx.snapshot().get(0).deaths().stream().noneMatch(d -> d.epochMillis() == 4_000L));
        assertInstanceOf(HistoryView.class, deleted.followUp().view());
    }
}
