package com.k33bz.sanctuary.grave;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import com.k33bz.sanctuary.Sanctuary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * One-way bridge to the {@code postbox} mod (if present) with NO compile-time dependency on it:
 * drops a mail-request JSON into postbox's {@code config/postbox_outbox/} spool, which postbox's
 * {@code Outbox} sweep turns into a delivered letter. Writes atomically (tmp + ATOMIC_MOVE) so
 * postbox never reads a half-written file, and one file per request means no shared-file race with
 * postbox's own store. Best-effort: any failure (no postbox, IO error) just skips the mail.
 */
public final class PostboxBridge {
    private PostboxBridge() {
    }

    private static final Gson GSON = new Gson();

    /** True when the postbox mod is loaded and the spool can be written. */
    public static boolean available() {
        return FabricLoader.getInstance().isModLoaded("postbox");
    }

    /**
     * Queue a system letter to {@code toUuid} (best-effort). {@code from} is the sender label shown
     * on the postcard (e.g. "The Gravekeeper"); {@code body} is the message.
     */
    public static void sendMail(String toUuid, String toName, String from, String body) {
        if (toUuid == null || toUuid.isBlank() || !available()) {
            return;
        }
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve("postbox_outbox");
            Files.createDirectories(dir);

            JsonObject req = new JsonObject();
            req.addProperty("toUuid", toUuid);
            if (toName != null) {
                req.addProperty("toName", toName);
            }
            req.addProperty("from", from);
            req.addProperty("body", body);

            String base = toUuid + "-" + System.currentTimeMillis()
                    + "-" + Integer.toHexString(System.identityHashCode(body));
            Path tmp = dir.resolve(base + ".json.tmp");
            Path dst = dir.resolve(base + ".json");
            Files.writeString(tmp, GSON.toJson(req));
            Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Sanctuary.LOGGER.warn("[sanctuary] could not queue postbox mail", e);
        }
    }
}
