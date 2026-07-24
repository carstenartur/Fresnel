# ──────────────────────────────────────────────────────────────────────────────
# Stage 1 – Build the React/TypeScript frontend
# ──────────────────────────────────────────────────────────────────────────────
FROM node:20.20.2-alpine3.23@sha256:fb4cd12c85ee03686f6af5362a0b0d56d50c58a04632e6c0fb8363f609372293 AS frontend-build

WORKDIR /app/frontend

# Install dependencies (cached layer)
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --legacy-peer-deps

# Copy source and build
COPY frontend/ ./
RUN npm run build

# ──────────────────────────────────────────────────────────────────────────────
# Stage 2 – Build the Spring Boot backend (frontend already built)
# ──────────────────────────────────────────────────────────────────────────────
FROM maven:3.9.16-eclipse-temurin-21@sha256:1bb51c5ed28b95aef2bc7b46bff6940da43747cdaf838ce4afc2081ce9403750 AS backend-build

WORKDIR /app

# Copy Maven POMs first for dependency caching
COPY pom.xml ./
COPY optics-core/pom.xml optics-core/pom.xml
COPY backend/pom.xml backend/pom.xml

# Pre-fetch dependencies (layer cached unless POMs change)
RUN mvn -B -ntp dependency:go-offline -Pno-frontend 2>/dev/null || true

# Copy sources
COPY optics-core/src optics-core/src
COPY backend/src backend/src

# Copy the pre-built frontend assets into the Spring Boot static resource dir
COPY --from=frontend-build /app/frontend/dist backend/src/main/resources/static/

# Build the fat jar – frontend is already in static/, skip the Maven frontend build
RUN mvn -B -ntp -Pno-frontend -DskipTests package

# ──────────────────────────────────────────────────────────────────────────────
# Stage 3 – Runtime image (minimal JRE, non-root)
# ──────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21.0.11_10-jre-noble@sha256:373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64 AS runtime

# Create a non-root user
RUN groupadd --system fresnel && useradd --system --gid fresnel fresnel

WORKDIR /app

# Copy the executable jar from the build stage
COPY --from=backend-build /app/backend/target/backend-*.jar app.jar

RUN chown fresnel:fresnel /app/app.jar

USER fresnel

# The network-facing container profile fails startup until operators provide
# FRESNEL_SECURITY_USER_PASSWORD and FRESNEL_SECURITY_ADMIN_PASSWORD. Override
# with "container,postgres" for PostgreSQL deployments or
# "standalone,container" for persistent H2 in a trusted container.
ENV SPRING_PROFILES_ACTIVE=container

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
