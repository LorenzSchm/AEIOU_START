FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
COPY docs/forwarder.openapi.json docs/
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/forwarder-1.1.0.jar app.jar
COPY start-kit start-kit
ENV BIND_ADDRESS=0.0.0.0
ENTRYPOINT ["java","-jar","app.jar"]
