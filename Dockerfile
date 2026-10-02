# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --version
COPY src src
RUN ./gradlew --no-daemon bootJar -x test \
    && cp "$(ls build/libs/*.jar | grep -v -- -plain.jar)" app.jar

FROM eclipse-temurin:25-jre
RUN useradd --system --no-create-home app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
