# Disposable Fabric server for CI and local testing. Bare minimum to boot and
# accept an RCON client - no world persistence, no extra mods, no GUI.
#
#   docker build --build-arg MC_VERSION=26.1.2 -t mc-test:26.1.2 .
#
# The launcher jar AND the Minecraft server/libraries it pulls are fetched at
# BUILD time, so starting a container needs no network and boots in seconds.
# Mods and world live on volumes, so teardown is a docker rm.
FROM eclipse-temurin:25-jre-noble

ARG MC_VERSION=26.1.2
ARG FABRIC_LOADER=0.19.3
ARG FABRIC_INSTALLER=1.1.2

ENV MC_VERSION=${MC_VERSION} \
    SERVER_PORT=25565 \
    RCON_PORT=25575 \
    MEMORY=2G

WORKDIR /server

# curl for the fetch; procps so healthchecks and the harness can see the JVM.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates procps \
 && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL -o fabric-server.jar \
      "https://meta.fabricmc.net/v2/versions/loader/${MC_VERSION}/${FABRIC_LOADER}/${FABRIC_INSTALLER}/server/jar" \
 && test -s fabric-server.jar

# Accepting the EULA is required for the server to start at all. This image is
# disposable test infrastructure for a server the operator already runs.
RUN echo "eula=true" > eula.txt

# Pre-warm: boot once so the launcher downloads the Minecraft server jar and
# Fabric libraries into the image. Without this the first container start dies
# with "No game providers present on the class path". The server never exits on
# its own, so it is stopped as soon as the libraries appear rather than waiting
# out a timeout - that keeps the build to about a minute.
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
RUN printf 'server-port=25565\nonline-mode=false\nmax-players=1\nview-distance=2\n' > server.properties; \
    java -Xms1G -Xmx1G -jar fabric-server.jar nogui > /tmp/prewarm.log 2>&1 & \
    pid=$!; \
    for i in $(seq 1 60); do \
      if [ -d .fabric ] && [ -d libraries ] && [ -d versions ]; then break; fi; \
      kill -0 "$pid" 2>/dev/null || break; \
      sleep 5; \
    done; \
    kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; \
    rm -rf world logs server.properties; \
    if [ -d .fabric ] && [ -d libraries ]; then \
      echo "pre-warm ok: $(du -sh libraries | cut -f1) of libraries cached"; \
    else \
      echo "PRE-WARM FAILED"; tail -25 /tmp/prewarm.log; exit 1; \
    fi

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh && mkdir -p /server/mods /server/world

EXPOSE 25565 25575
HEALTHCHECK --interval=10s --timeout=5s --start-period=180s --retries=30 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${RCON_PORT}' || exit 1

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
