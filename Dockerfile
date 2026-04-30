# ──────────────────────────────────────────────────────────────────────────────
# Stage 1 – Build the React/TypeScript frontend
# ──────────────────────────────────────────────────────────────────────────────
FROM node:20-alpine AS frontend-build

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
FROM maven:3.9-eclipse-temurin-21 AS backend-build

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
FROM eclipse-temurin:21-jre AS runtime

# Create a non-root user
RUN groupadd --system fresnel && useradd --system --gid fresnel fresnel

WORKDIR /app

# Copy the executable jar from the build stage
COPY --from=backend-build /app/backend/target/backend-*.jar app.jar

RUN chown fresnel:fresnel /app/app.jar

USER fresnel

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
