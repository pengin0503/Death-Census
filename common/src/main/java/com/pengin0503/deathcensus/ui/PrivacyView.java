package com.pengin0503.deathcensus.ui;

import com.pengin0503.deathcensus.PlayerPrivacy;

/**
 * Read model for the privacy screen: four independent settings plus the
 * localized explanation rendered by both 1.2.0 UI adapters (DESIGN §7).
 */
public record PrivacyView(
        boolean optOut,
        boolean hideCoordinates,
        boolean hidePvpOpponent,
        boolean discordOptOut,
        UiText explanation
) {
    public PrivacyView {
        explanation = explanation == null ? new UiText("") : explanation;
    }

    /** Backwards-compatible two-state view (the other flags default to off). */
    public PrivacyView(boolean optOut, UiText explanation) {
        this(optOut, false, false, false, explanation);
    }

    public static PrivacyView of(PlayerPrivacy privacy, UiText explanation) {
        PlayerPrivacy effective = privacy == null ? PlayerPrivacy.none() : privacy;
        return new PrivacyView(
                effective.optOutPublic(),
                effective.hideCoordinates(),
                effective.hidePvpOpponent(),
                effective.discordOptOut(),
                explanation);
    }
}
