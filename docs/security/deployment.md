# Secure deployment

Fresnel has two distinct trust models:

1. **Local/desktop** — the default and `standalone` profiles bind to
   `127.0.0.1`. Built-in development accounts exist only to keep local
   development and packaged single-user desktop operation simple; their
   credential values are not repeated in public documentation.
2. **Network-exposed** — the `container` and `postgres` profiles bind to a
   configurable network address and fail startup until explicit credentials are
   supplied.

Do not expose a local/desktop profile through port forwarding, a reverse proxy or
an ingress controller.

## Required application credentials

Container and PostgreSQL profiles require two distinct passwords of at least 12
characters. Published defaults, usernames used as passwords and blank values are
rejected during application startup. Supply the values through the calling
environment or a secrets manager; this documentation intentionally contains no
example passwords.

```bash
export FRESNEL_SECURITY_USER_USERNAME=alice
export FRESNEL_SECURITY_ADMIN_USERNAME=fresnel-admin
: "${FRESNEL_SECURITY_USER_PASSWORD:?set the application-user password}"
: "${FRESNEL_SECURITY_ADMIN_PASSWORD:?set the administrator password}"
export FRESNEL_SECURITY_USER_PASSWORD FRESNEL_SECURITY_ADMIN_PASSWORD
```

The administrator and ordinary-user passwords must differ.

## Docker with in-memory H2

```bash
docker run --rm -p 127.0.0.1:8080:8080 \
  -e FRESNEL_SECURITY_USER_USERNAME \
  -e FRESNEL_SECURITY_USER_PASSWORD \
  -e FRESNEL_SECURITY_ADMIN_USERNAME \
  -e FRESNEL_SECURITY_ADMIN_PASSWORD \
  ghcr.io/carstenartur/fresnel:latest
```

The image activates the `container` profile by default. Omitting either password
causes startup to fail instead of silently enabling a known account.

## Docker with persistent H2

Use `standalone,container` in that order: `standalone` selects the file database,
while the final `container` profile restores network binding and the strict
credential policy.

```bash
docker run --rm -p 127.0.0.1:8080:8080 \
  -e SPRING_PROFILES_ACTIVE=standalone,container \
  -e FRESNEL_DATA_DIR=/data \
  -e FRESNEL_SECURITY_USER_USERNAME \
  -e FRESNEL_SECURITY_USER_PASSWORD \
  -e FRESNEL_SECURITY_ADMIN_USERNAME \
  -e FRESNEL_SECURITY_ADMIN_PASSWORD \
  -v fresnel-data:/data \
  ghcr.io/carstenartur/fresnel:latest
```

## PostgreSQL

The `postgres` profile requires all database and application credentials. It has
no built-in database or HTTP-Basic password fallback.

```bash
export SPRING_PROFILES_ACTIVE=postgres
export DB_URL='jdbc:postgresql://db.example.internal:5432/fresnel'
export DB_USER='fresnel_app'
export FRESNEL_SECURITY_USER_USERNAME=alice
export FRESNEL_SECURITY_ADMIN_USERNAME=fresnel-admin
: "${DB_PASSWORD:?set the database password}"
: "${FRESNEL_SECURITY_USER_PASSWORD:?set the application-user password}"
: "${FRESNEL_SECURITY_ADMIN_PASSWORD:?set the administrator password}"
export DB_PASSWORD FRESNEL_SECURITY_USER_PASSWORD FRESNEL_SECURITY_ADMIN_PASSWORD
java -jar backend-*.jar
```

Inside Docker use `SPRING_PROFILES_ACTIVE=container,postgres`.

## TLS is mandatory off loopback

Fresnel currently uses HTTP Basic authentication. Basic authentication does not
encrypt credentials; it only encodes them. Every LAN, Internet, Kubernetes or
shared-host deployment must terminate TLS at a trusted reverse proxy, load
balancer or ingress and forward traffic to Fresnel over a protected internal
network.

Recommended controls:

- redirect all clear-text HTTP to HTTPS;
- use a valid certificate and modern TLS policy;
- restrict direct access to port 8080;
- set `FRESNEL_CORS_ALLOWED_ORIGINS` to the exact HTTPS origins that need API
  access;
- store passwords in a secrets manager or orchestrator secret, not in images,
  Compose files committed to source control or shell history;
- rotate both application accounts after suspected disclosure.

## Render-job privacy and retention

Render-job submission, status, progress events and result downloads all require
authentication. The primary job identifier contains 192 bits of random entropy,
but it is still treated as a private identifier rather than a public share URL.
Only the owner or an administrator can read a job; unknown and unauthorized IDs
both return `404`.

Detailed worker exceptions remain server-side. Clients receive a generic
`render failed` status.

Live in-memory jobs are retained for 30 minutes. Persisted terminal job results
are removed opportunistically after the configured retention period, which
defaults to 30 days:

```properties
fresnel.jobs.persisted-retention-days=30
```

There is no implicit public sharing. A future sharing feature must use a separate,
revocable high-entropy token instead of weakening owner checks on the primary ID.
