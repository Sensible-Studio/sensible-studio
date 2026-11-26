#!/usr/bin/env bash
# First things on a new publish instance: its own passwords, no sample users, the cache's secret.
# Run as root on the server, once, while the instance still has its first passwords.
# Nothing secret is printed: the new values are in /root/sensible-studio-credentials (mode 600).
set -euo pipefail
B=http://127.0.0.1:4503
CRED=/root/sensible-studio-credentials
H='X-Studio-CSRF: 1'

if [ -s "$CRED" ]; then echo "already done ($CRED exists)"; exit 0; fi
umask 077
ADMIN_NEW=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-28)
TRANSPORT_NEW=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-28)
. /etc/studio-live/env

code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

# 1. the transport user (the author delivers as this one), while admin still has the first password
c=$(code -u admin:admin -H "$H" -F "newPwd=$TRANSPORT_NEW" -F "newPwdConfirm=$TRANSPORT_NEW" "$B/system/userManager/user/studio-transport.changePassword.html")
echo "transport password: $c"
[ "$c" = 200 ] || exit 1

# 2. Sling's sample users
for u in slingshot1 slingshot2; do
    echo "delete $u: $(code -u admin:admin -H "$H" -X POST "$B/system/userManager/user/$u.delete.html")"
done

# 3. the cache's flush target: enabled, with the cache's secret
c=$(code -u admin:admin -H "$H" -d op=agent -d id=live_cache -d enabled=true -d transportUser=flush --data-urlencode "transportPassword=$STUDIO_LIVE_FLUSH_SECRET" "$B/bin/studio/replication.json")
echo "live cache target: $c"

# 4. admin, last
c=$(code -u admin:admin -H "$H" -F "oldPwd=admin" -F "newPwd=$ADMIN_NEW" -F "newPwdConfirm=$ADMIN_NEW" "$B/system/userManager/user/admin.changePassword.html")
echo "admin password: $c"
[ "$c" = 200 ] || exit 1

cat > "$CRED" <<EOF
# Sensible Studio publish instance on this server ($(date -u +%F))
# Keep this file to yourself. Change a password in Home > Users, then update it here.
admin_user=admin
admin_password=$ADMIN_NEW
transport_user=studio-transport
transport_password=$TRANSPORT_NEW
# the Live cache's flush secret is in /etc/studio-live/env
EOF
chmod 600 "$CRED"

# check, without showing anything
echo "old admin password now: $(code -u admin:admin "$B/system/sling/info.sessionInfo.json") (401 expected)"
echo "new admin password: $(code -u "admin:$ADMIN_NEW" "$B/bin/studio/replication.json?op=agents") (200 expected)"
echo "transport sign-in: $(code -u "studio-transport:$TRANSPORT_NEW" "$B/system/sling/info.sessionInfo.json") (200 expected)"
echo "sample users left: $(curl -s -u "admin:$ADMIN_NEW" "$B/system/userManager/user.1.json" | grep -o 'slingshot[12]' | sort -u | wc -l) (0 expected)"
echo "test connection to the cache: $(curl -s -u "admin:$ADMIN_NEW" -H "$H" -d op=test -d id=live_cache "$B/bin/studio/replication.json" | cut -c1-120)"
