# ──────────────────────────────────────────────────────────────────────────────
# Stage 1 – Build the React/TypeScript frontend
# Reviewed multi-platform digest for node:22.23.1-alpine3.24.
# Dependabot keeps the tag and digest current through reviewed pull requests.
# ──────────────────────────────────────────────────────────────────────────────
FROM node:22.23.1-alpine3.24@sha256:16e22a550f3863206a3f701448c45f7912c6896a62de43add43bb9c86130c3e2 AS frontend-build

WORKDIR /app/frontend

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --legacy-peer-deps

COPY frontend/ ./
RUN npm run build

# ──────────────────────────────────────────────────────────────────────────────
# Stage 2 – Build the Spring Boot backend (frontend already built)
# Reviewed multi-platform digest for maven:3.9.16-eclipse-temurin-21.
# ──────────────────────────────────────────────────────────────────────────────
FROM maven:3.9.16-eclipse-temurin-21@sha256:2b4496088e7b80ae10a8c9f74e574ea21380325a006ec684532ad6bad5bc7273 AS backend-build

WORKDIR /app

COPY pom.xml ./
COPY optics-core/pom.xml optics-core/pom.xml
COPY backend/pom.xml backend/pom.xml

RUN mvn -B -ntp dependency:go-offline -Pno-frontend 2>/dev/null || true

COPY optics-core/src optics-core/src
COPY backend/src backend/src

COPY --from=frontend-build /app/frontend/dist backend/src/main/resources/static/

# Tests run in the CI/release gates. The image build consumes the same reviewed
# sources and creates only the packaged runtime artifact.
RUN mvn -B -ntp -Pno-frontend -DskipTests package

# ──────────────────────────────────────────────────────────────────────────────
# Stage 3 – Runtime image (minimal JRE, non-root)
# Reviewed multi-platform digest for eclipse-temurin:21.0.11_10-jre.
# ──────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21.0.11_10-jre@sha256:273396ed5998598ed1091e8d72711c2d36980a0e65103859c55a4e977a41ffd3 AS runtime

RUN groupadd --system fresnel && useradd --system --gid fresnel fresnel

WORKDIR /app

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
