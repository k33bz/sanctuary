package com.k33bz.sanctuary.guard;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Dependency-free CIDR containment for IPv4 and IPv6. Compares the leading {@code prefix} bits of
 * the address against the network. A v4 address is never inside a v6 CIDR and vice-versa (same
 * byte-length gate), which keeps the loopback rules honest.
 */
public final class Cidr {

    private Cidr() {
    }

    /** True if {@code addr} is inside {@code cidr} (e.g. {@code "192.168.11.0/24"}). Bad CIDR → false. */
    public static boolean contains(String cidr, InetAddress addr) {
        if (cidr == null || addr == null) return false;
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            // A bare address means an exact host match.
            try {
                return java.util.Arrays.equals(InetAddress.getByName(cidr.trim()).getAddress(), addr.getAddress());
            } catch (UnknownHostException e) {
                return false;
            }
        }
        final InetAddress network;
        final int prefix;
        try {
            network = InetAddress.getByName(cidr.substring(0, slash).trim());
            prefix = Integer.parseInt(cidr.substring(slash + 1).trim());
        } catch (UnknownHostException | NumberFormatException e) {
            return false;
        }
        byte[] net = network.getAddress();
        byte[] ip = addr.getAddress();
        if (net.length != ip.length) return false;            // v4 vs v6 never match
        if (prefix < 0 || prefix > net.length * 8) return false;

        int fullBytes = prefix / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (net[i] != ip[i]) return false;
        }
        int remBits = prefix % 8;
        if (remBits != 0) {
            int mask = 0xFF << (8 - remBits) & 0xFF;
            if ((net[fullBytes] & mask) != (ip[fullBytes] & mask)) return false;
        }
        return true;
    }
}
