package com.pengin0503.deathcensus;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Version-side checks for DESIGN 1.3.0 §1: safe config defaults and unchanged payload shape. */
class DiscordNotifierTest {

    @Test
    void retryConfigAccessorsReturnDefaultsWithoutLoadedConfig() {
        assertEquals(4, Config.discordRetryMaxAttempts());
        assertEquals(1000, Config.discordRetryBaseDelayMillis());
    }

    @Test
    void payloadJsonStillCapsContentAndDisablesMentions() {
        JsonObject body = JsonParser.parseString(
                DiscordNotifier.payloadJson("x".repeat(2500))).getAsJsonObject();
        String content = body.get("content").getAsString();
        assertEquals(2000, content.length());
        assertEquals(0, body.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
    }
}
