# ──────────────────────────────────────────────────────────────────────────────
# Stage 1 – Build the React/TypeScript frontend
# ──────────────────────────────────────────────────────────────────────────────
FROM node:26.8.2-alpine3.23@sha256:a3024faf41c40992531ecfb00604384665be870a44626afaf181c6d583f89296 AS frontend-build

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
FROM maven:3-eclipse-temurin-26@sha256:2bc6924d954a6efefde0a9629228914acf72e4abcdb927b68f19edd1135a0cb1 AS backend-build

WORKDIR /app

# Copy Maven POMs first for dependency caching
COPY pom.xml ./
COPY optics-core/pom.xml optics-core/pom.xml
COPY measurement-core/pom.xml measurement-core/pom.xml
COPY backend/pom.xml backend/pom.xml

# Pre-fetch dependencies (layer cached unless POMs change)
RUN mvn -B -ntp dependency:go-offline -Pno-frontend 2>/dev/null || true

# Copy sources
COPY optics-core/src optics-core/src
COPY measurement-core/src measurement-core/src
COPY backend/src backend/src

# Copy the pre-built frontend assets into the Spring Boot static resource dir
COPY --from=frontend-build /app/frontend/dist backend/src/main/resources/static/

# Build the fat jar – frontend is already in static/, skip the Maven frontend build
RUN mvn -B -ntp -Pno-frontend -DskipTests package

# ──────────────────────────────────────────────────────────────────────────────
# Stage 3 – Runtime image (minimal JRE, non-root)
# ──────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:24.0.2_12-jre-noble@sha256:b416d02335e702b0403ff280de9475a3348e29382285969c9d4e17862ce632e7 AS runtime

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
