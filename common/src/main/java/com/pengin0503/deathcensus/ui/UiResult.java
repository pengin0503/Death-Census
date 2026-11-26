package com.pengin0503.deathcensus.ui;

/** Dispatcher output. Adapters switch on {@link Open#view()} or read side effects. */
public sealed interface UiResult permits UiResult.Open, UiResult.SideEffect, UiResult.NoChange {

    /** Opens one of the common view models. */
    record Open(Object view) implements UiResult {
        public Open(HomeView view) {
            this((Object) view);
        }

        public Open(HistoryView view) {
            this((Object) view);
        }

        public Open(DeathDetailView view) {
            this((Object) view);
        }

        public Open(RankingView view) {
            this((Object) view);
        }

        public Open(PrivacyView view) {
            this((Object) view);
        }

        public Open(NoticeView view) {
            this((Object) view);
        }

        public Open(ConfirmationView view) {
            this((Object) view);
        }
    }

    /** A mutation was applied (or attempted) and carries a follow-up view. */
    record SideEffect(String action, boolean applied, UiText feedback, Open followUp) implements UiResult {
    }

    /** Malformed or unknown input: nothing was read or changed. */
    record NoChange(String reason) implements UiResult {
    }
}
