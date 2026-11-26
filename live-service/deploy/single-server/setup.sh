#!/usr/bin/env bash
# Sensible Studio on one small server: a publish instance, the Live cache and Apache httpd, for one
# site. This is how https://sensiblestudio.io runs, on a machine with 2 GB of memory (plus 2 GB of
# swap); the author stays elsewhere and delivers through an SSH tunnel:
#   ssh -N -L 14503:127.0.0.1:4503 <server>     target address http://127.0.0.1:14503/bin/receive
#
# Before: Ubuntu 24.04 with openjdk-21-jre-headless, apache2 and python3; a release unpacked in
# /opt/sensible-studio; the system users "studio" (home /var/lib/studio) and "studio-live".
# Set the values below, then run as root. Safe to run again.
# After: start studio-live, studio-publish and apache2, run secure.sh, then get a certificate:
#   certbot certonly --webroot -w /var/www/acme -d <domain> [-d <alias>]...
#   ln -s /etc/letsencrypt/live/<domain>/fullchain.pem /etc/studio-live/tls/<site>.crt
#   ln -s /etc/letsencrypt/live/<domain>/privkey.pem   /etc/studio-live/tls/<site>.key
#   (and a self-signed default.crt / default.key there, for the host names no site has)
#   echo "Define STUDIO_LIVE_TLS" > /etc/apache2/conf.d/tls.define && systemctl reload apache2
set -euo pipefail

REL=/opt/sensible-studio
DOMAIN=sensiblestudio.io
ALIASES="www.sensiblestudio.io docs.sensiblestudio.io"
SITE=docs
ROOT=/content/studio-docs
HOME_PAGE=/en.html

[ -x "$REL/lib/bin/studio-live" ] || { echo "no $REL/lib/bin/studio-live"; exit 1; }

# ---------------------------------------------------------------- the Live cache
install -d -o studio-live -g studio-live -m 711 /var/cache/studio-live
install -d -o studio-live -g www-data -m 2750 /var/cache/studio-live/docroot
install -D -m 755 "$REL/lib/bin/studio-live" /usr/local/bin/studio-live
install -d -m 755 /etc/studio-live
if [ ! -s /etc/studio-live/env ]; then
    install -m 640 -o root -g studio-live /dev/null /etc/studio-live/env
    echo "STUDIO_LIVE_FLUSH_SECRET=$(openssl rand -hex 32)" > /etc/studio-live/env
fi
python3 - "$REL/live-service/conf/live.toml" /etc/studio-live/live.toml "$SITE" "$DOMAIN" "$ALIASES" "$ROOT" <<'EOF'
import re, sys
src, dest, site, domain, aliases, root = sys.argv[1:7]
text = open(src).read()
text = re.sub(r'(?m)^listen *=.*$', 'listen = "127.0.0.1:4520"', text)
text = re.sub(r'(?m)^docroot *=.*$', 'docroot = "/var/cache/studio-live/docroot"', text)
text = re.sub(r'(?m)^publish *=.*$', 'publish = ["http://127.0.0.1:4503"]', text)
cut = re.search(r'(?m)^\[\[site\]\]', text)
if cut:
    text = text[:cut.start()]
hosts = ", ".join('"%s"' % h for h in [domain] + aliases.split())
text += '''[[site]]
name = "%s"
hosts = [%s]
roots = ["%s"]
shared = ["/conf/studio-docs"]
''' % (site, hosts, root)
open(dest, "w").write(text)
EOF
chmod 644 /etc/studio-live/live.toml
install -m 644 "$REL/live-service/deploy/studio-live.service" /etc/systemd/system/studio-live.service

# ---------------------------------------------------------------- the publish instance
install -d -o studio -g studio -m 750 /var/lib/studio
if [ ! -d /var/lib/studio/publish/install ]; then
    sudo -u studio env HOME=/var/lib/studio AUTHOR_HOME=/var/lib/studio/none PUBLISH_HOME=/var/lib/studio/publish \
        STUDIO_LOGIN_KEY=/var/lib/studio/login.key "$REL/bin/studio-publish" setup
