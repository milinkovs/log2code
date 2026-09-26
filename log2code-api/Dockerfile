# syntax=docker/dockerfile:1
# log2code-api with the web build (T32). Build context is log2code/ (the repository root):
#   docker build -f log2code-api/Dockerfile -t log2code/log2code-api .
# or through infra/docker-compose.yml (profile "app").

# --- web: React build -> /web/dist ------------------------------------------------------------
FROM node:24-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY web/ ./
RUN npm run build

# --- build: log2code-api executable jar (with log2code-core) ----------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# the wrapper downloads Maven as a .zip distribution
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
# the reactor reads every module's pom, even with -pl ... -am
COPY log2code-core/pom.xml log2code-core/
COPY log2code-analyzer/pom.xml log2code-analyzer/
COPY log2code-ingester/pom.xml log2code-ingester/
COPY log2code-eval/pom.xml log2code-eval/
COPY log2code-api/pom.xml log2code-api/
COPY log2code-core/src/main/ log2code-core/src/main/
COPY log2code-api/src/main/ log2code-api/src/main/
RUN --mount=type=cache,target=/root/.m2 \
    sh ./mvnw -q -pl log2code-api -am package -DskipTests \
    && cp log2code-api/target/log2code-api-*-exec.jar /src/app.jar

# --- runtime ------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system log2code && useradd --system --gid log2code --no-create-home log2code
COPY --from=build /src/app.jar /app/app.jar
COPY --from=web /web/dist/ /app/static/
ENV SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/app/static/,classpath:/static/
USER log2code
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
