#!/usr/bin/env bash
# Checks of the farm rehearsal (run.sh, then farm.sh clone|start|configure): two publish machines
# behind the server's cache. One line per check, ok or FAIL. Passwords and cookies are never printed.
# Makes a user on publish-a and removes it again (it travels to every publish instance meanwhile),
# and stops publish-a for a while.
set -uo pipefail
cd "$(dirname "$0")/../../../.."
. instances/live-scenarios/creds      # BOB_PW: bob, a visitor who exists on the publish instances
C=studio-live-server
fails=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1 ($2)"; else echo "FAIL  $1: got $2, want $3"; fails=$((fails+1)); fi; }
CA=$(mktemp); docker exec $C cat /etc/studio-live/tls/ca.crt > "$CA"
SITE=https://www.wknd.example:8443
site() { curl -s --cacert "$CA" --resolve www.wknd.example:8443:127.0.0.1 "$@"; }
mark() { A=$(docker exec publish-a sh -c 'wc -l < /srv/publish/logs/access.log'); B=$(docker exec publish-b sh -c 'wc -l < /srv/publish/logs/access.log'); J=$(date -u '+%Y-%m-%d %H:%M:%S'); }
asked() { echo "a=$(docker exec publish-a sh -c "tail -n +$((A+1)) /srv/publish/logs/access.log | grep -c -- '$1'") b=$(docker exec publish-b sh -c "tail -n +$((B+1)) /srv/publish/logs/access.log | grep -c -- '$1'")"; }
journal() { docker exec $C journalctl -u studio-live --no-pager -o cat --since "$J"; }
route() { python3 -c 'import sys
h=0xcbf29ce484222325
for b in sys.argv[1].encode(): h=((h^b)*0x100000001b3)&0xFFFFFFFFFFFFFFFF
print("%016x"%h)' "$1"; }

echo "== pages from two machines"
docker exec $C sh -c 'rm -rf /var/cache/studio-live/docroot/wknd/content'
mark
for p in us/en ca/en us/en/faqs us/en/about-us us/en/magazine us/en/adventures; do site -o /dev/null "$SITE/content/wknd/$p.html"; done
check "six pages not in the cache, from both machines" "$(asked '.html' | tr ' ' '\n' | grep -c '=[1-9]')" 2
check "then from the web server's files" "$(site -o /dev/null -w '%{http_code}' -D - $SITE/content/wknd/us/en/faqs.html | grep -ci '^x-studio-live')" 0

echo "== a publication reaches both machines, and both tell the cache"
mark
curl -s -o /dev/null -u admin:admin --data-urlencode op=activate --data-urlencode path=/content/wknd/us/en/faqs http://127.0.0.1:4502/bin/studio/replication.json
sleep 8
check "flushes from the farm" "$(journal | grep -c 'invalidated action=Activate handle=/content/wknd/us/en/faqs')" 2
check "fetched again" "$(journal | grep -c 'fetched again after its publication path=/content/wknd/us/en/faqs.html' | awk '{print ($1>0)?"yes":"no"}')" yes

echo "== one sign-in for both machines, the key from the secret"
JAR=$(mktemp)
check "sign in on publish-a" "$(curl -s -o /dev/null -w '%{http_code}' -c "$JAR" -d j_username=bob --data-urlencode "j_password=$BOB_PW" -d j_validate=true http://127.0.0.1:4513/libs/granite/core/content/login.html/j_security_check)" 200
LOGIN=$(awk '$6=="studio-login"{print $7}' "$JAR"); rm -f "$JAR"
me() { curl -s -H "Cookie: studio-login=$LOGIN${2:+; $2}" "$1" | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("authorizableId"))
except Exception: print("?")'; }
check "the cookie on publish-b" "$(me http://127.0.0.1:4514/libs/granite/security/currentuser.json)" bob
check "through the cache, over HTTPS" "$(curl -s --cacert "$CA" --resolve www.wknd.example:8443:127.0.0.1 -H "Cookie: studio-login=$LOGIN" $SITE/libs/granite/security/currentuser.json | python3 -c 'import json,sys; print(json.load(sys.stdin).get("authorizableId"))')" bob
check "no key in the instances' homes" "$(docker exec publish-a sh -c 'ls /srv/publish/login.key 2>/dev/null | wc -l')$(docker exec publish-b sh -c 'ls /srv/publish/login.key 2>/dev/null | wc -l')" 00

echo "== a user made on one machine is on the other"
U=farm-$(date +%s)
PW="Farm-$RANDOM-$RANDOM"
curl -s -o /dev/null -u admin:admin --form-string ":name=$U" --form-string "pwd=$PW" --form-string "pwdConfirm=$PW" http://127.0.0.1:4513/system/userManager/user.create.html
have() { curl -s -o /dev/null -w '%{http_code}' -u admin:admin "http://127.0.0.1:$1/system/userManager/user/$U.json"; }
check "made on publish-a" "$(have 4513)" 200
for _ in $(seq 1 30); do [ "$(have 4514)" = 200 ] && break; sleep 3; done
check "on publish-b, through the author" "$(have 4514)" 200
curl -s -o /dev/null -u admin:admin -F ":operation=delete" "http://127.0.0.1:4513/system/userManager/user/$U.delete.html"
for _ in $(seq 1 30); do [ "$(have 4514)" = 404 ] && break; sleep 3; done
check "removed on publish-a, gone from publish-b" "$(have 4514)" 404
for _ in $(seq 1 20); do [ "$(have 4503)$(have 4504)" = 404404 ] && break; sleep 3; done
check "and from this machine's publish instances" "$(have 4503)$(have 4504)" 404404

echo "== a machine goes away under a signed-in visitor"
RA=$(route http://publish-a:4503); RB=$(route http://publish-b:4503)
mark
site -o /dev/null -H "Cookie: studio-login=$LOGIN; studio-route=$RA" $SITE/libs/granite/security/currentuser.json
site -o /dev/null -H "Cookie: studio-login=$LOGIN; studio-route=$RA" $SITE/libs/granite/security/currentuser.json
check "pinned to publish-a" "$(asked currentuser.json)" "a=2 b=0"
docker stop publish-a >/dev/null
H=$(mktemp)
WHO=$(site -D "$H" -H "Cookie: studio-login=$LOGIN; studio-route=$RA" $SITE/libs/granite/security/currentuser.json | python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("authorizableId"))
except Exception: print("?")')
check "publish-a down: still bob" "$WHO" bob
check "the route moves to publish-b" "$(grep -i '^set-cookie: studio-route=' "$H" | sed -E 's/.*studio-route=([^;]*).*/\1/' | tr -d '\r')" "$RB"
check "the page still there" "$(site -o /dev/null -w '%{http_code}' $SITE/content/wknd/us/en.html)" 200
docker start publish-a >/dev/null
for _ in $(seq 1 60); do [ "$(curl -s -o /dev/null -m 3 -w '%{http_code}' http://127.0.0.1:4513/content/wknd/us/en.html)" = 200 ] && break; sleep 3; done
check "publish-a back" "$(curl -s -o /dev/null -m 3 -w '%{http_code}' http://127.0.0.1:4513/content/wknd/us/en.html)" 200
rm -f "$H" "$CA"
echo "== $fails failed"
exit $fails
