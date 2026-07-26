import assert from 'node:assert/strict';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  loadProjectFiles,
  verifyDependencyContracts,
} from './verify-dependency-contracts.mjs';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const projectDirectory = resolve(scriptDirectory, '..');
const current = loadProjectFiles(projectDirectory);

function fixture() {
  return structuredClone(current);
}

function nextMajor(version) {
  const match = String(version).match(/\d+/);
  assert.ok(match, `Expected a semantic version, got ${version}`);
  return Number(match[0]) + 1;
}

function incompatibleVersions(currentVersion) {
  const major = nextMajor(currentVersion);
  return {
    range: `^${major}.0.0`,
    exact: `${major}.0.0`,
  };
}

test('accepts the checked-in dependency graph', () => {
  assert.deepEqual(
    verifyDependencyContracts(current.packageJson, current.packageLock),
    [],
  );
});

test('rejects split React and React DOM major versions', () => {
  const candidate = fixture();
  const incompatible = incompatibleVersions(candidate.packageJson.dependencies.react);
  candidate.packageJson.dependencies['react-dom'] = incompatible.range;
  candidate.packageLock.packages[''].dependencies['react-dom'] = incompatible.range;
  candidate.packageLock.packages['node_modules/react-dom'].version = incompatible.exact;
  candidate.packageLock.packages['node_modules/react-dom'].peerDependencies.react = incompatible.range;

  const errors = verifyDependencyContracts(candidate.packageJson, candidate.packageLock);

  assert.ok(errors.some((error) => error.includes('must be updated atomically')));
  assert.ok(errors.some((error) => error.includes('requires react')));
});

test('rejects mismatched React type package majors', () => {
  const candidate = fixture();
  const incompatible = incompatibleVersions(candidate.packageJson.devDependencies['@types/react']);
  candidate.packageJson.devDependencies['@types/react-dom'] = incompatible.range;
  candidate.packageLock.packages[''].devDependencies['@types/react-dom'] = incompatible.range;
  candidate.packageLock.packages['node_modules/@types/react-dom'].version = incompatible.exact;
  candidate.packageLock.packages['node_modules/@types/react-dom'].peerDependencies['@types/react'] = incompatible.range;

  const errors = verifyDependencyContracts(candidate.packageJson, candidate.packageLock);

  assert.ok(errors.some((error) => error.includes('must be updated atomically')));
  assert.ok(errors.some((error) => error.includes('requires @types/react')));
});

test('rejects a stale lockfile root declaration', () => {
  const candidate = fixture();
  candidate.packageJson.dependencies.react = incompatibleVersions(
    candidate.packageJson.dependencies.react,
  ).range;

  const errors = verifyDependencyContracts(candidate.packageJson, candidate.packageLock);

  assert.ok(errors.some((error) => error.includes('package-lock.json is stale for react')));
});
