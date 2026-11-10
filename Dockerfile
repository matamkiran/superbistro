# Builds the whole app (React + Spring Boot) inside Docker. Nothing needs to be installed
# on the host except Docker itself.

FROM eclipse-temurin:17-jdk-noble AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q -Dskip.frontend=true dependency:go-offline
COPY frontend/package.json frontend/vite.config.js frontend/index.html frontend/
COPY frontend/src frontend/src
COPY src src
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:17-jre-noble
# pg_dump for the nightly backups (PostgreSQL's own repository: version 18 tools back up 13-18 servers).
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl \
 && install -d /usr/share/postgresql-common/pgdg \
 && curl -fsSL -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc https://www.postgresql.org/media/keys/ACCC4CF8.asc \
 && echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt noble-pgdg main" > /etc/apt/sources.list.d/pgdg.list \
 && apt-get update && apt-get install -y --no-install-recommends postgresql-client-18 \
 && rm -rf /var/lib/apt/lists/*
RUN useradd --system --uid 10001 bistro && mkdir -p /data && chown bistro /data
WORKDIR /app
COPY --from=build /src/target/superbistro.jar app.jar
USER bistro
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" DATA_DIR=/data PG_DUMP=/usr/lib/postgresql/18/bin/pg_dump
VOLUME /data
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
