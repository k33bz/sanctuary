package com.k33bz.sanctuary.grave;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OfflineUuidTest {

    /** Known vector: the offline UUID every offline-mode server derives for "Notch". */
    @Test
    void matchesVanillaDerivation() {
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                OfflineUuid.of("Notch"));
    }

    @Test
    void caseSensitiveLikeVanilla() {
        assertNotEquals(OfflineUuid.of("k33bz"), OfflineUuid.of("K33bz"));
    }
}
