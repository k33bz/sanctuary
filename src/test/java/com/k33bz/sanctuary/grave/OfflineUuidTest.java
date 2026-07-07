package com.k33bz.sanctuary.grave;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OfflineUuidTest {

    /** Known vectors: md5("OfflinePlayer:" + name) as a version-3 UUID, verified externally. */
    @Test
    void matchesVanillaDerivation() {
        assertEquals(UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f"),
                OfflineUuid.of("Notch"));
        assertEquals(UUID.fromString("6ee87966-7edb-376e-a5c2-83cb256633ef"),
                OfflineUuid.of("k33bz"));
    }

    @Test
    void caseSensitiveLikeVanilla() {
        assertNotEquals(OfflineUuid.of("k33bz"), OfflineUuid.of("K33bz"));
    }
}
