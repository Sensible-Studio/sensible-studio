# Helpers for the scenarios (source it in bash): what answered a request, and what the publish
# instances were asked since mark.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FARM=http://127.0.0.1:8086
RUN="$ROOT/instances/live-scenarios"
DOCROOT="$RUN/docroot"
P1LOG="$ROOT/instances/publish/logs/access.log"
P2LOG="$ROOT/instances/publish2/logs/access.log"
LIVELOG="$RUN/logs/live.log"
[ -f "$RUN/creds" ] && . "$RUN/creds"

# req HOST PATH [curl options]: "<status> <who>", who = apache (a file, the service never asked),
# or the service's verdict (stored, hit, pass, denied, stale, error)
req() {
    local host="$1" path="$2"; shift 2
    curl -s -o /dev/null -D /tmp/scn-h.txt -m 30 --path-as-is -H "Host: $host" "$@" "$FARM$path" >/dev/null
    local code who
    code=$(head -1 /tmp/scn-h.txt | awk '{print $2}')
    who=$(grep -i '^x-studio-live:' /tmp/scn-h.txt | awk '{print $2}' | tr -d '\r')
    echo "${code:-000} ${who:-apache}"
}
header() { grep -i "^$1:" /tmp/scn-h.txt | cut -d' ' -f2- | tr -d '\r'; }
mark() { M1=$(wc -l < "$P1LOG"); M2=$(wc -l < "$P2LOG"); ML=$(wc -l < "$LIVELOG"); }
# asked PATTERN: how many requests each publish got since mark whose line matches
asked() { sleep 0.5; echo "p1=$(tail -n +$((M1+1)) "$P1LOG" | grep -c -- "$1") p2=$(tail -n +$((M2+1)) "$P2LOG" | grep -c -- "$1")"; }
livelog() { tail -n +$((ML+1)) "$LIVELOG" | sed -E 's/^[0-9T:-]+\.([0-9]{3})[0-9]*Z +[A-Z]+ +studio_live[a-z:_]*: /\1 /'; }
# cached SITE PATH: is the file in that site's cache directory (plain, held beside it, or not)
cached() { [ -f "$DOCROOT/$1/$2" ] && echo yes || { [ -f "$DOCROOT/$1.held/$2" ] && echo held || echo no; }; }
flush() { curl -s -m 30 -X POST -H "CQ-Action: ${2:-Activate}" -H "CQ-Handle: $1" http://127.0.0.1:4521/dispatcher/invalidate.cache; echo; }
activate() { curl -s -m 30 -o /dev/null -w "$1 %{http_code}" -u admin:admin -d "op=${2:-activate}" --data-urlencode "path=$1" http://localhost:4502/bin/studio/replication.json; echo; }
# signin HOST USER PASSWORD JAR: through the farm
signin() { curl -s -m 30 -o /dev/null -D /tmp/scn-h.txt -c "$4" -H "Host: $1" -e "http://$1/libs/granite/core/content/login.html" -d "j_username=$2" --data-urlencode "j_password=$3" -d "j_validate=true" "$FARM/libs/granite/core/content/login.html/j_security_check"; head -1 /tmp/scn-h.txt | awk '{print $2}'; }
