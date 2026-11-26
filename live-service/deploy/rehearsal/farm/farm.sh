#!/usr/bin/env bash
# The farm rehearsal: two publish instances, each on a machine of its own (a container, Linux,
# Java 21), behind the server rehearsal's cache (deploy/rehearsal/run.sh first), on a network of
# their own; the author stays on this machine and delivers to them through their ports.
#
#   farm.sh clone      the two instances as copies of publish 2 (stopped for a minute meanwhile):
#                      content, users and code as they are there, the way a publish is added
#   farm.sh start      builds the image, starts them, points the cache at them
#   farm.sh configure  the author's agents for them, their flush targets at the cache
#   farm.sh stop       stops them, and the cache goes back to this machine's publish instances
#
# The farm's sign-in key is a secret given to each container as a file (/run/secrets/login.key,
# read-only), from instances/farm/login.key; it is not in their homes. admin:admin is the
# development environment's.
set -euo pipefail
cd "$(dirname "$0")/../../../.."
NET=studio-farm
HOMES=instances/farm-docker
JAR=studio-quickstart/target/studio-quickstart-1.0.0-SNAPSHOT.jar
KEY=instances/farm/login.key
CACHE=studio-live-server
port() { [ "$1" = a ] && echo 4513 || echo 4514; }

clone() {
    local pid; pid=$(cat instances/publish2/publish.pid)
    PUBLISH_HOME=$PWD/instances/publish2 PUBLISH_PORT=4504 bash bin/studio-publish stop
    for _ in $(seq 1 60); do kill -0 "$pid" 2>/dev/null || break; sleep 2; done
    mkdir -p "$HOMES"
    for n in a b; do
        rm -rf "$HOMES/publish-$n"
        cp -c -R instances/publish2 "$HOMES/publish-$n" 2>/dev/null || cp -R instances/publish2 "$HOMES/publish-$n"
        rm -f "$HOMES/publish-$n/publish.pid" "$HOMES/publish-$n/login.key"
        # the key comes from the secret, not from the home
        cat > "$HOMES/publish-$n/install/io.sensible.studio.auth.impl.LoginHandler.cfg.json" <<'EOF'
{
  "cookie.name": "studio-login",
  "timeout.minutes:Integer": 30,
  "key.file": "/run/secrets/login.key"
}
EOF
    done
    PUBLISH_HOME=$PWD/instances/publish2 PUBLISH_PORT=4504 bash bin/studio-publish start
}

start() {
    docker build -q -f live-service/deploy/rehearsal/farm/publish.Dockerfile -t studio-publish-node dam-service >/dev/null
    docker network create "$NET" >/dev/null 2>&1 || true
    docker network connect "$NET" "$CACHE" 2>/dev/null || true
    for n in a b; do
        docker rm -f "publish-$n" >/dev/null 2>&1 || true
        docker run -d --name "publish-$n" --network "$NET" --memory 3g -e JAVA_OPTS=-Xmx1536m \
            -p "$(port $n):4503" \
            -v "$PWD/$HOMES/publish-$n:/srv/publish" \
            -v "$PWD/$JAR:/opt/studio-quickstart.jar:ro" \
            -v "$PWD/$KEY:/run/secrets/login.key:ro" \
            studio-publish-node >/dev/null
    done
    for n in a b; do
        for _ in $(seq 1 120); do
            [ "$(curl -s -o /dev/null -m 3 -w '%{http_code}' "http://127.0.0.1:$(port $n)/content/wknd/us/en.html")" = 200 ] && break
            sleep 3
        done
        echo "publish-$n: $(curl -s -o /dev/null -m 3 -w '%{http_code}' "http://127.0.0.1:$(port $n)/content/wknd/us/en.html")"
    done
    # the cache fetches from them now, by their names on the farm's network
    docker exec "$CACHE" sed -i 's|^publish = .*|publish = ["http://publish-a:4503", "http://publish-b:4503"]|' /etc/studio-live/live.toml
    docker exec "$CACHE" systemctl restart studio-live
}

configure() {
    local secret; secret=$(docker exec "$CACHE" sh -c '. /etc/studio-live/env; printf %s "$STUDIO_LIVE_FLUSH_SECRET"')
    for n in a b; do
        local p; p=$(port $n)
        # the author delivers to it and fetches what visitors made there: copies of the agents of
        # publish 2 (the credentials stay on the author), pointing at this machine
        for kind in "" _reverse; do
            curl -s -o /dev/null -u admin:admin -F ":operation=delete" "http://127.0.0.1:4502/etc/replication/agents.author/farm_$n$kind" || true
            curl -s -o /dev/null -w "author agent farm_$n$kind %{http_code}\n" -u admin:admin -F ":operation=copy" \
                -F ":dest=/etc/replication/agents.author/farm_$n$kind" "http://127.0.0.1:4502/etc/replication/agents.author/publish2$kind"
        done
        curl -s -o /dev/null -u admin:admin --form-string "transportUri=http://127.0.0.1:$p/bin/receive" --form-string "jcr:title=Farm publish $n" \
            "http://127.0.0.1:4502/etc/replication/agents.author/farm_$n/jcr:content"
        curl -s -o /dev/null -u admin:admin --form-string "transportUri=http://127.0.0.1:$p/bin/outbox" --form-string "jcr:title=Farm publish $n (reverse)" \
            "http://127.0.0.1:4502/etc/replication/agents.author/farm_${n}_reverse/jcr:content"
        # it tells the cache, over the farm's network, with the secret; the scenario farm is not there
        curl -s -o /dev/null -w "publish-$n live_cache %{http_code}\n" -u admin:admin \
            --form-string "transportUri=http://$CACHE:4520/dispatcher/invalidate.cache" \
            --form-string "transportUser=publish" --form-string "transportPassword=$secret" \
            --form-string "enabled=true" --form-string "enabled@TypeHint=Boolean" \
            "http://127.0.0.1:$p/etc/replication/agents.publish/live_cache/jcr:content"
        curl -s -o /dev/null -u admin:admin --form-string "enabled=false" --form-string "enabled@TypeHint=Boolean" \
            "http://127.0.0.1:$p/etc/replication/agents.publish/live_cache_scenarios/jcr:content"
    done
}

stop() {
    docker rm -f publish-a publish-b >/dev/null 2>&1 || true
    for n in a b; do
        for kind in "" _reverse; do
            curl -s -o /dev/null -u admin:admin -F ":operation=delete" "http://127.0.0.1:4502/etc/replication/agents.author/farm_$n$kind" || true
        done
    done
    docker exec "$CACHE" sed -i 's|^publish = .*|publish = ["http://host.docker.internal:4503", "http://host.docker.internal:4504"]|' /etc/studio-live/live.toml
    docker exec "$CACHE" systemctl restart studio-live
}

case "${1:-}" in
    clone) clone ;;
    start) start ;;
    configure) configure ;;
    stop) stop ;;
    *) echo "Usage: farm.sh clone|start|configure|stop" ;;
esac
