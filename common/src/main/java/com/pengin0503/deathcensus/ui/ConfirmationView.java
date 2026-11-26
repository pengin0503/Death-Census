package com.pengin0503.deathcensus.ui;

public record ConfirmationView(UiText title, UiText body, UiActionRef yes, UiActionRef no) {
    public ConfirmationView {
        title = title == null ? new UiText("") : title;
        body = body == null ? new UiText("") : body;
        yes = yes == null ? new UiActionRef("ui/home") : yes;
        no = no == null ? new UiActionRef("ui/home") : no;
    }
}
