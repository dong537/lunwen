FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl libreoffice-writer \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system thesis && useradd --system --gid thesis --home /app thesis \
    && mkdir -p /data/thesis-inspection /app/.cache \
    && chown -R thesis:thesis /data/thesis-inspection /app
COPY --from=build /build/target/app.jar /app/app.jar
USER thesis
EXPOSE 18098
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD curl -fsS http://127.0.0.1:18098/api/inspections/health || exit 1
ENTRYPOINT ["java", "-Dthesis.soffice=/usr/bin/soffice", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
