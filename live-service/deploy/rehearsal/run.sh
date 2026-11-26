#!/usr/bin/env bash
# The server rehearsal: build the image (a Linux build of studio-live, installed the way the
# README's "On a server" says) and boot it with systemd as PID 1.
#   Apache of the "server":   https://www.wknd.example:8443 (curl --resolve www.wknd.example:8443:127.0.0.1
#                             --cacert with the container's /etc/studio-live/tls/ca.crt); :8090 sends to HTTPS
#   studio-live, for flushes: http://localhost:4522   (what a publish agent points at)
# The publish instances are the ones on this machine (host.docker.internal:4503/4504).
set -euo pipefail
cd "$(dirname "$0")/../.."
docker build -f deploy/rehearsal/Dockerfile -t studio-live-server .
docker rm -f studio-live-server >/dev/null 2>&1 || true
docker run -d --name studio-live-server \
  --privileged --cgroupns=host -v /sys/fs/cgroup:/sys/fs/cgroup:rw \
  --tmpfs /run --tmpfs /run/lock \
  -p 8090:80 -p 8443:443 -p 4522:4520 -p 4443:4443 \
  --add-host host.docker.internal:host-gateway \
  studio-live-server
echo "booted; status: docker exec studio-live-server systemctl status studio-live apache2"
