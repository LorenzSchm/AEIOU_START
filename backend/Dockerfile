# syntax=docker/dockerfile:1
#
# Use the repo root as the build context for Dokploy or the Docker CLI.
#   docker build -f backend/Dockerfile -t aeiou-dispatch .
# Gradle reads the start-kit CSVs alongside backend/data and backend/cloudline.

# ---- Build stage ---------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Sources and the resource trees processResources copies into the jar.
COPY backend ./backend
COPY start-kit/*.csv ./start-kit/
WORKDIR /src/backend
RUN --mount=type=cache,target=/root/.gradle sh ./gradlew --no-daemon :app:bootJar

# ---- Runtime stage -------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tesseract-ocr tesseract-ocr-eng \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --uid 1001 --home /app --no-create-home dispatch \
 && install -d -o dispatch -g dispatch /app/state \
 && chown dispatch:dispatch /app

COPY --from=build /src/backend/app/build/libs/app-0.1.0.jar /app/wave.jar
COPY --from=build /src/backend/data /app/data

USER dispatch
VOLUME /app/state

# Supply the hosted DATABASE_URL and API keys as runtime environment variables.
# A live writer sets AUTO_SEND=true after its dispatch state has been restored.
ENV SERVER_ADDRESS=0.0.0.0 \
    AUTO_SEND=false \
    DISPATCH_DB=/app/state/dispatch.sqlite3 \
    DISPATCH_TARIFF_DIR=/app/data \
    DISPATCH_OPENAI_KEY_FILE=/app/state/openai.key \
    JAVA_OPTS=""

EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
  CMD curl -fsS -o /dev/null "http://127.0.0.1:${SERVER_PORT:-8000}/api/v1/status" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/wave.jar"]
