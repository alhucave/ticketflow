# syntax=docker/dockerfile:1
# Base images are pinned by multi-arch index digest (tag kept for readability). Dependabot (docker ecosystem)
# proposes updates for both. To refresh by hand: docker pull <image:tag> && docker image inspect <image:tag> --format '{{index .RepoDigests 0}}'
# Version reported by the app (service.version of the logs, jar name). Plain builds keep the development version;
# release.yml passes the tag version (--build-arg APP_VERSION=0.1.0). See DP-040.
ARG APP_VERSION=0.0.1-SNAPSHOT

FROM eclipse-temurin:25-jdk@sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc AS build
ARG APP_VERSION
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.lockfile ./
COPY gradle gradle
RUN ./gradlew --no-daemon --version
COPY src src
COPY docker/healthcheck docker/healthcheck
RUN ./gradlew --no-daemon -PappVersion="$APP_VERSION" bootJar -x test \
    && cp "$(ls build/libs/*.jar | grep -v -- -plain.jar)" app.jar \
    && mkdir healthcheck && javac -d healthcheck docker/healthcheck/Healthcheck.java

# Runtime: distroless JRE (no shell, no package manager, no curl), non-root user (uid 65532).
FROM gcr.io/distroless/java25-debian13:nonroot@sha256:ca60da1345c0f17b6d019049e6749e15f10fd3c0da86dec938d2b4ec565d0629
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
COPY --from=build /workspace/healthcheck /app/healthcheck
USER nonroot
# 8080: public API. 8081: actuator (health probes, Prometheus); never publish it beyond localhost or a private network.
EXPOSE 8080 8081
# JSON logs (one object per line, with the correlation id) by default in the container; an orchestrator can unset it.
# The management port must listen on the container interface (its default is loopback only).
ENV LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs \
    MANAGEMENT_SERVER_ADDRESS=0.0.0.0
# The probe is a separate JVM: keep it tiny so it fits next to the app under the container memory limit.
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
    CMD ["java", "-Xmx16m", "-Xss256k", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:-UsePerfData", "-cp", "/app/healthcheck", "Healthcheck"]
# Container-aware heap (percentage of the cgroup limit) and fail-fast on OOM so the orchestrator restarts the container.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-XX:-UsePerfData", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
