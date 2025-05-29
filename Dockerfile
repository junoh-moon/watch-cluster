FROM gradle:8.5-jdk17-alpine AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache ca-certificates
WORKDIR /app
COPY --from=builder /app/build/libs/watch-cluster.jar /app/watch-cluster.jar
USER 1000:1000
ENTRYPOINT ["java", "-jar", "/app/watch-cluster.jar"]