#!/usr/bin/env bash
# A flush agent on publish 4503 pointing at the server container (the secret as its
# transport password), an activation from author, and what the server's cache did with it.
set -uo pipefail
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
cd "$(dirname "$0")"
C=studio-live-server
A=/etc/replication/agents.publish/rehearsal_server
SECRET=$(docker exec "$C" sh -c '. /etc/studio-live/env; printf %s "$STUDIO_LIVE_FLUSH_SECRET"')
FILE=/var/cache/studio-live/docroot/wknd/content/wknd/us/en.html

curl -s -o /dev/null -k --resolve www.wknd.example:8443:127.0.0.1 https://www.wknd.example:8443/us/en.html
echo "cached before: $(docker exec $C sh -c "test -f $FILE && stat -c %Y $FILE || echo none")"

curl -s -o /dev/null -w "agent page %{http_code}\n" -u admin:admin -F 'jcr:primaryType=cq:Page' "http://127.0.0.1:4503$A"
curl -s -o /dev/null -w "agent content %{http_code}\n" -u admin:admin \
  --form-string 'jcr:primaryType=cq:PageContent' \
  --form-string 'sling:resourceType=cq/replication/components/agent' \
  --form-string 'jcr:title=Server rehearsal' \
  --form-string 'enabled=true' --form-string 'enabled@TypeHint=Boolean' \
  --form-string 'transportUri=http://127.0.0.1:4522/dispatcher/invalidate.cache' \
  --form-string 'transportUser=publish' \
  --form-string "transportPassword=$SECRET" \
  --form-string 'serializationType=flush' \
  --form-string 'retryDelay=60000' --form-string 'retryDelay@TypeHint=Long' \
  "http://127.0.0.1:4503$A/jcr:content"

sleep 1
docker exec $C sh -c 'journalctl -u studio-live --no-pager -n 0 >/dev/null'
SINCE=$(docker exec $C date '+%H:%M:%S')
echo "test connection: $(curl -s -u admin:admin --form-string op=test --form-string agent=rehearsal_server http://127.0.0.1:4503/bin/studio/replication)"
curl -s -o /dev/null -w "activate on author %{http_code}\n" -u admin:admin \
  --form-string op=activate --form-string path=/content/wknd/us/en http://127.0.0.1:4502/bin/studio/replication
sleep 8
echo "--- the server's journal since the activation"
docker exec $C journalctl -u studio-live --no-pager --since "$SINCE" -o cat | grep -iv "redirect map\|vanity" | tail -8
echo "cached after: $(docker exec $C sh -c "test -f $FILE && stat -c %Y $FILE || echo none")"
echo "stale copy: $(docker exec $C sh -c 'find /var/cache/studio-live/docroot/wknd.stale -name en.html | head -1')"
echo "--- the agent's queue on publish"
curl -s -u admin:admin "http://127.0.0.1:4503/bin/studio/replication?op=queue&agent=rehearsal_server" | head -c 400; echo

curl -s -o /dev/null -w "agent removed %{http_code}\n" -u admin:admin -F ':operation=delete' "http://127.0.0.1:4503$A"
