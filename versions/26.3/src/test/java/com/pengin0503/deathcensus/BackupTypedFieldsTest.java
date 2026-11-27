package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M12: tolerant backup loading defaults wrong-typed primitives; it never coerces them. */
class BackupTypedFieldsTest {
    private static final String UUID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Test
    void wrongTypedBackupPrimitivesDefaultInsteadOfCoercing() {
        String json = "{\"players\":[{\"uuid\":\"" + UUID_A + "\",\"name\":true,"
                + "\"vanillaDeathsBaseline\":\"not-a-number\","
                + "\"deaths\":[{\"epochMillis\":123,\"playerName\":7,\"survivalTicks\":true,"
                + "\"playTicks\":\"bad\",\"causeCategory\":false}]}]}";
        List<PlayerDeaths> parsed = CensusExport.parseBackupJson(json);
        assertNotNull(parsed);
        assertEquals(1, parsed.size());
        assertEquals("", parsed.get(0).name());
        assertEquals(0, parsed.get(0).vanillaDeathsBaseline());
        DeathEntry death = parsed.get(0).deaths().get(0);
        assertEquals(123L, death.epochMillis());
        assertEquals("", death.playerName());
        assertEquals(0, death.survivalTicks());
        assertEquals(0, death.playTicks());
        assertEquals("", death.causeCategory());
    }

    @Test
    void numericUuidIsRejectedRatherThanCoercedToString() {
        assertTrue(CensusExport.parseBackupJson(
                "{\"players\":[{\"uuid\":7,\"name\":\"A\"}]}").isEmpty());
    }
}
