package com.pengin0503.deathcensus.ui;

public record PrivacyView(boolean optOut, UiText explanation) {
    public PrivacyView {
        explanation = explanation == null ? new UiText("") : explanation;
    }
}
