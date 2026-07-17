# jpackage installers

These scripts wrap `jpackage` from JDK 21+ to build native installers
that bundle a private Java runtime. They run on the matching host OS:
Windows installers must be built on Windows, Linux packages on Linux.

Both scripts expect the Spring Boot fat jar to already be built at
`backend/target/backend-<version>.jar` (run `mvn -B -ntp -Pno-frontend
package` or the regular `mvn package` first, or rely on the
`release-package` Maven profile / CI workflow that runs them in the
correct order).

## Inputs

The scripts read these environment variables:

| Variable        | Default                              | Description                                           |
|-----------------|--------------------------------------|-------------------------------------------------------|
| `APP_VERSION`   | parsed from the jar filename         | Version embedded in the installer.                    |
| `APP_JAR`       | newest `backend/target/backend-*.jar`| Path to the Spring Boot jar.                          |
| `OUTPUT_DIR`    | `backend/target/dist`                | Where the installer is written.                       |
| `JPACKAGE_TYPE` | `msi` on Windows, `deb` on Linux     | Installer type. `app-image` works on every platform.  |

## Quick start

```bash
# Linux .deb (Debian / Ubuntu)
mvn -B -ntp -Pno-frontend -DskipTests package
JPACKAGE_TYPE=deb bash packaging/jpackage/build-linux.sh

# Windows .msi (run in a Windows shell / GitHub Actions Windows runner)
mvn -B -ntp -Pno-frontend -DskipTests package
set JPACKAGE_TYPE=msi
packaging\jpackage\build-windows.cmd
```

The output (`*.deb`, `*.msi`, `*.rpm`, or an `app-image` directory) is
written to `backend/target/dist/` so it sits alongside the ZIP / tgz
artifacts produced by the `release-package` Maven profile.

## `.fresnel` file association

Both native packages consume the shared descriptor
[`fresnel-job.properties`](fresnel-job.properties):

```properties
extension=fresnel
mime-type=application/vnd.carstenartur.fresnel.job+json
description=Fresnel production job
```

The installer registers Fresnel as a capable handler for `.fresnel` jobs.
It does not overwrite a user-selected default application through custom
registry or desktop-database edits. Depending on operating-system policy,
the user may need to select Fresnel once through **Open with…**.

The packaged launcher receives the associated file as an ordinary command-line
argument. Supported forms are:

```text
Fresnel
Fresnel /path/to/example.fresnel
Fresnel --open /path/to/example.fresnel
Fresnel /path/to/example.fresnel -- --server.port=9090
```

Arguments after `--` are Spring Boot arguments and are accepted only by the
primary process. Desktop security properties and `server.address` are reserved
by the launcher and cannot be overridden there.

Native packages add these Java options:

```text
-Dspring.profiles.active=standalone
-Dfresnel.desktop.enabled=true
```

The ZIP/tar.gz fallback launchers, plain jar, Docker image and development
commands do not enable desktop mode automatically.

## Desktop launch protocol

The first packaged invocation becomes the primary process by taking an exclusive
lock in the per-user Fresnel data directory. Once the embedded server is ready it
publishes only:

- protocol version,
- actual loopback port,
- process ID,
- a random per-process session secret,
- start timestamp.

A second invocation authenticates to `127.0.0.1` with that secret and sends the
job bytes. It never sends the local source path. The browser receives a separate,
short-lived, one-time import token, which the React application removes from the
address bar before consuming it. Invalid jobs become in-application errors and do
not stop the healthy primary process.

Metadata files use owner-only permissions on POSIX filesystems and inherited
per-user ACLs on Windows. A new lock owner removes stale metadata left by a
crashed former process.

## Release smoke-test checklist

Run these checks on the installed `.msi` and `.deb` artifacts before publishing a
release:

1. Ordinary Start-menu/application-menu launch opens Fresnel in the browser.
2. Opening `docs/jobs/zone-plate/on-axis.fresnel` starts Fresnel when stopped and
   selects the Zone Plate editor.
3. Opening a second `.fresnel` file while Fresnel is running reuses the primary
   process and does not report a port conflict.
4. A filename containing spaces and non-ASCII characters opens successfully.
5. A malformed job opens the application and shows an import error without
   replacing an already valid design.
6. The browser URL contains only an opaque `fresnelOpen` token momentarily and
   contains no local path after the page initializes.
7. **Open with…** lists Fresnel; changing the default application remains under
   user/OS control.
8. Uninstall removes package-owned shortcuts and file-association metadata.
9. Plain `java -jar`, Docker and development starts do not open a browser or take
   the desktop lock.

CI verifies the shared MIME metadata and package command lines. Full desktop-shell
association behavior still requires these host-OS smoke tests.

## CI

`.github/workflows/release-package.yml` runs `build-linux.sh` on the
`ubuntu-latest` runner and `build-windows.cmd` on `windows-latest`,
and uploads the resulting installers, the ZIP/tgz fallbacks, and the
plain jar as release artifacts.
