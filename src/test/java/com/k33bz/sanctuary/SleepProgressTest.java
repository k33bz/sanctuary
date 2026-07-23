package com.k33bz.sanctuary;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SleepProgressTest {

    @Test
    void required_ceilAndFloorOfOne() {
        assertEquals(1, SleepProgress.required(1, 50));   // ceil(0.5) -> 1
        assertEquals(1, SleepProgress.required(2, 50));   // ceil(1.0) -> 1
        assertEquals(2, SleepProgress.required(3, 50));   // ceil(1.5) -> 2
        assertEquals(3, SleepProgress.required(5, 50));   // ceil(2.5) -> 3
        assertEquals(5, SleepProgress.required(10, 50));  // ceil(5.0) -> 5
        assertEquals(1, SleepProgress.required(10, 0));   // floor at 1 even for 0%
        assertEquals(10, SleepProgress.required(10, 100));
    }

    @Test
    void required_neverBelowOne_evenWithNoPlayers() {
        assertEquals(1, SleepProgress.required(0, 50));
    }

    @Test
    void message_singleSleeper_needsMore() {
        assertEquals("k33bz is resting (1/2) — 1 more sleeper needed to pass the night.",
                SleepProgress.message(List.of("k33bz"), 1, 2));
    }

    @Test
    void message_multipleSleepers_needMore() {
        assertEquals("k33bz, Doc are resting (2/5) — 3 more sleepers needed to pass the night.",
                SleepProgress.message(List.of("k33bz", "Doc"), 2, 5));
    }

    @Test
    void message_enough_announcesNightPasses() {
        assertEquals("k33bz, Doc are resting (2/2) — the night passes.",
                SleepProgress.message(List.of("k33bz", "Doc"), 2, 2));
        assertEquals("k33bz is resting (1/1) — the night passes.",
                SleepProgress.message(List.of("k33bz"), 1, 1));
    }

    @Test
    void joinNames_variants() {
        assertEquals("", SleepProgress.joinNames(List.of()));
        assertEquals("Alice", SleepProgress.joinNames(List.of("Alice")));
        assertEquals("Alice, Bob, Carol", SleepProgress.joinNames(List.of("Alice", "Bob", "Carol")));
    }
}
