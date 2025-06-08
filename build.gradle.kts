import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.6"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
    jacoco
}

group = "com.watchcluster"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    
    // Jackson for Spring Boot (explicitly included for Kotlin support)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    
    // Existing dependencies
    implementation("io.fabric8:kubernetes-client:6.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("com.cronutils:cron-utils:9.2.1")
    implementation("com.github.docker-java:docker-java:3.3.4")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.3.4")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.watchcluster.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("watch-cluster")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}