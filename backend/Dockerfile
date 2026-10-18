FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl libreoffice-writer \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data/thesis-inspection
COPY target/app.jar /app/app.jar
EXPOSE 18098
ENTRYPOINT ["java", "-Dthesis.soffice=/usr/bin/soffice", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
