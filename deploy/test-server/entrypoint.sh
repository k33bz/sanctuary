#!/usr/bin/env bash
# Writes server.properties fresh on every start so a recycled volume cannot
# carry stale settings, then execs the server as PID 1 for clean signals.
set -euo pipefail

: "${RCON_PASSWORD:?set RCON_PASSWORD}"   # no default: an empty RCON password silently disables RCON

cat > /server/server.properties <<PROPS
server-port=${SERVER_PORT}
enable-rcon=true
rcon.port=${RCON_PORT}
rcon.password=${RCON_PASSWORD}
online-mode=false
level-name=world
level-seed=${LEVEL_SEED:-0}
gamemode=survival
difficulty=${DIFFICULTY:-normal}
spawn-protection=0
max-players=10
view-distance=6
simulation-distance=4
sync-chunk-writes=false
enable-command-block=true
motd=mc-test ${MC_VERSION}
PROPS

echo "[mc-test] Minecraft ${MC_VERSION}, mods: $(ls -1 /server/mods/*.jar 2>/dev/null | wc -l)"
exec java -Xms"${MEMORY}" -Xmx"${MEMORY}" \
  -XX:+UseG1GC \
  -jar /server/fabric-server.jar nogui
