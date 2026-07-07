package com.k33bz.sanctuary.grave;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Vanilla's offline-mode UUID derivation: {@code UUID.nameUUIDFromBytes("OfflinePlayer:" + name)}.
 * On an offline-mode server this IS the player's UUID, so the console consecrate path can assign
 * yard ownership by name and have it match the player the moment they join.
 */
public final class OfflineUuid {
    private OfflineUuid() {
    }

    public static UUID of(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
