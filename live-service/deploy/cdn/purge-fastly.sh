#!/bin/sh
# Purges what a publication changed from Fastly, by surrogate key: the tags studio-live gives in
# STUDIO_LIVE_TAGS after every invalidation. With [cdn] kind = "fastly" in live.toml:
#
#   [after_invalidation]
#   command = ["/usr/local/lib/studio-live/purge-fastly.sh"]
#
# and in the service's environment (/etc/studio-live/env, root:studio-live 640):
#   FASTLY_API_TOKEN=...      a token with the purge scope
#   FASTLY_SERVICE_ID=...
#   FASTLY_SOFT_PURGE=1       (default) mark as stale rather than remove: with stale-while-revalidate
#                             visitors keep getting the old page for a moment while it is fetched
set -eu
[ -n "${STUDIO_LIVE_TAGS:-}" ] || exit 0
: "${FASTLY_API_TOKEN:?FASTLY_API_TOKEN is not set}" "${FASTLY_SERVICE_ID:?FASTLY_SERVICE_ID is not set}"
api="${FASTLY_API:-https://api.fastly.com}"
# a few hundred keys a request (Fastly's batch purge)
printf '%s\n' $STUDIO_LIVE_TAGS | xargs -n 200 | while read -r keys; do
    if [ "${FASTLY_SOFT_PURGE:-1}" = 1 ]; then
        curl -fsS --max-time 20 -X POST "$api/service/$FASTLY_SERVICE_ID/purge" \
            -H "Fastly-Key: $FASTLY_API_TOKEN" -H "Surrogate-Key: $keys" -H "Fastly-Soft-Purge: 1" >/dev/null
    else
        curl -fsS --max-time 20 -X POST "$api/service/$FASTLY_SERVICE_ID/purge" \
            -H "Fastly-Key: $FASTLY_API_TOKEN" -H "Surrogate-Key: $keys" >/dev/null
    fi
done
