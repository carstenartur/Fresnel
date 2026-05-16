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

## CI

`.github/workflows/release-package.yml` runs `build-linux.sh` on the
`ubuntu-latest` runner and `build-windows.cmd` on `windows-latest`,
and uploads the resulting installers, the ZIP/tgz fallbacks, and the
plain jar as release artifacts.
