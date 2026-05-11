plugins {
    application
    java
    jacoco
}

group = "io.stream"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "io.stream.quotes.Main"
    applicationName = "binance-quotes-service"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.javalin)
    implementation(libs.jetty.websocket.jetty.client)
    implementation(libs.jackson.databind)
    implementation(libs.snakeyaml)
    implementation(libs.sqlite.jdbc)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required = true
        xml.required = true
    }
}