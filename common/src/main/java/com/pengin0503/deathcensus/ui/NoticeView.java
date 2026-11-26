package com.pengin0503.deathcensus.ui;

public record NoticeView(UiText message, String backAction) {
    public NoticeView {
        message = message == null ? new UiText("") : message;
        backAction = backAction == null || backAction.isEmpty() ? "ui/home" : backAction;
    }

    public static NoticeView of(String key, Object... args) {
        return new NoticeView(UiText.of(key, args), "ui/home");
    }
}
