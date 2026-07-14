package com.k33bz.sanctuary.grave;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guards the SNBT escapers that are the sole defense against an attacker-controlled name breaking
 * out of the {@code /summon} SNBT the grave displays build. On an offline-mode server a username can
 * carry any character, so these must stay robust regardless of upstream validation (see the escaper
 * defense-in-depth hardening). A regression here silently reopens the injection surface.
 */
class GraveEscapeTest {

    @Test
    void escapeEscapesQuoteAndBackslash() {
        assertEquals("a\\\"b", Graves.escape("a\"b"));
        assertEquals("a\\\\b", Graves.escape("a\\b"));
    }

    @Test
    void escapeStripsControlCharsThatWouldBreakTheCommandLine() {
        assertEquals("ab", Graves.escape("a\nb"));
        assertEquals("ab", Graves.escape("a\rb"));
        assertEquals("ab", Graves.escape("a\tb"));
        assertEquals("ab", Graves.escape("ab"));
    }

    @Test
    void escapeHandlesNullAndPlainText() {
        assertEquals("", Graves.escape(null));
        assertEquals("Steve", Graves.escape("Steve"));
    }

    @Test
    void displayNameAlsoStripsSectionFormattingCodes() {
        assertEquals("acb", Graves.displayName("a§cb"));
        assertEquals("lhi", Graves.displayName("§lhi"));
    }

    @Test
    void displayNameStillEscapesAndStripsLikeEscape() {
        assertEquals("a\\\"b", Graves.displayName("a\"b"));
        assertEquals("ab", Graves.displayName("a\nb"));
    }

    @Test
    void escapedOutputHasNoBareQuoteOrControlChar() {
        String hostile = "evil\"}],Passengers:[{id:\"minecraft:tnt\"}]\n§c";
        String e = Graves.escape(hostile);
        for (int i = 0; i < e.length(); i++) {
            char c = e.charAt(i);
            assertFalse(c < 0x20, "a control char survived escape()");
            if (c == '"') {
                assertFalse(i == 0 || e.charAt(i - 1) != '\\', "a bare quote survived escape()");
            }
        }
    }
}
