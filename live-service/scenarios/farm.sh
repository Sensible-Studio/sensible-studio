#!/usr/bin/env bash
# The test farm of the scenarios: start | stop | status.
# A Live cache on 4521 (scenarios/live.toml) and Apache on 8086 (scenarios/httpd.conf), with the
# cache and the logs in instances/live-scenarios. Each publish instance needs a flush agent that
# points at 4521 (live_cache_scenarios), or publications do not reach this cache.
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN="$DIR/instances/live-scenarios"
HTTPD="${HTTPD:-/usr/sbin/httpd}"
export LIVE_HOME="$RUN" LIVE_CONFIG="$DIR/live-service/scenarios/live.toml"
apache() {
    mkdir -p "$RUN/apache" "$RUN/docroot"
    "$HTTPD" -f "$DIR/live-service/scenarios/httpd.conf" -d "$DIR/live-service/apache" \
        -C "Define STUDIO_LIVE_DOCROOT $RUN/docroot" -C "Define TEST_RUN $RUN/apache" "$@"
}
case "$1" in
    start) "$DIR/bin/studio-live" start; apache -t && apache -k start && echo "Apache of the test farm: http://127.0.0.1:8086" ;;
    stop) apache -k stop 2>/dev/null || true; "$DIR/bin/studio-live" stop ;;
    status) "$DIR/bin/studio-live" status; [ -f "$RUN/apache/httpd.pid" ] && kill -0 "$(cat "$RUN/apache/httpd.pid")" 2>/dev/null && echo "Apache running" || echo "Apache not running" ;;
    *) echo "Usage: live-service/scenarios/farm.sh start|stop|status" ;;
esac
