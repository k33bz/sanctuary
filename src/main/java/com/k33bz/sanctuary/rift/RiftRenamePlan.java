package com.k33bz.sanctuary.rift;

import java.util.List;
import java.util.function.Predicate;

/**
 * Decides whether the gathering world's save folder should be moved onto a renamed dimension id, and
 * from where. Deliberately free of Minecraft types so the decision can be unit-tested: it is the one
 * piece of the rename that can destroy a world, and "a folder appeared" is not the property worth
 * asserting (see {@code docs/coe/2026-07-04-empty-graves.md}).
 *
 * <p>{@link RiftWorldgen#migrateLegacyWorldFolder} supplies the filesystem answers and performs the move;
 * everything about which move is legal lives here.
 */
public final class RiftRenamePlan {

    private RiftRenamePlan() {
    }

    /**
     * Namespace and path of a {@code namespace:path} id, or null when it is not well formed. Minecraft
     * lays a dimension out at {@code dimensions/<namespace>/<path>}, so both halves are needed to find
     * its folder.
     */
    public static String[] split(String id) {
        if (id == null) {
            return null;
        }
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            return null;
        }
        String namespace = id.substring(0, colon);
        String path = id.substring(colon + 1);
        if (path.indexOf(':') >= 0) {
            return null;
        }
        return new String[]{namespace, path};
    }

    /**
     * The legacy id whose folder should be moved onto {@code currentId}, or null when nothing should move.
     *
     * <p>Refusing to move is always the safe answer, so every uncertainty resolves that way: a target
     * folder that already exists is a world in its own right and is never clobbered, a malformed id is
     * skipped rather than thrown on, and a legacy id equal to the current one is not moved onto itself.
     * The first legacy entry with a folder wins, which makes the outcome deterministic when a server has
     * been renamed more than once.
     *
     * @param targetExists whether the destination folder is already on disk
     * @param sourceExists whether a given legacy id has a folder on disk
     */
    public static String chooseSource(String currentId, List<String> legacyIds,
                                      boolean targetExists, Predicate<String> sourceExists) {
        if (targetExists || legacyIds == null || legacyIds.isEmpty() || split(currentId) == null) {
            return null;
        }
        for (String legacy : legacyIds) {
            if (legacy == null || legacy.equals(currentId) || split(legacy) == null) {
                continue;
            }
            if (sourceExists.test(legacy)) {
                return legacy;
            }
        }
        return null;
    }
}
