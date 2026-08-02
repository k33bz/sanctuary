# Disposable Fabric test server

A throwaway Minecraft server for running the `mc-test-harness` suite against a
freshly built mod, without touching gmc101.

```bash
export RCON_PASSWORD=$(openssl rand -hex 16)
export MODS_DIR=/path/to/jars          # the mod under test AND its dependencies
docker compose up -d --build           # first build fetches the Fabric launcher
docker compose logs -f mc-test         # wait for "Done"
docker compose down -v                 # teardown, world included
```

**`fabric-api` must be in `MODS_DIR` too.** Sanctuary declares a hard dependency
on it, and Fabric aborts before the server starts:
`Mod 'Sanctuary' requires any version of fabric-api, which is missing!`
That is the loader working correctly, not a broken image.

Verified 2026-08-01 on 26.1.2: boots in ~12s, loads 44 mods,
`sanctuary v0.8.10.0+26.1.2 initialized (server-authoritative)`, RCON up.

## Deliberate choices

- **Ports 25599/25598 on the host**, never 25565 — colliding with the live
  server is the one mistake that would matter.
- **World on tmpfs**, so teardown leaves nothing and chunk generation is faster.
- **Mods mounted read-only**; the server has no reason to write to them.
- **`RCON_PASSWORD` has no default.** An empty RCON password does not fail
  loudly, it silently disables RCON, and the harness then hangs on connect.
- **The launcher is fetched at build time**, so starting a container needs no
  network and a rebuild is only needed to change Minecraft version.

Match `MC_VERSION` to the branch under test: `main` builds `+26.2`, the `26.1`
branch builds `+26.1.2`. gmc101 runs **26.1.2**.
