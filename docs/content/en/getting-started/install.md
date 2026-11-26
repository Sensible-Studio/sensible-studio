---
title: Install and run
description: Install Sensible Studio from a release or build it from source, then start the author instance, a publish instance and the Live cache on your own machine.
order: 10
---

There are two ways to get a running instance. A **release** is everything built already: unpack it and start. Building **from source** is for people who want to change the product, or run the latest code.

Either way you end with the same folder layout and the same commands, so everything after "Start the author instance" applies to both.

## From a release

You need **Java 21** or newer, and nothing else.

1. Download the archive for your system from the project's [releases](https://github.com/Sensible-Studio/sensible-studio/releases): `sensible-studio-<version>-linux-x64.tar.gz` or `sensible-studio-<version>-macos-arm64.tar.gz`.
2. Unpack it and start:

```
tar -xzf sensible-studio-<version>-linux-x64.tar.gz
cd sensible-studio-<version>-linux-x64
bin/studio start
```

The first start sets the instance up from the release's own files, and downloads Apache Sling itself from Maven Central (about 100 MB, once). For Weekender, the demo site the examples in this documentation use, run `bin/studio setup --demo` before the first start.

The archive holds the launcher, the install folder, the media service and the Live cache compiled for that system, the scripts, the Apache configuration and this documentation. `ffmpeg` and LibreOffice are still optional extras of the machine; see the table below.

Continue at [Start the author instance](#start-the-author-instance).

## From source

This takes you from a clone of the repository to an author instance you can sign in to. Allow twenty minutes, most of it waiting for the first build.

### What you need

| | Version | What for |
|---|---|---|
| **Java** | 21 or newer | The instances. `bin/studio` picks a Java 21 by itself on macOS; elsewhere set `JAVA_HOME`. |
| **Maven** | 3.9 | Building the modules and fetching libraries from Maven Central. |
| **Node.js** | 20 or newer | Building the authoring interface. |
| **zip, unzip, curl** | any | Packaging. |
| **Rust** (cargo) | stable | Optional. The media service (renditions, any size by address, auto tags) and the Live cache. Without it the instance starts and media is not processed. Install from [rustup.rs](https://rustup.rs). |
| **ffmpeg** | any | Optional. Video thumbnails, posters and a copy every browser plays. |
| **LibreOffice** | any | Optional. Previews of Word, Excel, PowerPoint and OpenDocument files. |

It runs on macOS and Linux. About 4 GB of free memory and 5 GB of disk are enough for an author, a publish instance and the sample site.

### Build and set up

```
git clone <the repository address> sensible-studio
cd sensible-studio
bin/studio setup --demo
```

`setup` does everything a new clone needs:

1. builds the Java modules (`mvn package`);
2. fetches the libraries and the Core Components from Maven Central;
3. builds the authoring interface (`npm run package` in `author-ui`);
4. packs the content packages and copies the configuration;
5. builds the media service, if Rust is installed.

The result is `instances/author/install`: the folder the instance installs from when it starts. Nothing in `instances/` is in git, so run `setup` again after you pull new code.

`--demo` adds [Weekender](https://github.com/Sensible-Studio/weekender), the demo site: a travel magazine with adventures, articles and a language structure, which the rest of this documentation uses in its examples. Leave it out for an empty instance. `--wknd` adds WKND, the sample site Adobe publishes for AEM, as a check that a project built for AEM runs unchanged.

## Start the author instance

```
bin/studio start
```

The instance runs in the foreground and listens on port 4502; `bin/studio start -p 8080` chooses another port. The first start creates the repository and installs everything from `install/`, which takes two to three minutes. It is ready when [http://localhost:4502/](http://localhost:4502/) shows the sign-in page.

Sign in as `admin` with the password `admin`. **Change that password** before the instance is reachable by anyone else: **Home › Users › Users**, open *admin*, **Change password**.

Stop it with `Ctrl+C`, or from another terminal with `bin/studio stop`.

> **Note:** During the first start the log shows one error, *Refusing to install package … core.wcm.components.content … required dependencies missing*. The installer met the packages in the wrong order; it installs that package a second later. Nothing needs doing.

## Start a publish instance

The publish instance is a second instance, with its own repository, that serves what authors publish.

```
bin/studio-publish start
```

It listens on port 4503 and keeps its files in `instances/publish`. The first `start` runs `setup` by itself: it seeds the publish instance's install folder from the author's, without the authoring interface, and adds the configuration that lets visitors read the site without signing in.

The author already has a publishing target that points at `http://localhost:4503`. Publish a page, then open it at `http://localhost:4503/content/<site>/<page>.html`.

| Command | What it does |
|---|---|
| `bin/studio-publish start` | Starts it (`setup` first if needed) |
| `bin/studio-publish stop` | Stops it |
| `bin/studio-publish status` | Is it running, and does it answer |
| `bin/studio-publish setup` | Brings its install folder up to date with the author's |

A second publish instance, or a preview, is the same script with another home and port: `PUBLISH_HOME=instances/preview PUBLISH_PORT=4505 bin/studio-publish start`.

## Start the Live cache

Optional on a developer's machine. The Live cache is what stands in front of the publish instances on a real site; see [The Live cache](../developer/live-cache.md).

```
bin/studio-live build      # from source only; a release has it built
bin/studio-live start
```

It answers on [http://localhost:4520/](http://localhost:4520/). `bin/studio-live status` says what it holds and which publish instances answer.

## Ports

| Port | What listens |
|---|---|
| 4502 | Author instance |
| 4503 | Publish instance |
| 4510 | Media service of the author (started and stopped by the instance) |
| 4511 | Media service of the publish instance (its port plus 8) |
| 4520 | Live cache |

## If something goes wrong

- **The page shows the Apache Sling welcome page.** `instances/author/install` is missing or empty. Run `bin/studio setup`.
- **The first start stops with a download error.** The launcher fetches Apache Sling from Maven Central the first time. The machine needs to reach `repo1.maven.org` once.
- **A sample page fails with "cannot be resolved to a type".** The instance runs on a Java older than 21. Check `java.version` under **Home › System › Web Console › System Status**.
- **Images have no thumbnails.** The media service is not running: Rust was not installed when you ran `setup`. Install it and run `bin/studio setup` again.
- **Anything else.** The log is `instances/author/logs/error.log`. **Home › System › Developer › Health** shows what the instance thinks of itself.

Next: [a tour of the interface](tour.md), then [your first page](first-page.md).
