#!/usr/bin/env bash
# "On a server" of live-service/README.md, step by step, on Ubuntu with Apache from the
# distribution. Run as root from the directory a deploy unpacked (/opt/studio-live here).
set -euo pipefail
cd /opt/studio-live

# --- the service, as the README says --------------------------------------------------------
useradd --system --home /var/cache/studio-live --shell /usr/sbin/nologin studio-live
install -d -o studio-live -g studio-live -m 711 /var/cache/studio-live           # others may pass through, not look
install -d -o studio-live -g www-data -m 2750 /var/cache/studio-live/docroot    # Apache reads, the service writes
install -D -m 755 target/release/studio-live /usr/local/bin/studio-live
install -D -m 644 conf/live.toml /etc/studio-live/live.toml
# the purge commands for a CDN in front ([cdn] and [after_invalidation] in live.toml)
install -d -m 755 /usr/local/lib/studio-live
install -m 755 deploy/cdn/purge-*.sh /usr/local/lib/studio-live/
install -m 640 -g studio-live /dev/null /etc/studio-live/env
echo "STUDIO_LIVE_FLUSH_SECRET=$(openssl rand -hex 24)" > /etc/studio-live/env
install -m 644 deploy/studio-live.service /etc/systemd/system/studio-live.service

# --- what a server's live.toml says that the developer's does not ---------------------------
# the cache directory, the publish instances (here: the developer's machine, from the container),
# and a listen address the publish instances can reach: they are on other machines now, so the
# flush comes over the network and the secret is what lets it in.
sed -i \
  -e 's|^docroot = .*|docroot = "/var/cache/studio-live/docroot"|' \
  -e 's|^publish = .*|publish = ["http://host.docker.internal:4503", "http://host.docker.internal:4504"]|' \
  -e 's|^listen = .*|listen = "0.0.0.0:4520"|' \
  /etc/studio-live/live.toml
# the site's real host names only: localhost and 127.0.0.1 are a developer's machine's
sed -i -e '/^hosts = /s/, "localhost", "127.0.0.1"//' /etc/studio-live/live.toml

# --- Apache: the modules, the cache's directory rules, the virtual hosts ---------------------
a2enmod -q rewrite proxy proxy_http headers deflate ssl
a2dissite -q 000-default
cp -r apache/conf.d /etc/apache2/conf.d
sed -i -e 's/ localhost 127.0.0.1$//' /etc/apache2/conf.d/available_vhosts/*.vhost

# --- HTTPS: certificates, as a certificate authority would give them --------------------------
# Here a certificate authority of the rehearsal's own (its ca.crt is what a visitor trusts);
# on a server the site's real certificates (certbot, the provider's) go in the same place.
install -d -m 750 /etc/studio-live/tls
cd /etc/studio-live/tls
openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=Sensible Studio rehearsal CA" \
  -keyout ca.key -out ca.crt 2>/dev/null
cert() {  # cert NAME NAMES... (host names, or IP addresses)
  local name=$1; shift
  local sans="" n
  for n in "$@"; do
    if [[ $n =~ ^[0-9.]+$ ]]; then sans+="IP:$n,"; else sans+="DNS:$n,"; fi
  done
  sans=${sans%,}
  openssl req -newkey rsa:2048 -nodes -subj "/CN=$1" -keyout "$name.key" -out "$name.csr" 2>/dev/null
  openssl x509 -req -in "$name.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -days 30 \
    -extfile <(printf 'subjectAltName=%s\nextendedKeyUsage=serverAuth' "$sans") -out "$name.crt" 2>/dev/null
  rm -f "$name.csr"
}
cert wknd www.wknd.example wknd.example
cert default default.invalid
cert publish-gateway publish.internal 127.0.0.1
rm -f ca.key ca.srl
chmod 600 ./*.key
cd /opt/studio-live

cat > /etc/apache2/conf-available/studio-live.conf <<'EOF'
# The Live cache: its directory rules, then the virtual hosts (the default one first, so that a
# host name no site has gets 404). Every site over HTTPS, HTTP sending visitors there.
Define STUDIO_LIVE_TLS
Include conf.d/studio_live.conf
Include conf.d/available_vhosts/000_default.vhost
Include conf.d/available_vhosts/wknd.vhost
EOF
a2enconf -q studio-live

# A publish instance behind HTTPS, as behind a load balancer of the organisation: publish 2 of the
# developer's machine on 4443, with a certificate of the rehearsal's authority, which Java does not
# trust. An author publishes there only once that authority is in its Trust Store.
cat > /etc/apache2/conf-available/publish-gateway.conf <<'EOF'
Listen 4443
<VirtualHost *:4443>
    ServerName publish.internal
    SSLEngine on
    SSLCertificateFile    /etc/studio-live/tls/publish-gateway.crt
    SSLCertificateKeyFile /etc/studio-live/tls/publish-gateway.key
    ProxyPass        / http://host.docker.internal:4504/
    ProxyPassReverse / http://host.docker.internal:4504/
</VirtualHost>
EOF
a2enconf -q publish-gateway
# Apache reads the redirect maps the service writes (RewriteMap): it starts after the service,
# which writes them (empty until read) when it starts.
install -d /etc/systemd/system/apache2.service.d
cat > /etc/systemd/system/apache2.service.d/studio-live.conf <<'EOF'
[Unit]
After=studio-live.service
Wants=studio-live.service
EOF

systemctl enable studio-live.service apache2.service
