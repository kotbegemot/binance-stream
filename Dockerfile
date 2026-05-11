# syntax=docker/dockerfile:1.6

# -------- Stage 1: builder --------
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Copy wrapper first so the distribution caches between builds.
COPY gradle gradle
COPY gradlew gradlew
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --version --no-daemon

# Then source.
COPY src src

RUN ./gradlew installDist --no-daemon --stacktrace

# -------- Stage 2: runtime --------
FROM eclipse-temurin:21-jre AS runtime

WORKDIR /app

COPY --from=builder /app/build/install/binance-quotes-service /app

EXPOSE 8080

ENV QUOTES_DB_PATH=/data/quotes.db

ENTRYPOINT ["/app/bin/binance-quotes-service"]