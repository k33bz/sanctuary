package com.k33bz.sanctuary.grave;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.k33bz.sanctuary.Sanctuary;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Loads the Gravekeeper's static mutter pool + the framing string from the bundled
 * {@code assets/sanctuary/lang/en_us.json} once, and resolves a chosen {@link KeeperMutter.Line} to
 * its final display text. Sanctuary is a SERVER-side mod, so vanilla clients can't resolve these keys
 * themselves — the server reads them here and sends the resolved literal (the file stays in lang form
 * so it is translator-recognizable). Missing/short pools degrade to a small built-in fallback so a
 * bad resource never NPEs the tick loop; muttering just gets terser.
 */
public final class KeeperMutterLines {
    private KeeperMutterLines() {
    }

    private static final String RESOURCE = "/assets/sanctuary/lang/en_us.json";
    private static final String FRAME_KEY = "sanctuary.gravekeeper.mutter.frame";
    private static final String STATIC_PREFIX = "sanctuary.gravekeeper.mutter.";

    /** Fallback pool if the resource is missing (keeps muttering alive rather than crashing). */
    private static final String[] FALLBACK = {
            "they never do stay buried.", "rest now… rest.", "the earth is patient.",
    };
    private static final String FALLBACK_FRAME = "The Gravekeeper mutters, \"%s\"";

    private static String[] staticLines;
    private static String frame;

    /** Lazy one-time load; synchronized so the first two concurrent ticks don't double-parse. */
    private static synchronized void ensureLoaded() {
        if (staticLines != null) {
            return;
        }
        try (InputStream in = KeeperMutterLines.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + RESOURCE);
            }
            JsonObject obj = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            frame = obj.has(FRAME_KEY) ? obj.get(FRAME_KEY).getAsString() : FALLBACK_FRAME;
            // Contiguous indexed keys sanctuary.gravekeeper.mutter.0..N define the static pool.
            int n = 0;
            while (obj.has(STATIC_PREFIX + n)) {
                n++;
            }
            if (n == 0) {
                staticLines = FALLBACK.clone();
            } else {
                staticLines = new String[n];
                for (int i = 0; i < n; i++) {
                    staticLines[i] = obj.get(STATIC_PREFIX + i).getAsString();
                }
            }
        } catch (Exception e) {
            Sanctuary.LOGGER.warn("[sanctuary] could not load mutter lines; using fallback", e);
            staticLines = FALLBACK.clone();
            frame = FALLBACK_FRAME;
        }
    }

    /** Number of static pool lines available (the {@code poolSize} for {@link KeeperMutter}). */
    public static int poolSize() {
        ensureLoaded();
        return staticLines.length;
    }

    /**
     * Resolve a chosen line to its final display text (WITHOUT the framing). STATIC lines look up their
     * key in the pool; CONTEXTUAL lines already carry their rendered text. Returns null only for a
     * degenerate empty pool (caller skips the mutter).
     */
    public static String resolve(KeeperMutter.Line line) {
        ensureLoaded();
        if (line.kind == KeeperMutter.Kind.CONTEXTUAL) {
            return line.text;
        }
        int i = line.index;
        if (i < 0 || i >= staticLines.length) {
            return null;
        }
        return staticLines[i];
    }

    /** Wrap a resolved line in the framing string ({@code The Gravekeeper mutters, "…"}). */
    public static String frame(String resolved) {
        ensureLoaded();
        try {
            return String.format(Locale.ROOT, frame, resolved);
        } catch (Exception e) {
            return String.format(Locale.ROOT, FALLBACK_FRAME, resolved);
        }
    }
}
