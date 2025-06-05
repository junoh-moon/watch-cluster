FROM gradle:8.5-jdk17 AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:17-jre
ENV TZ=Asia/Seoul
WORKDIR /app
COPY --from=builder /app/build/libs/watch-cluster.jar /app/watch-cluster.jar
USER 1000:1000
ENTRYPOINT ["java", "-jar", "/app/watch-cluster.jar"]