#!/usr/bin/env bash
# Checks of the server rehearsal (run.sh first). Prints one line per check, ok or FAIL; the flush
# secret is read from the container into a variable and never printed. Visitors come by the
# site's real name over HTTPS, trusting the rehearsal's certificate authority only.
set -uo pipefail
C=studio-live-server
box() { docker exec "$C" "$@"; }
fails=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1 ($2)"; else echo "FAIL  $1: got $2, want $3"; fails=$((fails+1)); fi; }
CA=$(mktemp); box cat /etc/studio-live/tls/ca.crt > "$CA"
SITE=https://www.wknd.example:8443
# web PATH [curl options]: the status of a visitor's request to the site, over HTTPS
web() { local p=$1; shift; curl -s -o /dev/null -w '%{http_code}' --cacert "$CA" --resolve www.wknd.example:8443:127.0.0.1 --resolve wknd.example:8443:127.0.0.1 "$@" "$SITE$p"; }
flush() { curl -s -o /dev/null -w '%{http_code}' -X POST -H 'CQ-Action: Activate' -H "CQ-Handle: $1" -H 'Content-Length: 0' "${@:2}" http://127.0.0.1:4522/dispatcher/invalidate.cache; }
SECRET=$(box sh -c '. /etc/studio-live/env; printf %s "$STUDIO_LIVE_FLUSH_SECRET"')
PAGE=/content/wknd/us/en.html

echo "== systemd"
check "studio-live active" "$(box systemctl is-active studio-live)" active
check "apache2 active" "$(box systemctl is-active apache2)" active
check "runs as studio-live" "$(box ps -o user= -C studio-live | head -1 | tr -d ' ')" studio-live

echo "== HTTPS and the site's names"
check "HTTP sends to HTTPS" "$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -H 'Host: www.wknd.example' http://127.0.0.1:8090/us/en.html?x=1)" "301 https://www.wknd.example/us/en.html?x=1"
check "certificate of www.wknd.example trusted" "$(web /us/en.html)" 200
check "certificate of wknd.example trusted" "$(curl -s -o /dev/null -w '%{http_code}' --cacert "$CA" --resolve wknd.example:8443:127.0.0.1 https://wknd.example:8443/us/en.html)" 200
check "HSTS" "$(curl -s -D - -o /dev/null --cacert "$CA" --resolve www.wknd.example:8443:127.0.0.1 $SITE/us/en.html | grep -ci '^strict-transport-security: max-age=31536000')" 1
check "a name no certificate is for" "$(curl -sk -o /dev/null -w '%{http_code}' --resolve nobody.example:8443:127.0.0.1 https://nobody.example:8443/us/en.html)" 404
check "localhost is no site's any more (HTTPS)" "$(curl -sk -o /dev/null -w '%{http_code}' https://localhost:8443/us/en.html)" 404
check "localhost is no site's any more (HTTP)" "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8090/us/en.html)" 404
check "the cache's own: localhost unknown" "$(curl -s -G -o /dev/null -w '%{http_code}' -H 'Host: localhost' http://127.0.0.1:4522/content/wknd/us/en.html)" 404
check "route cookie Secure behind HTTPS" "$(curl -s -D - -o /dev/null --cacert "$CA" --resolve www.wknd.example:8443:127.0.0.1 -H 'Cookie: studio-login=x' $SITE/libs/granite/security/currentuser.json | grep -i '^set-cookie: studio-route=' | grep -ci 'secure')" 1

echo "== pages through Apache"
check "page, first time" "$(web /us/en.html)" 200
check "page, from the cache" "$(web /us/en.html)" 200
check "page on disk" "$(box test -f /var/cache/studio-live/docroot/wknd/content/wknd/us/en.html && echo yes || echo no)" yes
check "filter: /system closed" "$(web /system/console)" 404
check "cache endpoints closed to visitors" "$(web /_live/status)" 403

echo "== files"
check "docroot owner:group mode" "$(box stat -c '%U:%G %a' /var/cache/studio-live/docroot)" "studio-live:www-data 2750"
check "site dir group (setgid)" "$(box stat -c '%G' /var/cache/studio-live/docroot/wknd)" www-data
check "cached page mode" "$(box stat -c '%a' /var/cache/studio-live/docroot/wknd/content/wknd/us/en.html)" 640
check "www-data reads the page" "$(box su -s /bin/sh www-data -c 'cat /var/cache/studio-live/docroot/wknd/content/wknd/us/en.html >/dev/null && echo yes || echo no' 2>/dev/null)" yes
for d in held stale deps private; do
  p=/var/cache/studio-live/docroot/wknd.$d
  if box test -d "$p"; then
    check "www-data cannot list wknd.$d" "$(box su -s /bin/sh www-data -c "ls $p >/dev/null 2>&1 && echo yes || echo no")" no
  fi
done
check "env file mode" "$(box stat -c '%U:%G %a' /etc/studio-live/env)" "root:studio-live 640"
check "www-data cannot read the secret" "$(box su -s /bin/sh www-data -c 'cat /etc/studio-live/env >/dev/null 2>&1 && echo yes || echo no')" no
check "certificate keys root's alone" "$(box stat -c '%U %a' /etc/studio-live/tls/wknd.key)" "root 600"
check "service cannot write /etc (ProtectSystem)" "$(box sh -c 'nsenter -t $(pgrep -x studio-live) -m sh -c "touch /etc/x 2>/dev/null && echo yes || echo no"')" no

echo "== redirects (RewriteMap from the DAM map)"
check "/old-faqs.html" "$(web /old-faqs.html)" 301
check "/surf" "$(web /surf)" 302
check "/about.html" "$(web /about.html)" 308

echo "== flush over the network"
check "no secret" "$(flush $PAGE)" 403
check "wrong secret" "$(flush $PAGE -u any:wrong)" 403
check "the secret" "$(flush $PAGE -u "any:$SECRET")" 200
check "page fetched again" "$(web /us/en.html)" 200

echo "== logs"
LOG=$(box sh -c 'ls -t /var/log/apache2/*access.log | head -1')
web /us/en.html?logged=before >/dev/null
check "the visit is logged" "$(box grep -c 'logged=before' "$LOG")" 1
box logrotate -f /etc/logrotate.d/apache2; sleep 2
web /us/en.html?logged=after >/dev/null; sleep 1
check "apache2 after rotating the logs" "$(box systemctl is-active apache2)" active
check "the old log kept" "$(box sh -c "grep -c 'logged=before' $LOG.1")" 1
check "new visits in the new log" "$(box grep -c 'logged=after' "$LOG")" 1
check "the service's log: the journal" "$(box sh -c 'journalctl -u studio-live --no-pager | grep -q "Live cache ready" && echo yes')" yes

echo "== restarts"
box systemctl restart studio-live; sleep 2
check "after systemctl restart" "$(web /us/en.html)" 200
box pkill -9 -x studio-live; sleep 4
check "after a crash (Restart=on-failure)" "$(box systemctl is-active studio-live)" active
docker restart "$C" >/dev/null; sleep 6
check "after a reboot: studio-live" "$(box systemctl is-active studio-live)" active
check "after a reboot: apache2" "$(box systemctl is-active apache2)" active
check "after a reboot: page" "$(web /us/en.html)" 200
check "after a reboot: redirect" "$(web /old-faqs.html)" 301

rm -f "$CA"
echo "== $fails failed"
exit $fails