fi
# the site's public address (canonical links, sitemaps) and its short paths
cat > /var/lib/studio/publish/install/io.sensible.studio.wcm.impl.ExternalizerImpl.cfg.json <<EOF
{
  "domains": ["local https://$DOMAIN", "author http://localhost:4502", "publish https://$DOMAIN"]
}
EOF
cat > /var/lib/studio/publish/install/org.apache.sling.jcr.resource.internal.JcrResourceResolverFactoryImpl.cfg.json <<EOF
{
  "resource.resolver.mapping": ["$ROOT/:/", "/:/"]
}
EOF
chown studio:studio /var/lib/studio/publish/install/*.cfg.json
cat > /etc/systemd/system/studio-publish.service <<'EOF'
[Unit]
Description=Sensible Studio publish instance
After=network-online.target
Wants=network-online.target

[Service]
User=studio
Group=studio
WorkingDirectory=/var/lib/studio/publish
Environment=HOME=/var/lib/studio
ExecStart=/usr/bin/java -Xms256m -Xmx900m -XX:+ExitOnOutOfMemoryError -jar /opt/sensible-studio/lib/studio-quickstart.jar -p 4503 -r publish -c /var/lib/studio/publish
SuccessExitStatus=143
Restart=on-failure
RestartSec=5
LimitNOFILE=65536
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full

[Install]
WantedBy=multi-user.target
EOF

# ---------------------------------------------------------------- Apache httpd
A=/etc/apache2
install -d "$A/conf.d/available_vhosts" "$A/conf.d/sites" "$A/conf.d/rewrites" /var/www/acme /etc/studio-live/tls
install -m 644 "$REL/live-service/apache/conf.d/studio_live.conf" "$A/conf.d/studio_live.conf"
install -m 644 "$REL/live-service/apache/conf.d/rewrites/redirect_map.rules" "$A/conf.d/rewrites/redirect_map.rules"

cat > "$A/conf.d/acme.conf" <<'EOF'
# Let's Encrypt's HTTP challenge, answered from a folder of its own under every host name
Alias /.well-known/acme-challenge/ /var/www/acme/.well-known/acme-challenge/
<Directory /var/www/acme>
    Require all granted
    Options -Indexes
</Directory>
EOF

cat > "$A/conf.d/available_vhosts/000_default.vhost" <<'EOF'
# Host names no site has: 404 (first, so it is the default)
<VirtualHost *:${STUDIO_LIVE_PORT}>
    ServerName default.invalid
    RewriteEngine On
    RewriteRule ^/\.well-known/acme-challenge/ - [L]
    RewriteRule ^ - [R=404,L]
</VirtualHost>
<IfDefine STUDIO_LIVE_TLS>
<VirtualHost *:${STUDIO_LIVE_TLS_PORT}>
    ServerName default.invalid
    SSLEngine on
    SSLCertificateFile    "${STUDIO_LIVE_TLS_DIR}/default.crt"
    SSLCertificateKeyFile "${STUDIO_LIVE_TLS_DIR}/default.key"
    RewriteEngine On
    RewriteRule ^ - [R=404,L]
</VirtualHost>
</IfDefine>
EOF

cat > "$A/conf.d/available_vhosts/$SITE.vhost" <<EOF
# $DOMAIN: the documentation site
<VirtualHost *:\${STUDIO_LIVE_PORT}>
    ServerName  $DOMAIN
    <IfDefine !STUDIO_LIVE_TLS>
        Include conf.d/sites/$SITE.site
    </IfDefine>
    <IfDefine STUDIO_LIVE_TLS>
        RewriteEngine On
        RewriteRule ^/\\.well-known/acme-challenge/ - [L]
        RewriteRule ^ https://$DOMAIN%{REQUEST_URI} [R=301,NE,L]
    </IfDefine>
</VirtualHost>
# its other names lead to it
<VirtualHost *:\${STUDIO_LIVE_PORT}>
    ServerName  ${ALIASES%% *}
    ServerAlias ${ALIASES#* }
    RewriteEngine On
    RewriteRule ^/\\.well-known/acme-challenge/ - [L]
    <IfDefine !STUDIO_LIVE_TLS>
        RewriteRule ^ http://$DOMAIN%{REQUEST_URI} [R=301,NE,L]
    </IfDefine>
    <IfDefine STUDIO_LIVE_TLS>
        RewriteRule ^ https://$DOMAIN%{REQUEST_URI} [R=301,NE,L]
    </IfDefine>
</VirtualHost>
<IfDefine STUDIO_LIVE_TLS>
<VirtualHost *:\${STUDIO_LIVE_TLS_PORT}>
    ServerName  $DOMAIN
    SSLEngine on
    SSLCertificateFile    "\${STUDIO_LIVE_TLS_DIR}/$SITE.crt"
    SSLCertificateKeyFile "\${STUDIO_LIVE_TLS_DIR}/$SITE.key"
    <IfModule mod_headers.c>
        Header always set Strict-Transport-Security "max-age=31536000"
        RequestHeader set X-Forwarded-Proto https
    </IfModule>
    Include conf.d/sites/$SITE.site
</VirtualHost>
<VirtualHost *:\${STUDIO_LIVE_TLS_PORT}>
    ServerName  ${ALIASES%% *}
    ServerAlias ${ALIASES#* }
    SSLEngine on
    SSLCertificateFile    "\${STUDIO_LIVE_TLS_DIR}/$SITE.crt"
    SSLCertificateKeyFile "\${STUDIO_LIVE_TLS_DIR}/$SITE.key"
    RewriteEngine On
    RewriteRule ^ https://$DOMAIN%{REQUEST_URI} [R=301,NE,L]
</VirtualHost>
</IfDefine>
EOF

cat > "$A/conf.d/sites/$SITE.site" <<EOF
# What $DOMAIN does in its virtual host
DocumentRoot "\${STUDIO_LIVE_DOCROOT}/$SITE"
<IfModule mod_headers.c>
    Header always append X-Frame-Options SAMEORIGIN
    Header always set X-Content-Type-Options nosniff
</IfModule>
RewriteEngine On
RewriteRule ^/\\.well-known/acme-challenge/ - [L]
RewriteRule ^/(dispatcher|_live)(/|\$) - [F,L]
# the home page; pages are asked for without $ROOT (the last block gives visitors such links)
RewriteRule ^/?\$ $HOME_PAGE [R=302,L]
RewriteRule ^$ROOT/(.*)\$ /\$1 [R=301,L]
RewriteCond %{REQUEST_URI} !^/(apps|bin|content|etc|etc\\.clientlibs|home|libs|system|tmp|var|dispatcher|_live|\\.well-known)(/|\\.|\$)
RewriteRule ^/(.*\\.html)\$ $ROOT/\$1 [PT,L]
ErrorDocument 502 "The site is not available at the moment."
ErrorDocument 503 "The site is not available at the moment."
<IfModule mod_headers.c>
    <LocationMatch "^/etc\\.clientlibs/.*\\.(?i:js|css|ttf|woff2?|svg)\$">
        Header set Cache-Control "max-age=86400,public" "expr=%{REQUEST_STATUS} < 400"
    </LocationMatch>
    <LocationMatch "\\.html\$">
        Header set Cache-Control "max-age=300,stale-while-revalidate=3600" "expr=%{REQUEST_STATUS} < 400"
    </LocationMatch>
</IfModule>
# Links in the pages are full repository paths: visitors get the short ones
<IfModule mod_substitute.c>
    <LocationMatch "\\.html\$">
        AddOutputFilterByType SUBSTITUTE text/html
        Substitute "s|\\"$ROOT/|\\"/|n"
    </LocationMatch>
</IfModule>
EOF

cat > "$A/sites-available/studio-live.conf" <<'EOF'
# Sensible Studio: the Live cache's side of Apache (see live-service/README.md in the release)
IncludeOptional conf.d/tls.define
Include conf.d/studio_live.conf
Include conf.d/acme.conf
Include conf.d/available_vhosts/000_default.vhost
IncludeOptional conf.d/available_vhosts/[a-z]*.vhost
EOF

a2enmod -q rewrite proxy proxy_http headers deflate ssl substitute >/dev/null
a2dissite -q 000-default >/dev/null 2>&1 || true
a2ensite -q studio-live >/dev/null

mkdir -p /etc/systemd/system/apache2.service.d
cat > /etc/systemd/system/apache2.service.d/studio-live.conf <<'EOF'
[Unit]
After=studio-live.service
Wants=studio-live.service
EOF

systemctl daemon-reload
systemctl enable -q studio-publish studio-live
echo "configured"
