# Fresnel installation guide

Fresnel is distributed as a Docker image, native Windows/Linux installers,
portable archives and an executable Spring Boot JAR. All variants contain the
same React application and REST API.

## Choose an installation mode

| Mode | Best for | Java required |
|---|---|---:|
| Docker | controlled server/container deployment | no |
| Windows MSI | local Windows desktop use | no |
| Debian package | local Linux desktop use | no |
| portable ZIP/tar.gz | systems with Java 21 | yes |
| executable JAR | development, CI or managed deployment | yes |

Native desktop and default JAR operation are loopback-only. Docker and
PostgreSQL are network profiles and require explicit credentials.

## 1. Docker

The image activates the strict `container` profile. It deliberately refuses to
start until both application passwords are supplied. Passwords must contain at
least 12 characters, must differ and may not equal the corresponding username or
a published default. This guide intentionally contains no example password
values; supply secrets through the calling environment or an external secret
manager.

```bash
export FRESNEL_SECURITY_USER_USERNAME=alice
export FRESNEL_SECURITY_ADMIN_USERNAME=fresnel-admin
: "${FRESNEL_SECURITY_USER_PASSWORD:?set the application-user password}"
: "${FRESNEL_SECURITY_ADMIN_PASSWORD:?set the administrator password}"

docker run --rm -p 127.0.0.1:8080:8080 \
  -e FRESNEL_SECURITY_USER_USERNAME \
  -e FRESNEL_SECURITY_USER_PASSWORD \
  -e FRESNEL_SECURITY_ADMIN_USERNAME \
  -e FRESNEL_SECURITY_ADMIN_PASSWORD \
  ghcr.io/carstenartur/fresnel:latest
```

Open `http://localhost:8080` and authenticate with the configured account.

### Persistent H2 in Docker

Compose `standalone` with the final strict `container` profile:

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

### PostgreSQL in Docker

Use `container,postgres` and provide every database and application secret:

```bash
: "${DB_PASSWORD:?set the database password}"

docker run --rm -p 127.0.0.1:8080:8080 \
  -e SPRING_PROFILES_ACTIVE=container,postgres \
  -e DB_URL='jdbc:postgresql://db:5432/fresnel' \
  -e DB_USER='fresnel_app' \
  -e DB_PASSWORD \
  -e FRESNEL_SECURITY_USER_USERNAME \
  -e FRESNEL_SECURITY_USER_PASSWORD \
  -e FRESNEL_SECURITY_ADMIN_USERNAME \
  -e FRESNEL_SECURITY_ADMIN_PASSWORD \
  ghcr.io/carstenartur/fresnel:latest
```

For LAN, Internet or orchestrated deployment, terminate TLS at a trusted reverse
proxy/ingress, prevent direct access to port 8080 and configure an exact
`FRESNEL_CORS_ALLOWED_ORIGINS` allowlist. See
[`docs/security/deployment.md`](../docs/security/deployment.md).

## 2. Windows installer

