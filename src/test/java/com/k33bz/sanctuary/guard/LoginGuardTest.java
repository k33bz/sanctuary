package com.k33bz.sanctuary.guard;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic checks for the login guard — no Minecraft, no network. Proves the CIDR matcher and the
 * protected-name decision, including the traps that would make IP protection a false sense of
 * security (v4-in-v6, off-by-one prefix boundaries).
 */
class LoginGuardTest {

    private static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    // ---- CIDR containment --------------------------------------------------

    @Test
    void lanBlockContainsItsHosts() throws Exception {
        assertTrue(Cidr.contains("192.168.11.0/24", ip("192.168.11.183")), "the bot host is in the LAN");
        assertTrue(Cidr.contains("192.168.11.0/24", ip("192.168.11.120")), "gmc101 is in the LAN");
        assertTrue(Cidr.contains("192.168.11.0/24", ip("192.168.11.1")));
        assertTrue(Cidr.contains("192.168.11.0/24", ip("192.168.11.255")));
    }

    @Test
    void cidrExcludesNeighbours() throws Exception {
        assertFalse(Cidr.contains("192.168.11.0/24", ip("192.168.12.183")), "adjacent /24 is out");
        assertFalse(Cidr.contains("192.168.11.0/24", ip("192.168.10.255")), "one below the block is out");
        assertFalse(Cidr.contains("192.168.11.0/24", ip("10.0.0.5")));
    }

    @Test
    void prefixBoundariesAreExact() throws Exception {
        assertTrue(Cidr.contains("192.168.0.0/23", ip("192.168.1.5")), "/23 spans .0 and .1");
        assertFalse(Cidr.contains("192.168.0.0/23", ip("192.168.2.5")), "/23 stops before .2");
        assertTrue(Cidr.contains("203.0.113.64/26", ip("203.0.113.100")));
        assertFalse(Cidr.contains("203.0.113.64/26", ip("203.0.113.130")), "past the /26 boundary");
    }

    @Test
    void loopbackAndHostMatch() throws Exception {
        assertTrue(Cidr.contains("127.0.0.0/8", ip("127.0.0.1")));
        assertTrue(Cidr.contains("::1/128", ip("::1")));
        assertTrue(Cidr.contains("192.168.11.183", ip("192.168.11.183")), "bare address = exact host");
        assertFalse(Cidr.contains("192.168.11.183", ip("192.168.11.184")));
    }

    @Test
    void v4IsNeverInsideV6AndViceVersa() throws Exception {
        assertFalse(Cidr.contains("::/0", ip("192.168.11.183")), "a v6 catch-all must not swallow v4");
        assertFalse(Cidr.contains("0.0.0.0/0", ip("::1")), "a v4 catch-all must not swallow v6");
    }

    @Test
    void garbageCidrFailsClosed() throws Exception {
        assertFalse(Cidr.contains("not-a-cidr", ip("192.168.11.183")));
        assertFalse(Cidr.contains("192.168.11.0/99", ip("192.168.11.183")), "prefix out of range");
        assertFalse(Cidr.contains("", ip("192.168.11.183")));
    }

    // ---- protected-name + address decision ---------------------------------

    @Test
    void protectedNameIsCaseInsensitive() {
        LoginGuardConfig c = new LoginGuardConfig();
        assertTrue(c.isProtected("k33bz"));
        assertTrue(c.isProtected("K33BZ"), "case must not be a bypass");
        assertTrue(c.isProtected("Doc"), "bot names are protected too");
        assertFalse(c.isProtected("randomPlaytester"), "the open playtest is untouched");
        assertFalse(c.isProtected(null));
    }

    @Test
    void reservedNameOnlyFromAllowedNetworks() throws Exception {
        LoginGuardConfig c = new LoginGuardConfig(); // default LAN + loopback allowlist
        // The real bot host and the owner's LAN clear the guard.
        assertTrue(c.isAllowedAddress(ip("192.168.11.183")));
        assertTrue(c.isAllowedAddress(ip("127.0.0.1")));
        // An outside address does NOT — this is the hijack that would otherwise steal the inventory.
        assertFalse(c.isAllowedAddress(ip("203.0.113.7")), "a WAN attacker on a reserved name is refused");
        assertFalse(c.isAllowedAddress(ip("192.168.12.50")), "a different VLAN is not implicitly trusted");
    }

    @Test
    void dockerBridgeIsNotImplicitlyTrusted() throws Exception {
        // Guard against the subtle hole: if a WAN connection were ever SNAT'd to a docker gateway,
        // the default allowlist must NOT contain that range.
        LoginGuardConfig c = new LoginGuardConfig();
        assertFalse(c.isAllowedAddress(ip("172.20.0.1")), "docker bridge must never be a default-trusted source");
        assertFalse(c.isAllowedAddress(ip("172.17.0.1")));
    }
}
