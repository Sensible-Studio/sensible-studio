# A publish instance on a machine of its own (Linux, Java 21), for the farm rehearsal: built with
# dam-service/ as the context (its image service is compiled for Linux here); the quickstart jar,
# the instance's home and the farm's sign-in key are given by farm.sh when the container starts.
FROM rust:1-bookworm AS dam
WORKDIR /src
COPY . .
RUN cargo build --release

FROM eclipse-temurin:21-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=dam /src/target/release/studio-dam /opt/studio-dam
# the home is a volume: the image service goes where the instance looks for it, then the instance
ENTRYPOINT ["sh", "-c", "install -D -m 755 /opt/studio-dam /srv/publish/dam/bin/studio-dam && exec java $JAVA_OPTS -jar /opt/studio-quickstart.jar -p 4503 -r publish -c /srv/publish"]