Download `Fresnel-<version>.msi` from the
[Releases page](https://github.com/carstenartur/Fresnel/releases). The installer:

- installs under `C:\Program Files\Fresnel\`;
- bundles a private Java 21 runtime;
- registers a Start-menu shortcut and `.fresnel` file handler;
- stores user data under `%APPDATA%\Fresnel\`;
- starts an application server bound to `127.0.0.1`.

Open a job directly with:

```bat
"C:\Program Files\Fresnel\Fresnel.exe" --open "D:\Optics\example.fresnel"
```

Windows may retain an existing user-selected file association. Use **Open
with…** to choose Fresnel rather than editing the registry manually.

### ZIP fallback

The Windows ZIP requires Java 21 on `PATH` or through `JAVA_HOME`:

```bat
bin\start-fresnel.bat
```

The portable archive does not register file associations or provide the native
single-instance launcher.

## 3. Linux installer

Download `fresnel_<version>_amd64.deb` and install it with:

```bash
sudo apt install ./fresnel_<version>_amd64.deb
```

The package installs under `/opt/fresnel/`, bundles a private Java runtime,
registers the Fresnel MIME type and stores data under
`$HOME/.local/share/Fresnel/` unless `FRESNEL_DATA_DIR` is set.

Open a job explicitly with:

```bash
/opt/fresnel/bin/Fresnel --open "$HOME/Optics/example.fresnel"
```

### tar.gz fallback

```bash
tar -xzf fresnel-<version>-linux.tar.gz
cd fresnel-<version>
./bin/start-fresnel.sh
```

This path requires Java 21 and does not install a desktop file association.

## 4. Executable JAR

Local in-memory operation:

```bash
java -jar backend-<version>.jar
```

Local persistent H2 operation:

```bash
java -Dspring.profiles.active=standalone -jar backend-<version>.jar
```

Both modes bind to `127.0.0.1` by default. Do not place them behind a network
proxy. For a managed PostgreSQL deployment, activate `postgres` and set the
required DB and application secrets described in the secure deployment guide.

## Desktop single-instance behavior

Native installations support:

```text
Fresnel
Fresnel /path/to/example.fresnel
Fresnel --open /path/to/example.fresnel
```

Only one packaged desktop process owns the local server for a given data
directory. A subsequent invocation authenticates to the loopback primary,
submits the job bytes and receives a random one-time import token. Source paths
are not sent over HTTP or retained in browser history. Tokens expire after five
minutes and work once.

## Data locations

| Item | Windows | Linux | macOS / JAR |
|---|---|---|---|
| database | `%APPDATA%\Fresnel\db\` | `$HOME/.local/share/Fresnel/db/` | under `FRESNEL_DATA_DIR` |
| desktop metadata | `%APPDATA%\Fresnel\desktop-instance*` | `$HOME/.local/share/Fresnel/desktop-instance*` | under `FRESNEL_DATA_DIR` |
| external config | installation `config` directory | installation `config` or `/etc/fresnel` | configurable |

Override the data directory before launch:

```bash
FRESNEL_DATA_DIR=/srv/fresnel ./bin/start-fresnel.sh
```

```bat
set FRESNEL_DATA_DIR=D:\Fresnel
bin\start-fresnel.bat
```

Each distinct data directory has an independent desktop single-instance scope.

## Port configuration

Set `SERVER_PORT` before launch:

```bash
SERVER_PORT=9090 ./bin/start-fresnel.sh
```

```bat
set SERVER_PORT=9090
bin\start-fresnel.bat
```

Desktop mode always forces `server.address=127.0.0.1`. Server/container profiles
use `SERVER_ADDRESS`, which defaults to `0.0.0.0`; those profiles require explicit
credentials.

## Render-job privacy and retention

Render-job submission, status, SSE progress and result downloads require
authentication. Only the owner or an administrator can retrieve a job. The
primary job ID contains 192 bits of random entropy and is not a public share
link. Unknown and unauthorized identifiers both return `404`.

Terminal job results default to 30 days of persisted retention. Override with:

```properties
fresnel.jobs.persisted-retention-days=30
```

## Reset local data

Stop Fresnel, then remove the configured data directory:

```bash
rm -rf "$HOME/.local/share/Fresnel"
```

```bat
rmdir /S /Q "%APPDATA%\Fresnel"
```

The application recreates an empty database and desktop coordination files at
the next start.

## Building packages locally

```bash
mvn -B -ntp -Pfrontend,release-package -pl backend -am package
```

This creates the executable JAR plus Windows ZIP and Linux tar.gz archives under
`backend/target/dist/`.

Native packages require `jpackage` from JDK 21 or newer:

```bash
JPACKAGE_TYPE=deb bash packaging/jpackage/build-linux.sh
```

```bat
set JPACKAGE_TYPE=msi
packaging\jpackage\build-windows.cmd
```

See [`packaging/jpackage/README.md`](jpackage/README.md) for package metadata,
CI integration and smoke-test requirements.
