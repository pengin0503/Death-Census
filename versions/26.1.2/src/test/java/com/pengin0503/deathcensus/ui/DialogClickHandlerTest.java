package com.pengin0503.deathcensus.ui;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M13: a modded client's whitespace-padded {@code p} must not abort the click. */
class DialogClickHandlerTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void whitespacePaddedPageProducesNormalUiResult() {
        CompoundTag payload = new CompoundTag();
        payload.putInt("v", 1);
        payload.putString("p", " 5 ");

        String rawPage = payload.getString("p").orElseThrow();
        Map<String, String> inputs = new LinkedHashMap<>();
        // Mirrors the handler's defensive validation, which trims the payload.
        assertTrue(UiActions.parsePage(rawPage).isPresent());
        UiRequest request = DialogClickHandler.requestFromPayload(VIEWER, "history", "",
                rawPage, inputs, "", -1L).orElseThrow();
        assertEquals(5, request.page());
        assertEquals(" 5 ", request.inputs().get("p"), "raw input stays available to the validator");

        UiResult result = assertDoesNotThrow(() -> UiActions.handle(request,
                new FakeUiContext(VIEWER, List.of())),
                "whitespace-padded p must not throw NumberFormatException");
        assertInstanceOf(UiResult.Open.class, result, "expected a normal UI result");
    }
}
