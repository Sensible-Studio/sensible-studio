# A Linux server for the Live cache, to rehearse "On a server" of ../../README.md before a real
# one: systemd, Apache from the distribution, the service under its own user, the steps of the
# README run as written (install.sh). Built from live-service/:
#   docker build -f deploy/rehearsal/Dockerfile -t studio-live-server .
# and run with deploy/rehearsal/run.sh.

FROM rust:1-bookworm AS build
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY src src
RUN cargo build --release --locked

FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends systemd systemd-sysv apache2 ca-certificates curl openssl logrotate \
 && rm -rf /var/lib/apt/lists/* \
 # a container's own: nothing that waits for hardware or a login
 && systemctl mask systemd-logind.service getty.target console-getty.service systemd-udevd.service systemd-remount-fs.service

# what a deploy would bring: the binary, the unit, the configuration, Apache's files
COPY --from=build /src/target/release/studio-live /opt/studio-live/target/release/studio-live
COPY deploy/studio-live.service /opt/studio-live/deploy/studio-live.service
COPY conf/live.toml /opt/studio-live/conf/live.toml
COPY apache/conf.d /opt/studio-live/apache/conf.d
COPY deploy/cdn /opt/studio-live/deploy/cdn
COPY deploy/rehearsal/install.sh /opt/studio-live/install.sh
RUN bash /opt/studio-live/install.sh \
 # Docker's Ubuntu images forbid services to be reloaded (policy-rc.d, for image builds); a server
 # does not, and logrotate reloads Apache through it after rotating its logs
 && rm -f /usr/sbin/policy-rc.d

STOPSIGNAL SIGRTMIN+3
CMD ["/sbin/init"]
