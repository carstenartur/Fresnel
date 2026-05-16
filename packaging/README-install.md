# Fresnel — Installation Guide

Fresnel ships in four supported flavours. Pick the one that matches
your use-case; they all run the same Spring Boot application and serve
the bundled React UI at `http://localhost:8080`.

| Flavour              | Best for                                  | Java required? |
|----------------------|-------------------------------------------|----------------|
| Docker image         | Server / container deployments            | No             |
| Windows installer    | Local desktop use on Windows              | No (bundled)   |
| Linux installer/tgz  | Local desktop use on Linux                | No (bundled)   |
| Plain executable jar | Anywhere you already have JDK 21 (CI etc) | Yes            |

> ℹ️ The installers bundle a private Java runtime via `jpackage`, so
> end users do **not** need to install a JDK manually. The plain jar
> path is for developers and CI.

---

## 1. Docker (recommended for servers)

```bash
docker run --rm -p 8080:8080 ghcr.io/carstenartur/fresnel:latest
# → http://localhost:8080
```

To persist the database between container restarts, mount a volume at
`/data` and point Fresnel at it:

```bash
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=standalone \
  -e FRESNEL_DATA_DIR=/data \
  -v fresnel-data:/data \
  ghcr.io/carstenartur/fresnel:latest
```

The default container image uses the in-memory H2 profile; use the
`standalone` profile (above) when you want a persistent local database
without setting up PostgreSQL.

---

## 2. Windows installer

Download the latest `Fresnel-<version>.msi` (or the
`fresnel-<version>-windows.zip` fallback) from the
[Releases page](https://github.com/carstenartur/Fresnel/releases) and
run it. The installer:

* installs Fresnel under `C:\Program Files\Fresnel\`,
* registers a Start-menu shortcut **Fresnel**,
* bundles its own Java runtime — no JDK needed,
* stores the database and other user data **outside** the install
  directory, under `%APPDATA%\Fresnel\` (typically
  `C:\Users\<you>\AppData\Roaming\Fresnel`).

Click the Start-menu entry (or run `start-fresnel.bat` from the install
folder) and open <http://localhost:8080>.

### ZIP fallback (no admin rights)

If you cannot run an installer, download the `*-windows.zip` archive
and unzip it anywhere. Then double-click `bin\start-fresnel.bat`.
You will need a system-wide Java 21 runtime in that case (`JAVA_HOME`
or `java` on `PATH`).

---

## 3. Linux installer

Download either the `.deb` (Debian / Ubuntu) or the
`fresnel-<version>-linux.tar.gz` fallback from the
[Releases page](https://github.com/carstenartur/Fresnel/releases).

### Debian / Ubuntu

```bash
sudo apt install ./fresnel_<version>_amd64.deb
fresnel    # or: /opt/fresnel/bin/Fresnel
```

The package installs under `/opt/fresnel/`, includes a private JRE, and
stores user data under `$HOME/.local/share/fresnel/`.

### tar.gz fallback

```bash
tar -xzf fresnel-<version>-linux.tar.gz
cd fresnel-<version>
./bin/start-fresnel.sh
```

You will need Java 21 on `PATH` for this path (the installer is the
one that ships a bundled JRE).

---

## 4. Plain JAR (developers / CI)

```bash
java -jar backend-<version>.jar
# → http://localhost:8080  (in-memory H2, data lost on restart)
```

For a persistent local database (same setup the installers use):

```bash
java -Dspring.profiles.active=standalone \
     -jar backend-<version>.jar
```

The standalone profile writes the H2 database to
`$HOME/.fresnel/db/fresnel.mv.db` unless you set `FRESNEL_DATA_DIR`.

---

## Where things are stored

| Item       | Windows                        | Linux                                   | macOS                                              |
|------------|--------------------------------|-----------------------------------------|----------------------------------------------------|
| Database   | `%APPDATA%\Fresnel\db\`        | `$HOME/.local/share/fresnel/db/`        | `$HOME/Library/Application Support/Fresnel/db/`    |
| Config     | `<install>\config\`            | `<install>/config/` or `/etc/fresnel/`  | `<install>/config/`                                |
| Logs       | stdout (run from a terminal)   | stdout                                  | stdout                                             |

Override the data directory at any time:

```bash
# Linux/macOS
FRESNEL_DATA_DIR=/srv/fresnel ./bin/start-fresnel.sh
```

```bat
:: Windows
set FRESNEL_DATA_DIR=D:\Fresnel
bin\start-fresnel.bat
```

---

## Changing the port

The launch scripts honour `SERVER_PORT`, or you can uncomment
`server.port=` in
`config/application-standalone.properties`:

```bash
SERVER_PORT=9090 ./bin/start-fresnel.sh
```

```bat
set SERVER_PORT=9090
bin\start-fresnel.bat
```

---

## Resetting / deleting local data

Stop Fresnel, then delete the contents of `FRESNEL_DATA_DIR`:

```bash
# Linux
rm -rf "$HOME/.local/share/fresnel"
```

```bat
:: Windows
rmdir /S /Q "%APPDATA%\Fresnel"
```

The application will recreate an empty database on the next start.

---

## Building the installers locally

```bash
# Builds the jar plus the Windows ZIP and Linux tar.gz under
# backend/target/dist/.
mvn -B -ntp -Prelease-package -pl backend -am package

# Native installers (requires jpackage from JDK 21+):
JPACKAGE_TYPE=deb bash packaging/jpackage/build-linux.sh    # on Linux
```

```bat
:: Native Windows installer (run on Windows in a CMD shell):
set JPACKAGE_TYPE=msi
packaging\jpackage\build-windows.cmd
```

See [`packaging/jpackage/README.md`](jpackage/README.md) for details
and CI integration.
