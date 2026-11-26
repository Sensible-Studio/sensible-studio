---
title: On a server
description: Put Sensible Studio on a server: the author, the publish instances and the Live cache, with HTTPS, secrets, the first things to change, backups and logs.
order: 90
---

A production setup is at least three parts, ideally on machines of their own:

| Part | Reached by | Notes |
|---|---|---|
| **Author** | Your people only | Behind a VPN or an access proxy. Never on the public internet as it comes. |
| **Publish** instances, one or more | The Live cache and the author only | No public address. |
| **Live cache** (Apache httpd and `studio-live`) | Visitors | The only part with a public address. A CDN may stand in front. |

## Before anything else: secure it

A new instance is set up for a developer's machine. Do these before it is reachable by anyone else.

1. **Change the `admin` password**, on the author and on every publish instance. It starts as `admin`.
2. **Remove the sample users.** Apache Sling's starter creates `slingshot1` and `slingshot2`. Delete them in **Home › Users › Users**.
3. **Change the transport user's password.** The author delivers to a publish instance as the user `studio-transport`, whose first password is in the configuration that ships in the repository, so it is public. Change it on each publish instance, and in the matching target on the author.
4. **Set a flush secret** for the Live cache (below). Without one, only a connection from the same machine may invalidate.
5. **Give the publish instances a signing key of your own** (below).
6. **Keep the consoles closed.** `/system/console`, the Repository Browser and `/bin/*` must not be reachable from outside. The shipped filter of the Live cache closes them; do not open them.

## The instances

Do not build on the server. Put a **release** on it: the archive from the project's releases, or one you made yourself with `bin/studio dist <version>` on a machine with the same system as the server. Unpacked, it has everything the commands below need, and the server needs only Java 21.

```
tar -xzf sensible-studio-<version>-linux-x64.tar.gz -C /opt
ln -sfn /opt/sensible-studio-<version>-linux-x64 /opt/sensible-studio
```

The scripts are the quickest way to run from it. Each instance keeps its repository in a home folder of your choice, outside the release, so a new release replaces the code and leaves the content alone:

```
AUTHOR_HOME=/var/lib/studio/author   /opt/sensible-studio/bin/studio start
PUBLISH_HOME=/var/lib/studio/publish /opt/sensible-studio/bin/studio-publish start
```

Under systemd, start the launcher itself. A publish instance also gets its run mode:

```
java -Xmx4g -jar /opt/sensible-studio/lib/studio-quickstart.jar -c /var/lib/studio/author  -p 4502
java -Xmx4g -jar /opt/sensible-studio/lib/studio-quickstart.jar -c /var/lib/studio/publish -p 4503 -r publish
```

Run `bin/studio setup` and `bin/studio-publish setup` once with the same home folders first: they fill each home's `install` folder and put the media service in place. An instance's first start downloads Apache Sling from Maven Central, so the server needs to reach `repo1.maven.org` that once.

Run each as its own system user, from a systemd unit, with `Restart=on-failure`. Install **ffmpeg** and **LibreOffice** on the machines if you want video and document previews.

| | Author | Publish |
|---|---|---|
| Memory | 4 GB heap to start with | 2 to 4 GB |
| Disk | The repository, and every version of every file | The published content |
| Java | 21 | 21 |

**Deploying a new version**: unpack the new release, point `/opt/sensible-studio` at it, run the two `setup` commands again and restart the instances. Your own project's bundles and packages go in each instance's `install` folder; the installer picks them up while the instance runs. Every build needs a new version number.

## The Live cache

`live-service/deploy/studio-live.service` is the systemd unit. The service runs as its own user, may write nothing but the cache directories, and reads its secret from a file only root and it can read.

```
useradd --system --home /var/cache/studio-live --shell /usr/sbin/nologin studio-live
install -d -o studio-live -g studio-live -m 711  /var/cache/studio-live
install -d -o studio-live -g www-data    -m 2750 /var/cache/studio-live/docroot
install -D -m 755 lib/bin/studio-live /usr/local/bin/studio-live           # from the release
install -D -m 644 live-service/conf/live.toml /etc/studio-live/live.toml
install -m 640 -g studio-live /dev/null /etc/studio-live/env
install -m 644 live-service/deploy/studio-live.service /etc/systemd/system/
systemctl enable --now studio-live
```

In `/etc/studio-live/live.toml` set `docroot = "/var/cache/studio-live/docroot"`, `publish` to the publish instances' addresses, and `listen` to an address they can reach.

**The flush secret.** Put `STUDIO_LIVE_FLUSH_SECRET=<a long random value>` in `/etc/studio-live/env`, and the same value as the password of the **Live cache** target on every publish instance. Without the secret a flush from another machine gets 403.

**Start order.** Apache refuses to start without the redirect map files the service writes, so order it after the service:

```
# /etc/systemd/system/apache2.service.d/studio-live.conf
[Unit]
After=studio-live.service
Wants=studio-live.service
```

## HTTPS

For visitors, Apache terminates TLS. `Define STUDIO_LIVE_TLS` before `conf.d/studio_live.conf` is included, enable `ssl`, and put each site's certificate in `/etc/studio-live/tls` as `<site>.crt` and `<site>.key`, plus `default.crt` and `default.key` for host names no site has. HTTP then only redirects to HTTPS, the HTTPS virtual host sends `Strict-Transport-Security`, and cookies are `Secure`.

Take `localhost` and `127.0.0.1` out of the sites' `hosts` in `live.toml` and out of the vhosts' `ServerAlias`.

For the author, put a reverse proxy with TLS in front. An instance can also serve HTTPS itself (**Home › Users › SSL Configuration**), which is meant for trying things out.

## The sign-in key of the publish instances

Visitors who sign in to the live site get a cookie signed with a key. Every publish instance must have the same key, or a visitor signed in on one is a stranger on the next.

```
install -d -m 750 -o root -g studio /etc/studio
openssl rand -hex 32 | install -m 640 -o root -g studio /dev/stdin /etc/studio/login.key
```

Once per farm; copy the file to every publish instance from your secret store, and point each instance at it: in `io.sensible.studio.auth.impl.LoginHandler.cfg.json`, `"key.file": "/etc/studio/login.key"`.

A new key is read within seconds and signs everybody out once. The key alone lets nobody in.

## Backups

Everything an instance knows is in its home folder: the repository (`repository/`) and the media service's store (`dam/`).

- **The author is what you back up.** It holds all content and all versions.
- A publish instance can be rebuilt from the author by publishing again, though restoring a copy is quicker.
- The simplest consistent backup is a copy of the home folder taken while the instance is stopped, or a filesystem snapshot.
- The Live cache holds nothing that needs saving.

**Home › System › Developer › Health** runs the repository's nightly maintenance (compaction, cleaning up binaries nobody references, old versions) and shows whether it succeeded.

## Logs

| What | Where |
|---|---|
| An instance | `<home>/logs/error.log` and `access.log`, a file a day; the media service's `dam-service.log` |
| studio-live | The journal: `journalctl -u studio-live` |
| Apache | Where the distribution puts them, rotated by its `logrotate` |

## Rehearse it

`live-service/deploy/rehearsal/` runs the whole server side on Ubuntu 24.04 with systemd in a container: `run.sh` builds and boots it, `check.sh` checks it (HTTPS by the site's names, redirects, who may read what, the flush with and without the secret, log rotation, a restart, a crash, a reboot). `rehearsal/farm/` adds two publish instances on machines of their own. Read them before writing your own deployment: they are the commands above, tested.
