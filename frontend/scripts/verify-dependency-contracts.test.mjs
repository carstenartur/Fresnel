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

test('accepts the checked-in dependency graph', () => {
  assert.deepEqual(
    verifyDependencyContracts(current.packageJson, current.packageLock),
    [],
  );
});

test('rejects a split React and React DOM major upgrade', () => {
  const candidate = fixture();
  candidate.packageJson.dependencies['react-dom'] = '^19.2.8';
  candidate.packageLock.packages[''].dependencies['react-dom'] = '^19.2.8';
  candidate.packageLock.packages['node_modules/react-dom'].version = '19.2.8';
  candidate.packageLock.packages['node_modules/react-dom'].peerDependencies.react = '^19.2.8';

  const errors = verifyDependencyContracts(candidate.packageJson, candidate.packageLock);

  assert.ok(errors.some((error) => error.includes('must be updated atomically')));
  assert.ok(errors.some((error) => error.includes('requires react')));
});

test('rejects mismatched React type package majors', () => {
  const candidate = fixture();
  candidate.packageJson.devDependencies['@types/react-dom'] = '^19.2.3';
  candidate.packageLock.packages[''].devDependencies['@types/react-dom'] = '^19.2.3';
  candidate.packageLock.packages['node_modules/@types/react-dom'].version = '19.2.3';
  candidate.packageLock.packages['node_modules/@types/react-dom'].peerDependencies['@types/react'] = '^19.2.0';

  const errors = verifyDependencyContracts(candidate.packageJson, candidate.packageLock);

  assert.ok(errors.some((error) => error.includes('must be updated atomically')));
  assert.ok(errors.some((error) => error.includes('requires @types/react')));
});

test('rejects a stale lockfile root declaration', () => {
  const candidate = fixture();
  candidate.packageJson.dependencies.react = '^19.2.8';

  const errors = verifyDependencyContracts(candidate.packageJson, candidate.packageLock);

  assert.ok(errors.some((error) => error.includes('package-lock.json is stale for react')));
});
