#!/bin/sh
# Purges what a publication changed from Amazon CloudFront, by cache tag (an invalidation item
# "#<tag>"): the tags studio-live gives in STUDIO_LIVE_TAGS after every invalidation. With
# [cdn] kind = "cloudfront" in live.toml (and the distribution's CacheTagConfig naming the same
# header, Cache-Tag unless [cdn] header says otherwise):
#
#   [after_invalidation]
#   command = ["/usr/local/lib/studio-live/purge-cloudfront.sh"]
#
# with the AWS CLI installed, its credentials in the service's environment, and:
#   CLOUDFRONT_DISTRIBUTION_ID=...
set -eu
[ -n "${STUDIO_LIVE_TAGS:-}" ] || exit 0
: "${CLOUDFRONT_DISTRIBUTION_ID:?CLOUDFRONT_DISTRIBUTION_ID is not set}"
items=""
for tag in $STUDIO_LIVE_TAGS; do
    items="$items #$tag"
done
# each item counts as one invalidation path
# shellcheck disable=SC2086
aws cloudfront create-invalidation --distribution-id "$CLOUDFRONT_DISTRIBUTION_ID" --paths $items >/dev/null
