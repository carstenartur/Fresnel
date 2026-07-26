import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REACT_FAMILY = ['react', 'react-dom', '@types/react', '@types/react-dom'];

function majorOf(version, label) {
  const match = String(version ?? '').match(/(?:^|[^0-9])(\d+)(?:\.|$)/);
  if (!match) {
    throw new Error(`Cannot determine a major version for ${label}: ${JSON.stringify(version)}`);
  }
  return Number(match[1]);
}

function manifestVersion(packageJson, packageName) {
  return packageJson.dependencies?.[packageName]
    ?? packageJson.devDependencies?.[packageName]
    ?? null;
}

function lockRootVersion(packageLock, packageName) {
  const root = packageLock.packages?.[''] ?? {};
  return root.dependencies?.[packageName]
    ?? root.devDependencies?.[packageName]
    ?? null;
}

function installedVersion(packageLock, packageName) {
  return packageLock.packages?.[`node_modules/${packageName}`]?.version ?? null;
}

function describeVersions(entries) {
  return entries.map(([name, version]) => `${name}=${version}`).join(', ');
}

export function verifyDependencyContracts(packageJson, packageLock) {
  const errors = [];

  for (const packageName of REACT_FAMILY) {
    const manifest = manifestVersion(packageJson, packageName);
    const lockRoot = lockRootVersion(packageLock, packageName);
    const installed = installedVersion(packageLock, packageName);

    if (!manifest) errors.push(`package.json does not declare ${packageName}.`);
    if (!lockRoot) errors.push(`package-lock.json root package does not declare ${packageName}.`);
    if (!installed) errors.push(`package-lock.json does not contain an installed ${packageName}.`);
    if (manifest && lockRoot && manifest !== lockRoot) {
      errors.push(
        `package-lock.json is stale for ${packageName}: package.json declares ${manifest}, `
        + `but the lockfile root declares ${lockRoot}.`,
      );
    }
  }

  const declared = REACT_FAMILY.map((name) => [name, manifestVersion(packageJson, name)]);
  if (declared.every(([, version]) => version)) {
    const majors = new Set(declared.map(([name, version]) => majorOf(version, `package.json ${name}`)));
    if (majors.size !== 1) {
      errors.push(
        'React runtime and type packages must be updated atomically to the same major version: '
        + describeVersions(declared),
      );
    }
  }

  const installed = REACT_FAMILY.map((name) => [name, installedVersion(packageLock, name)]);
  if (installed.every(([, version]) => version)) {
    const majors = new Set(installed.map(([name, version]) => majorOf(version, `installed ${name}`)));
    if (majors.size !== 1) {
      errors.push(
        'The locked React runtime and type packages use incompatible major versions: '
        + describeVersions(installed),
      );
    }
  }

  const reactDom = packageLock.packages?.['node_modules/react-dom'];
  const reactDomPeer = reactDom?.peerDependencies?.react;
  const lockedReact = installedVersion(packageLock, 'react');
  if (reactDomPeer && lockedReact
      && majorOf(reactDomPeer, 'react-dom peer dependency') !== majorOf(lockedReact, 'installed react')) {
    errors.push(
      `react-dom ${reactDom.version} requires react ${reactDomPeer}, `
      + `but package-lock.json installs react ${lockedReact}.`,
    );
  }

  const reactDomTypes = packageLock.packages?.['node_modules/@types/react-dom'];
  const reactTypesPeer = reactDomTypes?.peerDependencies?.['@types/react'];
  const lockedReactTypes = installedVersion(packageLock, '@types/react');
  if (reactTypesPeer && lockedReactTypes
      && majorOf(reactTypesPeer, '@types/react-dom peer dependency')
        !== majorOf(lockedReactTypes, 'installed @types/react')) {
    errors.push(
      `@types/react-dom ${reactDomTypes.version} requires @types/react ${reactTypesPeer}, `
      + `but package-lock.json installs @types/react ${lockedReactTypes}.`,
    );
  }

  return errors;
}

export function loadProjectFiles(projectDirectory) {
  return {
    packageJson: JSON.parse(readFileSync(resolve(projectDirectory, 'package.json'), 'utf8')),
    packageLock: JSON.parse(readFileSync(resolve(projectDirectory, 'package-lock.json'), 'utf8')),
  };
}

function run() {
  const scriptDirectory = dirname(fileURLToPath(import.meta.url));
  const projectDirectory = resolve(scriptDirectory, '..');
  const { packageJson, packageLock } = loadProjectFiles(projectDirectory);
  const errors = verifyDependencyContracts(packageJson, packageLock);

  if (errors.length > 0) {
    console.error('Frontend dependency contract verification failed:');
    for (const error of errors) console.error(`  - ${error}`);
    console.error('\nDo not bypass this with --legacy-peer-deps. Update coupled packages together.');
    process.exitCode = 1;
    return;
  }

  console.log('Frontend dependency contracts are consistent.');
}

if (process.argv[1] === fileURLToPath(import.meta.url)) run();
