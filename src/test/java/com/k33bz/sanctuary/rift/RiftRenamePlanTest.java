package com.k33bz.sanctuary.rift;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The gathering-world rename moves a world folder, so the property worth asserting is not "a decision
 * was made" but "the decision never destroys a world". Every case below is a way the rename could have
 * silently cost terrain.
 */
class RiftRenamePlanTest {

    private static final String NEW = "sanctuary:rssworld";
    private static final String OLD = "sanctuary:resource_world";

    private static Predicate<String> onDisk(String... ids) {
        Set<String> present = Set.of(ids);
        return present::contains;
    }

    @Test
    @DisplayName("moves the legacy folder when the new id has none yet")
    void movesLegacyFolder() {
        assertEquals(OLD, RiftRenamePlan.chooseSource(NEW, List.of(OLD), false, onDisk(OLD)));
    }

    @Test
    @DisplayName("never clobbers a world that already exists under the new id")
    void refusesWhenTargetExists() {
        // The dangerous case: both folders present. Moving would overwrite a live world.
        assertNull(RiftRenamePlan.chooseSource(NEW, List.of(OLD), true, onDisk(OLD)));
    }

    @Test
    @DisplayName("does nothing when no legacy folder is on disk")
    void noSourceNoMove() {
        assertNull(RiftRenamePlan.chooseSource(NEW, List.of(OLD), false, onDisk()));
    }

    @Test
    @DisplayName("never moves a folder onto itself")
    void skipsSelfRename() {
        // A stale config that lists the current id as legacy must not move rssworld onto rssworld.
        assertNull(RiftRenamePlan.chooseSource(NEW, List.of(NEW), false, onDisk(NEW)));
    }

    @Test
    @DisplayName("picks the first listed legacy id that exists, deterministically")
    void firstExistingWins() {
        List<String> legacy = List.of("sanctuary:oldest", OLD);
        assertEquals(OLD, RiftRenamePlan.chooseSource(NEW, legacy, false, onDisk(OLD)));
        assertEquals("sanctuary:oldest", RiftRenamePlan.chooseSource(
                NEW, legacy, false, onDisk("sanctuary:oldest", OLD)));
    }

    @Test
    @DisplayName("skips malformed ids instead of throwing on them")
    void skipsMalformedIds() {
        List<String> legacy = new ArrayList<>(Arrays.asList(null, "", "nocolon", "a:b:c", ":lead", "trail:", OLD));
        assertEquals(OLD, RiftRenamePlan.chooseSource(NEW, legacy, false, onDisk(OLD)));
        // A malformed CURRENT id has no resolvable folder, so nothing may move.
        assertNull(RiftRenamePlan.chooseSource("nocolon", List.of(OLD), false, onDisk(OLD)));
    }

    @Test
    @DisplayName("an empty or absent legacy list means no migration")
    void emptyLegacyList() {
        assertNull(RiftRenamePlan.chooseSource(NEW, List.of(), false, onDisk(OLD)));
        assertNull(RiftRenamePlan.chooseSource(NEW, null, false, onDisk(OLD)));
    }

    @Test
    @DisplayName("split yields the namespace and path that name the folder")
    void splitsIds() {
        assertArrayEquals(new String[]{"sanctuary", "rssworld"}, RiftRenamePlan.split(NEW));
        assertArrayEquals(new String[]{"sanctuary", "resource_world"}, RiftRenamePlan.split(OLD));
        assertNull(RiftRenamePlan.split(null));
        assertNull(RiftRenamePlan.split(""));
        assertNull(RiftRenamePlan.split("nocolon"));
        assertNull(RiftRenamePlan.split(":leading"));
        assertNull(RiftRenamePlan.split("trailing:"));
        assertNull(RiftRenamePlan.split("too:many:colons"));
    }
}
