# Fresnel — Installation Guide

Fresnel ships in four supported flavours. Pick the one that matches
your use-case; they all run the same Spring Boot application and serve
the bundled React UI at `http://localhost:8080` by default.

| Flavour              | Best for                                  | Java required? |
|----------------------|-------------------------------------------|----------------|
| Docker image         | Server / container deployments            | No             |
| Windows installer    | Local desktop use on Windows              | No (bundled)   |
| Linux installer/tgz  | Local desktop use on Linux                | No (bundled)   |
| Plain executable jar | Anywhere you already have JDK 21 (CI etc) | Yes            |

> ℹ️ The native installers bundle a private Java runtime via `jpackage`,
> register `.fresnel` production jobs and launch the browser automatically.
> End users do **not** need to install a JDK manually. The archive, plain-jar
> and Docker paths retain the conventional server-style startup behavior.

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

Docker does not register desktop file associations or open a browser.

---

## 2. Windows installer

Download the latest `Fresnel-<version>.msi` (or the
`fresnel-<version>-windows.zip` fallback) from the
[Releases page](https://github.com/carstenartur/Fresnel/releases) and
run it. The installer:

* installs Fresnel under `C:\Program Files\Fresnel\`,
* registers a Start-menu shortcut **Fresnel**,
* registers Fresnel as a handler for `.fresnel` production jobs,
* bundles its own Java runtime — no JDK needed,
* stores the database, desktop lock and other user data **outside** the
  install directory, under `%APPDATA%\Fresnel\` (typically
  `C:\Users\<you>\AppData\Roaming\Fresnel`).

Click the Start-menu entry to start Fresnel. The installed application waits
until its loopback server is ready and opens the correct local URL in the
default browser.

To open a design directly, double-click a `.fresnel` file or use:

```bat
"C:\Program Files\Fresnel\Fresnel.exe" --open "D:\Optics\example.fresnel"
```

If Windows asks which application to use, choose **Fresnel** through
**Open with…** and optionally select it as the default for `.fresnel` files.
The installer advertises the handler but does not override an existing
user-selected default application by editing the registry behind the user's
back.

### ZIP fallback (no admin rights)

If you cannot run an installer, download the `*-windows.zip` archive
and unzip it anywhere. Then double-click `bin\start-fresnel.bat`.
You will need a system-wide Java 21 runtime in that case (`JAVA_HOME`
or `java` on `PATH`).

The ZIP fallback does not register `.fresnel` files and does not enable the
single-instance desktop launcher. Open jobs with the **Open job…** control in
the web interface.

---

## 3. Linux installer

Download either the `.deb` (Debian / Ubuntu) or the
`fresnel-<version>-linux.tar.gz` fallback from the
[Releases page](https://github.com/carstenartur/Fresnel/releases).

### Debian / Ubuntu

```bash
sudo apt install ./fresnel_<version>_amd64.deb
Fresnel    # normally launch from the desktop application menu
```

The package installs under `/opt/fresnel/`, includes a private JRE, registers
the MIME type `application/vnd.carstenartur.fresnel.job+json`, and stores user
data under `$HOME/.local/share/Fresnel/` unless `XDG_DATA_HOME` or
`FRESNEL_DATA_DIR` is set.

Double-clicking a `.fresnel` job in a desktop file manager starts Fresnel or
hands the job to the already-running primary instance. A command-line fallback
is:

```bash
/opt/fresnel/bin/Fresnel --open "$HOME/Optics/example.fresnel"
```

Desktop environments may keep an existing per-user default application. Use
the file manager's **Open With…** action to choose Fresnel, or use the desktop's
normal default-application settings. Package installation intentionally does
not overwrite a user preference with ad-hoc MIME database commands.

### tar.gz fallback

```bash
tar -xzf fresnel-<version>-linux.tar.gz
cd fresnel-<version>
./bin/start-fresnel.sh
```

You will need Java 21 on `PATH` for this path (the installer is the
one that ships a bundled JRE). The tar.gz fallback does not install a desktop
file association; use **Open job…** in the browser UI.

---

## Opening jobs and single-instance behavior

Native `.msi` and `.deb` installations support these launch forms:

```text
Fresnel
Fresnel /path/to/example.fresnel
Fresnel --open /path/to/example.fresnel
```

Only one packaged desktop process owns the local Fresnel server for a given
user-data directory. When Fresnel is already running, another invocation:

1. verifies the recorded process through an authenticated loopback ping,
2. sends the job bytes to the primary instance,
3. receives a random one-time import token,
4. opens a `127.0.0.1` browser URL containing only that token.

The local source path is never sent through HTTP or placed in browser history.
The React application removes the token from the address bar before consuming
it. Tokens expire after five minutes and work only once. Invalid jobs are shown
as in-application errors and do not terminate the healthy primary process or
replace a valid current design.

If a former process crashed, the next launcher safely takes the released file
lock and removes stale metadata. No manual lock-file cleanup should be needed.

---

## 4. Plain JAR (developers / CI)

```bash
java -jar backend-<version>.jar
# → http://localhost:8080  (in-memory H2, data lost on restart)
```

For a persistent local database (same storage profile the installers use):

```bash
java -Dspring.profiles.active=standalone \
     -jar backend-<version>.jar
```

Plain-jar startup does not enable desktop file association, single-instance
locking or automatic browser opening. This preserves normal server and CI
behavior. The standalone profile writes the H2 database to the configured
Fresnel data directory unless you set `FRESNEL_DATA_DIR`.

---

## Where things are stored

| Item             | Windows                              | Linux                                      | macOS                                              |
|------------------|--------------------------------------|--------------------------------------------|----------------------------------------------------|
| Database         | `%APPDATA%\Fresnel\db\`             | `$HOME/.local/share/Fresnel/db/`           | `$HOME/Library/Application Support/Fresnel/db/`    |
| Desktop metadata | `%APPDATA%\Fresnel\desktop-instance*`| `$HOME/.local/share/Fresnel/desktop-instance*` | `$HOME/Library/Application Support/Fresnel/desktop-instance*` |
| Config           | `<install>\config\`                 | `<install>/config/` or `/etc/fresnel/`     | `<install>/config/`                                |
| Logs             | stdout/stderr when run from terminal | stdout/stderr                              | stdout/stderr                                      |

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

For native desktop installations, changing `FRESNEL_DATA_DIR` changes the
single-instance scope as well: each distinct data directory can own one primary
process.

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

Native desktop mode records and opens the actual configured port after the
server starts. It always forces `server.address=127.0.0.1`; a desktop import
endpoint is never exposed to the network.

---

## Repairing a file association

An operating system may retain a previous user-selected default after Fresnel is
installed or upgraded.

**Windows:** right-click a `.fresnel` file, choose **Open with → Choose another
app**, select **Fresnel**, and use the normal “always use” option when desired.

**Linux desktop:** use the file manager's **Open With…** or file-properties
application selector. Package metadata advertises Fresnel and its MIME type, but
the exact UI differs between GNOME, KDE and other desktops.

The command-line `--open` form shown above works independently of the default
association and is also useful for diagnostics.

---

## Resetting / deleting local data

Stop Fresnel, then delete the contents of `FRESNEL_DATA_DIR`:

```bash
# Linux
rm -rf "$HOME/.local/share/Fresnel"
```

```bat
:: Windows
rmdir /S /Q "%APPDATA%\Fresnel"
```

The application will recreate an empty database and desktop coordination files
on the next start.

---

## Building the installers locally

```bash
# Activating an explicit Maven profile suppresses activeByDefault profiles, so
# name both the frontend build and the release archive profile explicitly.
# The command builds the jar plus the Windows ZIP and Linux tar.gz under
# backend/target/dist/.
mvn -B -ntp -Pfrontend,release-package -pl backend -am package

# Native installers (requires jpackage from JDK 21+):
JPACKAGE_TYPE=deb bash packaging/jpackage/build-linux.sh    # on Linux
```

```bat
:: Native Windows installer (run on Windows in a CMD shell):
set JPACKAGE_TYPE=msi
packaging\jpackage\build-windows.cmd
```

See [`packaging/jpackage/README.md`](jpackage/README.md) for package metadata,
security details, CI integration and the release smoke-test checklist.
