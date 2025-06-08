FROM gradle:8.5-jdk17 AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY gradlew ./
RUN gradle dependencies --no-daemon
COPY src ./src
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
ENV TZ=Asia/Seoul
RUN apk add --no-cache tzdata
WORKDIR /app
COPY --from=builder /app/build/libs/watch-cluster.jar /app/watch-cluster.jar
USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/watch-cluster.jar"]