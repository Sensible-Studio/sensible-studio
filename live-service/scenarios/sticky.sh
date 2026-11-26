#!/usr/bin/env bash
# Sticky sessions with two real publish instances, one of which goes away under a signed-in visitor.
# Stops publish 2 (instances/publish2, :4504) for about a minute and starts it again.
set -uo pipefail
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
cd "$(dirname "$0")/../.."
. live-service/scenarios/lib.sh  # the farm (farm.sh start), the visitors in instances/live-scenarios/creds
JAR=$(mktemp)
route() { python3 -c 'import sys
h=0xcbf29ce484222325
for b in sys.argv[1].encode(): h=((h^b)*0x100000001b3)&0xFFFFFFFFFFFFFFFF
print("%016x"%h)' "$1"; }
R1=$(route http://127.0.0.1:4503); R2=$(route http://127.0.0.1:4504)
echo "route ids: publish1=$R1 publish2=$R2"

echo "sign-in (bob, through brand2): $(signin brand2.localhost bob "$BOB_PW" "$JAR")"
LOGIN=$(awk '$6=="studio-login"{print $7}' "$JAR")
[ -n "$LOGIN" ] || { echo "no sign-in cookie"; exit 1; }
who() { curl -s -m 30 -D /tmp/scn-h.txt -H "Host: wknd.localhost" -H "Cookie: studio-login=$LOGIN${1:+; studio-route=$1}" "$FARM/libs/granite/security/currentuser.json" | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("authorizableId"))
except Exception: print("?")'; }
newroute() { grep -i '^set-cookie: studio-route=' /tmp/scn-h.txt | sed -E 's/.*studio-route=([^;]*).*/\1/' | tr -d '\r'; }

echo "== no route yet"
mark; U=$(who); NEW=$(newroute); echo "user=$U route given=$NEW $(asked currentuser.json)"
echo "== six more with that route"
mark; for i in 1 2 3 4 5 6; do U=$(who "$NEW"); [ -n "$(newroute)" ] && echo "  route changed: $(newroute)"; done; echo "user=$U $(asked currentuser.json)"
echo "== pinned to publish 2"
mark; for i in 1 2 3 4; do U=$(who "$R2"); done; echo "user=$U $(asked currentuser.json) route changed to: '$(newroute)'"

echo "== publish 2 stops"
PUBLISH_HOME=$PWD/instances/publish2 PUBLISH_PORT=4504 bash bin/studio-publish stop >/dev/null 2>&1
for i in $(seq 1 30); do curl -s -o /dev/null -m 2 http://127.0.0.1:4504/ || break; sleep 2; done
echo "publish 2 answers: $(curl -s -o /dev/null -m 2 -w %{http_code} http://127.0.0.1:4504/ || true)"
mark; U=$(who "$R2"); MOVED=$(newroute); echo "user=$U status=$(head -1 /tmp/scn-h.txt | awk '{print $2}') route moved to=$MOVED (publish1=$R1) $(asked currentuser.json)"
mark; for i in 1 2 3; do U=$(who "$MOVED"); done; echo "then: user=$U $(asked currentuser.json) route changed: '$(newroute)'"
echo "== a protected page while publish 2 is away (brand2 has only publish 2)"
echo "club: $(req brand2.localhost /en/club.html -H "Cookie: studio-login=$LOGIN")"

echo "== publish 2 starts again"
PUBLISH_HOME=$PWD/instances/publish2 PUBLISH_PORT=4504 bash bin/studio-publish start >/dev/null 2>&1 &
for i in $(seq 1 90); do [ "$(curl -s -o /dev/null -m 3 -w %{http_code} -u admin:admin http://127.0.0.1:4504/libs/granite/security/currentuser.json)" = 200 ] && break; sleep 3; done
echo "publish 2 back: $(curl -s -o /dev/null -m 3 -w %{http_code} -u admin:admin http://127.0.0.1:4504/libs/granite/security/currentuser.json)"
sleep 3
mark; for i in 1 2 3; do U=$(who "$MOVED"); done; echo "the visitor stays where he went: user=$U $(asked currentuser.json) route changed: '$(newroute)'"
rm -f "$JAR"
