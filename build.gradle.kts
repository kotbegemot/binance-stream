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
    testImplementation(libs.awaitility)
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

// Run the service with Java Flight Recorder enabled. Captures a .jfr profile
// to perf/quotes-service.jfr for the configured duration (default 600s).
// Override via -PjfrDurationSec=300, -PjfrFile=perf/run-2.jfr, -PjfrSettings=default.
tasks.register<JavaExec>("runWithJfr") {
    group = "application"
    description = "Run the service with Java Flight Recorder enabled (perf profile)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.stream.quotes.Main")

    val durationSec = (project.findProperty("jfrDurationSec") as String?) ?: "600"
    val file = (project.findProperty("jfrFile") as String?) ?: "perf/quotes-service.jfr"
    val settings = (project.findProperty("jfrSettings") as String?) ?: "profile"

    doFirst {
        file("perf").mkdirs()
    }

    jvmArgs(
        "-XX:StartFlightRecording=" +
            "filename=$file,duration=${durationSec}s,settings=$settings,name=quotes-service",
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:+DebugNonSafepoints"
    )
}