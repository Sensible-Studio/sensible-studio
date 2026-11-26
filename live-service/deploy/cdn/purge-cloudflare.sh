#!/bin/sh
# Purges what a publication changed from Cloudflare, by cache tag: the tags studio-live gives in
# STUDIO_LIVE_TAGS after every invalidation. With [cdn] kind = "cloudflare" in live.toml:
#
#   [after_invalidation]
#   command = ["/usr/local/lib/studio-live/purge-cloudflare.sh"]
#
# and in the service's environment (/etc/studio-live/env, root:studio-live 640):
#   CF_API_TOKEN=...   a token allowed to purge the zone's cache
#   CF_ZONE_ID=...
set -eu
[ -n "${STUDIO_LIVE_TAGS:-}" ] || exit 0
: "${CF_API_TOKEN:?CF_API_TOKEN is not set}" "${CF_ZONE_ID:?CF_ZONE_ID is not set}"
api="${CF_API:-https://api.cloudflare.com/client/v4}"
# at most 100 tags a request (Cloudflare's limit); the tags are letters, digits, - and _
printf '%s\n' $STUDIO_LIVE_TAGS | xargs -n 100 | while read -r tags; do
    list=$(printf '"%s",' $tags)
    curl -fsS --max-time 20 -X POST "$api/zones/$CF_ZONE_ID/purge_cache" \
        -H "Authorization: Bearer $CF_API_TOKEN" -H "Content-Type: application/json" \
        --data "{\"tags\":[${list%,}]}" >/dev/null
done
