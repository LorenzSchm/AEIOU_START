# syntax=docker/dockerfile:1
#
# Use the repo root as the build context for Dokploy or the Docker CLI.
#   docker build -f backend/Dockerfile -t aeiou-dispatch .
# Gradle reads the start-kit CSVs alongside backend/data and backend/cloudline.

# ---- Build stage ---------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src

# Sources and the resource trees processResources copies into the jar.
COPY backend ./backend
COPY start-kit/*.csv ./start-kit/
WORKDIR /src/backend
RUN --mount=type=cache,target=/root/.gradle sh ./gradlew --no-daemon :app:bootJar

# ---- Java runtime stage --------------------------------------------------
# A trimmed Java runtime with only the modules the app uses. jdeps finds the static ones; the
# extra list covers modules that are only loaded reflectively or through service lookups:
# TLS elliptic-curve ciphers, legacy mail charsets (GBK, Big5, Shift_JIS), localised number and
# date formats, zip file systems, JMX/JNDI used by Spring and the JDBC drivers.
FROM eclipse-temurin:21-jdk-alpine AS jre
WORKDIR /tmp/app
COPY --from=build /src/backend/app/build/libs/app-0.1.0.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination extracted \
 && modules="$(jdeps --ignore-missing-deps -q --recursive --multi-release 21 \
      --print-module-deps --class-path 'extracted/lib/*' extracted/app.jar)" \
 && echo "jdeps modules: $modules" \
 && jlink \
      --add-modules "$modules,jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.zipfs,jdk.unsupported,java.naming,java.management,jdk.management,java.instrument,java.security.jgss,jdk.net" \
      --include-locales=en,de,fr,it,es,nl,pl,tr,zh,ja \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /jre

# ---- Runtime stage -------------------------------------------------------
FROM alpine:3.21 AS runtime
WORKDIR /app

# tesseract reads scanned attachments; fontconfig and DejaVu let Java draw text into images.
RUN apk add --no-cache tesseract-ocr tesseract-ocr-data-eng fontconfig ttf-dejavu \
 && adduser -D -H -u 1001 -h /app dispatch \
 && install -d -o dispatch -g dispatch /app/state \
 && chown dispatch:dispatch /app

ENV JAVA_HOME=/opt/java
ENV PATH="$JAVA_HOME/bin:$PATH"
COPY --from=jre /jre $JAVA_HOME
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
  CMD wget -q -O /dev/null "http://127.0.0.1:${SERVER_PORT:-8000}/api/v1/status" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/wave.jar"]
