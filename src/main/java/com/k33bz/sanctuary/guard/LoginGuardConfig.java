package com.k33bz.sanctuary.guard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reserved-name / allowed-IP guard config, written to {@code config/sanctuary_login_guard.json}.
 *
 * <p>gmc101 runs {@code online-mode=false} for the native bot fleet, so a player's identity is
 * just its name — anyone joining as {@code k33bz} would derive k33bz's offline UUID and be handed
 * k33bz's inventory (and kick the real session). This guard refuses a <b>protected name</b> unless
 * the connection's source IP falls inside an <b>allowed CIDR</b>. Unprotected names are untouched,
 * so the open playtest is unaffected.
 *
 * <p>Hot-reloaded: the file's mtime is checked on each login, so ops can add an IP or a name
 * without a server restart. A cheap read — logins are rare and the file is tiny.
 */
public final class LoginGuardConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile LoginGuardConfig cached;
    private static volatile long cachedMtime = -1L;

    /** Resolved lazily so class-load never touches the Fabric loader (keeps the logic unit-testable). */
    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("sanctuary_login_guard.json");
    }

    // ---- serialized fields -------------------------------------------------

    /** Master switch. */
    public boolean enabled = true;

    /**
     * Names that may only join from an allowed CIDR (case-insensitive). Seeded with the owner and
     * the current bot roster; keep it in sync when bots are renamed (or have the botfarm supervisor
     * write this list — a later phase). The real fix is {@code online-mode=true} with paid accounts.
     */
    public List<String> protectedNames = defaultProtectedNames();

    /**
     * Source networks a protected name may connect from. Defaults: loopback + the LAN the bots and
     * servers live on ({@code 192.168.11.0/24}, verified as the real source IP the server logs — no
     * docker-gateway rewriting). Add your WireGuard subnet / WAN address for remote owner access.
     * <b>Never add a docker bridge range</b> — that would let a mangled-source WAN attempt through.
     */
    public List<String> allowedCidrs = defaultAllowedCidrs();

    /** Shown to a rejected connection. */
    public String kickMessage = "That name is reserved. Connect from an authorized network.";

    // ---- defaults ----------------------------------------------------------

    private static List<String> defaultProtectedNames() {
        List<String> l = new ArrayList<>();
        l.add("k33bz");
        // Current bot roster (2026-07-23). Renames must be mirrored here.
        for (String bot : new String[]{"Doc", "Grumpy", "Lunk", "wheat1", "Sugar", "Sawyer", "Forge", "Stew"}) {
            l.add(bot);
        }
        return l;
    }

    private static List<String> defaultAllowedCidrs() {
        List<String> l = new ArrayList<>();
        l.add("127.0.0.0/8");   // loopback (v4)
        l.add("::1/128");       // loopback (v6)
        l.add("192.168.11.0/24"); // the LAN: bots (.183), gmc101 (.120), owner LAN machines
        return l;
    }

    // ---- load / hot-reload -------------------------------------------------

    /** Current config, reloaded from disk when the file changed. Never returns null. */
    public static LoginGuardConfig get() {
        try {
            long mtime = Files.exists(configFile()) ? Files.getLastModifiedTime(configFile()).toMillis() : 0L;
            LoginGuardConfig c = cached;
            if (c != null && mtime == cachedMtime) {
                return c;
            }
            c = load();
            cached = c;
            cachedMtime = mtime;
            return c;
        } catch (Exception e) {
            // On any error, fall back to the last good config (or fail-open defaults) so a bad edit
            // never locks the server out — the guard is protection, not a foot-gun.
            LoginGuardConfig c = cached;
            return c != null ? c : new LoginGuardConfig();
        }
    }

    private static LoginGuardConfig load() {
        try {
            if (Files.exists(configFile())) {
                LoginGuardConfig c = GSON.fromJson(Files.readString(configFile()), LoginGuardConfig.class);
                if (c == null) {
                    c = new LoginGuardConfig();
                }
                if (c.protectedNames == null) c.protectedNames = defaultProtectedNames();
                if (c.allowedCidrs == null) c.allowedCidrs = defaultAllowedCidrs();
                if (c.kickMessage == null) c.kickMessage = new LoginGuardConfig().kickMessage;
                return c;
            }
        } catch (IOException | RuntimeException e) {
            // fall through to a fresh default + rewrite
        }
        LoginGuardConfig c = new LoginGuardConfig();
        c.save();
        return c;
    }

    private void save() {
        try {
            Files.createDirectories(configFile().getParent());
            Files.writeString(configFile(), GSON.toJson(this));
        } catch (IOException ignored) {
            // best-effort; a read-only config dir just means no seed file, defaults still apply
        }
    }

    // ---- decision ----------------------------------------------------------

    /** True if {@code name} is on the protected list (case-insensitive). */
    public boolean isProtected(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String p : protectedNames) {
            if (p != null && p.toLowerCase(Locale.ROOT).equals(n)) return true;
        }
        return false;
    }

    /** True if {@code addr} falls inside any allowed CIDR. */
    public boolean isAllowedAddress(InetAddress addr) {
        if (addr == null) return false;
        for (String cidr : allowedCidrs) {
            if (Cidr.contains(cidr, addr)) return true;
        }
        return false;
    }
}
