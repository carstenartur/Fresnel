#!/usr/bin/env python3
"""Fail-closed recovery of the preserved Fresnel 0.1.8 publication.

The artifact commit is never replaced by the reviewed recovery control commit.
Only gh's successful cryptographic verification output is accepted as a receipt.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

METADATA = frozenset({'pom.xml', 'backend/pom.xml', 'measurement-core/pom.xml', 'optics-core/pom.xml', 'CITATION.cff'})
RECOVERY_PATHS = frozenset({'.github/workflows/deploy-release.yml', '.github/workflows/publish-release.yml',
                          '.github/scripts/release-publication-state.py', '.github/scripts/test-release-publication-state.py',
                          'docs/release-recovery.md'})
INCIDENT = {
    'repo': 'carstenartur/Fresnel', 'version': '0.1.8', 'release_id': 430398234,
    'publication_run_id': 41279261127, 'baseline': 'c3bca3ac5aa550e5af32cbc3c79aa54a567d1fab',
    'candidate': '08c9a93a4a7bb3437377019711af1b1f9cc70773',
    'candidate_branch': 'release/candidate-0.1.8-41279140538-1',
    'tag_object': '40f03ac0cc0b9cfe0bf6bd8cdbf9fe2daf9af94f',
    'jar_sha256': 'f9be8ad9c146f36c668f0cb229766512aa07b962d852178827196f99ffc8e2a7',
    'image_digest': 'sha256:f46673dddb83d0008d6f28c3aba899222016ba04bbede5b481def5005186e792',
    'image': 'ghcr.io/carstenartur/fresnel',
    'asset_ids': [697503326, 697503328],
}
SHA = re.compile(r'[0-9a-f]{40}\Z')
SEMVER = re.compile(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\Z')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def numeric_id(value):
    require(isinstance(value, str) and re.fullmatch(r'[1-9][0-9]*', value) is not None, 'IDs must be positive canonical decimal strings')
    return int(value)


def validate_resume_inputs(release_id: str, publication_run_id: str, candidate_sha: str):
    require(isinstance(candidate_sha, str) and SHA.fullmatch(candidate_sha) is not None, 'candidate must be a full lowercase SHA')
    return numeric_id(release_id), numeric_id(publication_run_id), candidate_sha


def resolve_unique_draft(releases: list[dict], expected_tag: str):
    require(SEMVER.fullmatch(expected_tag) is not None, 'tag must be X.Y.Z')
    matches = [r for r in releases if r.get('tag_name') == expected_tag and r.get('draft') is True and r.get('prerelease') is False]
    require(len(matches) == 1, 'expected exactly one non-prerelease draft with exact tag')
    identity = matches[0].get('id')
    require(type(identity) is int and identity > 0, 'draft ID must be a positive integer')
    return identity


def validate_release_binding(release, peeled_tag_commit, run, expected=INCIDENT):
    require(type(release.get('id')) is int and release['id'] == expected['release_id'], 'wrong release ID')
    require(release.get('url') == f"https://api.github.com/repos/{expected['repo']}/releases/{expected['release_id']}", 'wrong release repository')
    require(release.get('tag_name') == expected['version'] and release.get('prerelease') is False, 'wrong release tag or prerelease')
    require(type(release.get('draft')) is bool, 'missing release draft state')
    require(release['draft'] or bool(release.get('published_at')), 'unrelated unpublished state')
    require(peeled_tag_commit == expected['candidate'], 'tag peel differs from original candidate')
    for key, value in {'id': expected['publication_run_id'], 'head_sha': expected['candidate'],
                       'head_branch': expected['candidate_branch'], 'path': '.github/workflows/publish-release.yml',
                       'event': 'workflow_dispatch', 'run_attempt': 1, 'status': 'completed', 'conclusion': 'failure'}.items():
        require(type(run.get(key)) is type(value) and run[key] == value, f'original publication run {key} mismatch')
    require(run.get('repository', {}).get('full_name') == expected['repo'], 'wrong original run repository')


def git(repo, *args):
    return subprocess.check_output(['git', *args], cwd=repo, text=True, stderr=subprocess.PIPE).strip()


def validate_reviewed_ancestry(repo, candidate, reviewed_main, expected=INCIDENT):
    require(SHA.fullmatch(candidate) is not None and SHA.fullmatch(reviewed_main) is not None, 'full lowercase SHAs required')
    require(candidate == expected['candidate'], 'candidate differs from recorded source')
    require(git(repo, 'rev-list', '--parents', '-n', '1', candidate).split()[1:] == [expected['baseline']], 'original candidate parent mismatch')
    paths = set(git(repo, 'diff', '--name-only', expected['baseline'], candidate).splitlines())
    require(paths == METADATA, 'original candidate is not the five-file metadata change')
    # Restore the exact baseline version in XML/citation text: no other edits may hide in a metadata file.
    for path in METADATA:
        before = git(repo, 'show', f"{expected['baseline']}:{path}")
        after = git(repo, 'show', f'{candidate}:{path}')
        normalized = before.replace(expected['version'] + '-SNAPSHOT', expected['version'])
        if path == 'CITATION.cff' and not re.search(r'^date-released:', before, re.M):
            # The original canonical updater also recorded the incident release date.
            after = re.sub(r'\ndate-released: 2026-11-01$', '', after)
        require(normalized == after,
                f'candidate contains changes beyond version replacement in {path}')
        if path == 'CITATION.cff':
            require(re.search(r'^version:\s*[\"\']?' + re.escape(expected['version']) + r'[\"\']?\s*$', after, re.M) is not None, 'citation version mismatch')
        else:
            root = ET.fromstring(after)
            ns = {'m': 'http://maven.apache.org/POM/4.0.0'} if root.tag.startswith('{') else {}
            prefix = 'm:' if ns else ''
            version = root.find(prefix + 'version', ns)
            if version is None:
                version = root.find(prefix + 'parent/' + prefix + 'version', ns)
            require(version is not None and version.text == expected['version'], f'{path} version mismatch')
    ancestor = subprocess.run(['git', 'merge-base', '--is-ancestor', candidate, reviewed_main], cwd=repo, capture_output=True)
    require(ancestor.returncode == 0, 'original candidate must be an ancestor of reviewed main; use a normal merge')
    require(reviewed_main != candidate, 'reviewed recovery control commit must differ from artifact candidate')
    changed = set(git(repo, 'diff', '--name-only', candidate, reviewed_main).splitlines())
    require(bool(changed) and changed <= RECOVERY_PATHS, 'reviewed main includes non-recovery paths')


def validate_attestation_receipt(receipt, digest, expected):
    require(isinstance(receipt, list) and bool(receipt), 'no cryptographically verified attestation receipt')
    invocation = f"https://github.com/{expected['repo']}/actions/runs/{expected['publication_run_id']}/attempts/1"
    for item in receipt:
        statement = item.get('verificationResult', {}).get('statement', {})
        if (statement.get('predicateType') == 'https://slsa.dev/provenance/v1'
                and any(s.get('digest', {}).get('sha256') == digest for s in statement.get('subject', []))
                and statement.get('predicate', {}).get('runDetails', {}).get('metadata', {}).get('invocationId') == invocation):
            return
    raise ValueError('verified attestation subject or original publication invocation mismatch')


def validate_asset_receipt(assets, jar_bytes, checksum_bytes, jar_receipt, image_receipt, expected=INCIDENT):
    jar_name = f"backend-{expected['version']}.jar"
    required = [jar_name, jar_name + '.sha256']
    selected = []
    for name, data in zip(required, [jar_bytes, checksum_bytes]):
        matches = [a for a in assets if a.get('name') == name]
        require(len(matches) == 1, f'missing or duplicate asset {name}')
        asset = matches[0]
        require(type(asset.get('id')) is int and asset['id'] > 0 and asset.get('state') == 'uploaded', 'invalid asset ID/state')
        digest = hashlib.sha256(data).hexdigest()
        require(asset.get('digest') == 'sha256:' + digest and asset.get('size') == len(data), 'downloaded asset metadata/hash mismatch')
        selected.append(asset)
    digest = hashlib.sha256(jar_bytes).hexdigest()
    require(digest == expected['jar_sha256'], 'downloaded JAR differs from recorded SHA256')
    require(checksum_bytes == f'{digest}  {jar_name}\n'.encode(), 'checksum file does not bind the original JAR')
    validate_attestation_receipt(jar_receipt, digest, expected)
    validate_attestation_receipt(image_receipt, expected['image_digest'].split(':')[1], expected)
    return selected


def stable_publication_evidence(release, selected_assets):
    """Compare identity/content/state, excluding read counters and API timestamps.

    Actual downloaded bytes and cryptographic provenance are verified separately
    on every observation. Include embedded assets as well as the numeric asset
    endpoint results, without depending on collection ordering or telemetry.
    """
    asset_fields = ('id', 'url', 'name', 'state', 'size', 'digest', 'content_type')

    def assets_evidence(assets):
        return sorted(({key: asset.get(key) for key in asset_fields} for asset in assets),
                      key=lambda asset: str(asset['id']))

    release_fields = ('id', 'url', 'tag_name', 'draft', 'prerelease', 'name', 'body',
                      'target_commitish', 'immutable')
    stable_release = {key: release.get(key) for key in release_fields}
    # The timestamp's presence represents publication state; its clock value is incidental.
    stable_release['published'] = bool(release.get('published_at'))
    stable_release['assets'] = assets_evidence(release.get('assets', []))
    return {'release': stable_release, 'assets': assets_evidence(selected_assets)}


def plan_resume(is_draft, latest_matches):
    require(type(is_draft) is bool and type(latest_matches) is bool, 'explicit publication states required')
    require(is_draft or latest_matches, 'published release has unexpected latest digest; inspect before recovery')
    return ([] if latest_matches else ['promote_latest']) + (['publish_release'] if is_draft else [])


def validate_environment_policy(environment, policies, initiator):
    reviewers = [r for r in environment.get('protection_rules', []) if r.get('type') == 'required_reviewers']
    require(len(reviewers) == 1 and reviewers[0].get('prevent_self_review') is True
            and any(r.get('type') == 'Team' or (r.get('type') == 'User' and r.get('reviewer', {}).get('login') != initiator)
                    for r in reviewers[0].get('reviewers', [])),
            'release environment missing required reviewer other than initiator with prevent_self_review=true')
    require(environment.get('deployment_branch_policy') == {'protected_branches': False, 'custom_branch_policies': True}, 'release environment missing selected deployment ref restrictions')
    # GitHub policy matching uses fnmatch patterns, not regex; disallow broad additional policies.
    actual = {(p.get('type'), p.get('name')) for p in policies}
    require(actual == {('branch', 'release/candidate-*'), ('tag', '[0-9]*.[0-9]*.[0-9]*')}
            and len(policies) == 2, 'release deployment policies must contain only candidate branches and semantic-version tag pattern')


class Adapter:
    """Thin gh/docker adapter. Every write is behind full validation and a second observation."""
    def __init__(self, repo=Path('.'), expected=INCIDENT):
        self.repo = Path(repo)
        self.expected = expected

    def command(self, args, binary=False):
        return subprocess.run(args, cwd=self.repo, check=True, capture_output=True, text=not binary).stdout

    def api(self, suffix, *args):
        return json.loads(self.command(['gh', 'api', f"repos/{self.expected['repo']}/{suffix}", *args]))

    def ref(self, ref):
        return self.api('git/ref/' + ref)['object']

    def digest(self, tag, missing_ok=False):
        try:
            manifest = json.loads(self.command(['docker', 'buildx', 'imagetools', 'inspect', tag, '--format', '{{json .Manifest}}']))
            digest = manifest.get('digest')
            require(isinstance(digest, str) and re.fullmatch(r'sha256:[0-9a-f]{64}', digest) is not None, 'registry returned no immutable manifest digest')
            return digest
        except subprocess.CalledProcessError as error:
            # Accept only a manifest-resolution error naming the exact requested ref.
            # A token service/proxy 404, an unqualified message, or another image's
            # missing manifest does not establish absence of this destination.
            missing_manifest = re.fullmatch(
                r'(?:ERROR:\s*)?' + re.escape(tag) + r':\s*(?:manifest unknown|not found)',
                (error.stderr or '').strip(), re.I)
            if missing_ok and missing_manifest:
                return None
            raise

    def attestation(self, artifact):
        e = self.expected
        # The CLI enforces certificate claims, not user-controlled predicate fields.
        return json.loads(self.command(['gh', 'attestation', 'verify', artifact,
            '--repo', e['repo'], '--signer-workflow', e['repo'] + '/.github/workflows/publish-release.yml',
            '--cert-identity', f"https://github.com/{e['repo']}/.github/workflows/publish-release.yml@refs/heads/{e['candidate_branch']}",
            '--source-digest', e['candidate'], '--signer-digest', e['candidate'],
            '--source-ref', 'refs/heads/' + e['candidate_branch'], '--deny-self-hosted-runners', '--format', 'json']))

    def policy_blocker(self, initiator):
        try:
            environment = self.api('environments/release')
            branch_policy = environment.get('deployment_branch_policy')
            if isinstance(branch_policy, dict) and branch_policy.get('custom_branch_policies'):
                pages = self.api('environments/release/deployment-branch-policies?per_page=100', '--paginate', '--slurp')
                policies = [p for page in pages for p in page['branch_policies']]
            else:
                policies = []
            validate_environment_policy(environment, policies, initiator)
        except (ValueError, KeyError, subprocess.CalledProcessError) as error:
            return f'release environment policy could not be verified: {error}'
        return None

    def observe(self, reviewed_main, initiator, control_branch=None, event_sha=None):
        e = self.expected
        require(SHA.fullmatch(reviewed_main) is not None, 'reviewed main must be full lowercase SHA')
        require(self.ref('heads/main') == {'sha': reviewed_main, 'type': 'commit', 'url': f"https://api.github.com/repos/{e['repo']}/git/commits/{reviewed_main}"}, 'main lease drift')
        if control_branch is not None:
            require(re.fullmatch(r'release/candidate-recovery-0\.1\.8-[1-9][0-9]*-[1-9][0-9]*', control_branch) is not None, 'invalid recovery control ref')
            require(event_sha == reviewed_main and self.ref('heads/' + control_branch)['sha'] == reviewed_main, 'control SHA/ref differs from reviewed main')
        require(git(self.repo, 'rev-parse', 'HEAD') == reviewed_main, 'checked out control source differs from reviewed main')
        validate_reviewed_ancestry(self.repo, e['candidate'], reviewed_main, e)
        require(self.ref('heads/' + e['candidate_branch'])['sha'] == e['candidate'], 'original candidate branch moved')
        tag = self.ref('tags/' + e['version'])
        require(tag['type'] == 'tag' and tag['sha'] == e['tag_object'], 'original annotated tag object changed')
        tag_object = self.api('git/tags/' + e['tag_object'])
        require(tag_object.get('tag') == e['version'] and tag_object['object']['type'] == 'commit', 'original annotated tag binding changed')
        release = self.api(f"releases/{e['release_id']}")
        run = self.api(f"actions/runs/{e['publication_run_id']}")
        validate_release_binding(release, tag_object['object']['sha'], run, e)
        pages = self.api(f"releases/{e['release_id']}/assets?per_page=100", '--paginate', '--slurp')
        assets = [a for page in pages for a in page]
        originals = [a for name in [f"backend-{e['version']}.jar", f"backend-{e['version']}.jar.sha256"] for a in assets if a.get('name') == name]
        require([a['id'] for a in originals] == e['asset_ids'], 'preserved asset IDs missing, duplicated or replaced')
        if release['draft']:
            require(len(assets) == len(e['asset_ids']), 'draft asset set must contain exactly the two preserved originals')
        downloaded = [self.command(['gh', 'api', f"repos/{e['repo']}/releases/assets/{identity}", '-H', 'Accept: application/octet-stream'], binary=True) for identity in e['asset_ids']]
        require(self.digest(e['image'] + ':' + e['version']) == e['image_digest'], 'versioned image digest changed')
        with tempfile.TemporaryDirectory(prefix='fresnel-receipt-') as folder:
            jar_path = Path(folder) / f"backend-{e['version']}.jar"
            jar_path.write_bytes(downloaded[0])
            jar_receipt = self.attestation(str(jar_path))
        image_receipt = self.attestation('oci://' + e['image'] + '@' + e['image_digest'])
        selected = validate_asset_receipt(assets, *downloaded, jar_receipt, image_receipt, e)
        latest_digest = self.digest(e['image'] + ':latest', missing_ok=True)
        return {**stable_publication_evidence(release, selected),
                'latest_digest': latest_digest, 'latest_matches': latest_digest == e['image_digest'],
                'policy_blocker': self.policy_blocker(initiator)}

    def resume(self, reviewed_main, initiator, dry_run=False, control_branch=None, event_sha=None):
        before = self.observe(reviewed_main, initiator, control_branch, event_sha)
        effects = plan_resume(before['release']['draft'], before['latest_matches'])
        result = {'artifact_sha': self.expected['candidate'], 'control_sha': reviewed_main, 'latest_digest': before['latest_digest'], 'effects': effects, 'policy_blocker': before['policy_blocker']}
        if dry_run:
            return result
        require(control_branch is not None, 'publication requires a reviewed recovery control ref')
        require(before['policy_blocker'] is None, before['policy_blocker'])
        current = self.observe(reviewed_main, initiator, control_branch, event_sha)
        require(current == before, 'publication state changed before first write')
        if 'promote_latest' in effects:
            self.command(['docker', 'buildx', 'imagetools', 'create', '--tag', self.expected['image'] + ':latest', self.expected['image'] + '@' + self.expected['image_digest']])
            current = self.observe(reviewed_main, initiator, control_branch, event_sha)
            require(current == dict(before, latest_digest=self.expected['image_digest'], latest_matches=True), 'state drift after immutable latest promotion; core release remains staged')
        if 'publish_release' in effects:
            self.api(f"releases/{self.expected['release_id']}", '--method', 'PATCH', '-F', 'draft=false')
        after = self.observe(reviewed_main, initiator, control_branch, event_sha)
        require(not after['release']['draft'] and after['latest_digest'] == self.expected['image_digest'] and after['policy_blocker'] is None, 'core publication not confirmed')
        result['core_published'] = True
        return result

    def dispatch_and_wait(self, workflow, ref, inputs, title):
        # Deterministic title enables retries to reuse a run instead of duplicate dispatches.
        def matching():
            pages = self.api(f'actions/workflows/{workflow}/runs?event=workflow_dispatch&per_page=100', '--paginate', '--slurp')
            runs = [r for page in pages for r in page['workflow_runs']]
            matches = [r for r in runs if r['display_title'] == title]
            require(len(matches) <= 1, 'ambiguous matching workflow dispatch')
            return matches[0] if matches else None
        run = matching()
        if run is None:
            args = ['gh', 'workflow', 'run', workflow, '--repo', self.expected['repo'], '--ref', ref]
            for key, value in inputs.items():
                args.extend(['-f', f'{key}={value}'])
            self.command(args)
            for _ in range(60):
                run = matching()
                if run:
                    break
                time.sleep(2)
        require(run is not None, 'dispatched workflow not visible; retry will search deterministic request title')
        expected_head = self.expected['candidate'] if workflow == 'release-package.yml' else git(self.repo, 'rev-parse', 'HEAD')
        require(run['head_sha'] == expected_head, 'dispatched workflow head differs from intended source')
        require(type(run['id']) is int and run['id'] > 0, 'invalid dispatched run ID')
        if run['status'] == 'completed' and run['conclusion'] != 'success':
            raise ValueError(f"Workflow run {run['id']} is {run['status']} with conclusion {run['conclusion']}: "
                             f"https://github.com/{self.expected['repo']}/actions/runs/{run['id']}. "
                             'Separate diagnosis and explicit authorization for normal GitHub retry/admission are required; '
                             'this helper does not retry or admit unsuccessful runs.')
        self.command(['gh', 'run', 'watch', str(run['id']), '--repo', self.expected['repo'], '--exit-status'])
        return run['id']

    def main_lease(self, reviewed_main):
        require(self.ref('heads/main')['sha'] == reviewed_main, 'main lease drift during housekeeping')

    def optional_ref(self, ref):
        try:
            return self.ref(ref)
        except subprocess.CalledProcessError as error:
            require('404' in (error.stderr or ''), 'could not inspect optional ref')
            return None

    def update_maintenance(self, reviewed_main):
        branch = 'maintenance/0.1.x'
        current = self.optional_ref('heads/' + branch)
        if current and current['sha'] == reviewed_main:
            return
        if current:
            self.command(['git', 'fetch', 'origin', 'refs/heads/' + branch])
            ancestor = subprocess.run(['git', 'merge-base', '--is-ancestor', current['sha'], reviewed_main], cwd=self.repo, capture_output=True)
            require(ancestor.returncode == 0, 'maintenance branch cannot fast-forward to reviewed main')
            self.main_lease(reviewed_main)
            require(self.ref('heads/' + branch)['sha'] == current['sha'], 'maintenance branch moved')
            self.api('git/refs/heads/' + branch, '--method', 'PATCH', '-f', 'sha=' + reviewed_main, '-F', 'force=false')
        else:
            self.main_lease(reviewed_main)
            self.api('git/refs', '--method', 'POST', '-f', 'ref=refs/heads/' + branch, '-f', 'sha=' + reviewed_main)

    def validate_next_iteration(self, reviewed_main, head, next_snapshot):
        require(git(self.repo, 'rev-list', '--parents', '-n', '1', head).split()[1:] == [reviewed_main], 'existing next-development branch must start at reviewed main')
        changed = set(git(self.repo, 'diff', '--name-only', reviewed_main, head).splitlines())
        require(changed == METADATA | {'release.properties'}, 'next-development branch has unexpected changes')
        for path in METADATA:
            source = git(self.repo, 'show', f'{reviewed_main}:{path}')
            target = git(self.repo, 'show', f'{head}:{path}')
            expected = source.replace(self.expected['version'], next_snapshot)
            if path == 'CITATION.cff':
                # Match the existing citation updater: quoted version, removed release date.
                expected = re.sub(r'^version:.*$', 'version: "' + next_snapshot + '"', expected, flags=re.M)
                expected = re.sub(r'^date-released:.*\n?', '', expected, flags=re.M).rstrip()
                require(re.search(r'^version:\s*[\"\']?' + re.escape(next_snapshot) + r'[\"\']?\s*$', target, re.M) is not None, 'existing next-development CITATION.cff version mismatch')
                # Valid YAML scalars may be quoted or unquoted.
                target = re.sub(r'^version:.*$', 'version: "' + next_snapshot + '"', target, flags=re.M)
            require(target == expected, f'existing next-development branch has unexpected edits in {path}')
        props = git(self.repo, 'show', f'{head}:release.properties')
        source_props = git(self.repo, 'show', f'{reviewed_main}:release.properties')
        expected_props = re.sub(r'^next.release.version=.*$', 'next.release.version=' + next_snapshot.removesuffix('-SNAPSHOT'), source_props, flags=re.M)
        require(props == expected_props, 'next release property contains unexpected changes')

    def prepare_next_iteration(self, reviewed_main, next_snapshot):
        branch = 'release/prepare-next-' + next_snapshot
        prs = json.loads(self.command(['gh', 'pr', 'list', '--repo', self.expected['repo'], '--head', branch,
            '--base', 'main', '--state', 'all', '--json', 'number,state,headRefOid,baseRefName']))
        require(len(prs) <= 1, 'ambiguous existing next-development PR')
        current = self.optional_ref('heads/' + branch)
        if current:
            # Fetch is read-only with respect to the remote; no force pushes on retry.
            try:
                git(self.repo, 'cat-file', '-e', current['sha'] + '^{commit}')
            except subprocess.CalledProcessError:
                self.command(['git', 'fetch', 'origin', 'refs/heads/' + branch])
            self.validate_next_iteration(reviewed_main, current['sha'], next_snapshot)
            if prs:
                require(prs[0]['state'] in ('OPEN', 'MERGED') and prs[0]['headRefOid'] == current['sha'], 'existing next-development PR does not match the validated branch')
                return prs[0]['number']
        else:
            require(not prs, 'next-development PR exists but its branch is missing; inspect manually')
            self.main_lease(reviewed_main)
            require(git(self.repo, 'status', '--porcelain') == '', 'next-development preparation requires clean checkout')
            self.command(['git', 'checkout', '-b', branch, reviewed_main])
            self.command(['mvn', 'versions:set', '-DnewVersion=' + next_snapshot, '-DgenerateBackupPoms=false'])
            self.command(['python3', '.github/scripts/update-citation.py', '--version', next_snapshot, '--snapshot'])
            path = self.repo / 'release.properties'
            text = path.read_text()
            require(re.search(r'^next.release.version=', text, re.M) is not None, 'missing next.release.version')
            path.write_text(re.sub(r'^next.release.version=.*$', 'next.release.version=' + next_snapshot.removesuffix('-SNAPSHOT'), text, flags=re.M))
            self.command(['git', 'add', *sorted(METADATA), 'release.properties'])
            self.command(['git', 'commit', '-m', 'Prepare for next development iteration ' + next_snapshot])
            self.validate_next_iteration(reviewed_main, git(self.repo, 'rev-parse', 'HEAD'), next_snapshot)
            self.main_lease(reviewed_main)
            self.command(['git', 'push', 'origin', branch])
        self.main_lease(reviewed_main)
        with tempfile.TemporaryDirectory(prefix='fresnel-next-pr-') as folder:
            body = Path(folder) / 'body.md'
            body.write_text(f'Prepare Maven and citation metadata for {next_snapshot} after preserved release 0.1.8. The branch starts from reviewed main and retains the recovery changes.\n')
            return self.command(['gh', 'pr', 'create', '--repo', self.expected['repo'], '--base', 'main', '--head', branch,
                '--title', 'Prepare for next development iteration ' + next_snapshot, '--body-file', str(body)]).strip()

    def housekeeping(self, reviewed_main, next_snapshot):
        # Core publication is checked by the CLI before this post-publication method.
        self.main_lease(reviewed_main)
        errors = []
        result = {}
        try:
            self.update_maintenance(reviewed_main)
        except (ValueError, subprocess.CalledProcessError) as error:
            errors.append('maintenance: ' + str(error))
        try:
            result['next_pr'] = self.prepare_next_iteration(reviewed_main, next_snapshot)
        except (ValueError, subprocess.CalledProcessError) as error:
            errors.append('next-development PR: ' + str(error))
        require(not errors, 'Core publication remains complete; housekeeping failed: ' + '; '.join(errors))
        return result

    def orchestrate(self, reviewed_main, initiator, request_id, next_snapshot, dry_run=False):
        require(re.fullmatch(r'[1-9][0-9]*-[1-9][0-9]*', request_id) is not None, 'invalid orchestration request ID')
        before = self.observe(reviewed_main, initiator)
        result = {'artifact_sha': self.expected['candidate'], 'control_sha': reviewed_main,
                  'latest_digest': before['latest_digest'], 'effects': plan_resume(before['release']['draft'], before['latest_matches']), 'policy_blocker': before['policy_blocker']}
        if dry_run:
            return result
        require(before['policy_blocker'] is None, before['policy_blocker'])
        # Revalidate all artifacts/refs/policy immediately before creating the sole control ref.
        require(self.observe(reviewed_main, initiator) == before, 'state changed before control dispatch')
        branch = f"release/candidate-recovery-{self.expected['version']}-{request_id}"
        self.api('git/refs', '--method', 'POST', '-f', 'ref=refs/heads/' + branch, '-f', 'sha=' + reviewed_main)
        result['publication_run_id'] = self.dispatch_and_wait('publish-release.yml', branch, {
            'release_version': self.expected['version'], 'expected_main_sha': reviewed_main,
            'candidate_branch': branch, 'next_snapshot': next_snapshot, 'maintenance_branch': 'maintenance/0.1.x',
            'request_id': request_id, 'resume_release_id': str(self.expected['release_id']),
            'resume_publication_run_id': str(self.expected['publication_run_id']), 'expected_candidate_sha': self.expected['candidate'],
        }, f"Publish Release {self.expected['version']} {request_id}")
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['resolve-draft', 'preflight', 'orchestrate', 'resume', 'packages', 'housekeeping'])
    parser.add_argument('--release-version', default='0.1.8')
    parser.add_argument('--release-id', default='')
    parser.add_argument('--publication-run-id', default='')
    parser.add_argument('--candidate-sha', default='')
    parser.add_argument('--reviewed-main', default='')
    parser.add_argument('--control-branch')
    parser.add_argument('--event-sha')
    parser.add_argument('--request-id', default='')
    parser.add_argument('--next-snapshot', default='0.1.9-SNAPSHOT')
    parser.add_argument('--initiator', default=os.environ.get('GITHUB_ACTOR', ''))
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    try:
        adapter = Adapter()
        if args.mode == 'resolve-draft':
            pages = adapter.api('releases?per_page=100', '--paginate', '--slurp')
            print(resolve_unique_draft([r for page in pages for r in page], args.release_version))
            return 0
        ids = validate_resume_inputs(args.release_id, args.publication_run_id, args.candidate_sha)
        require(ids == (INCIDENT['release_id'], INCIDENT['publication_run_id'], INCIDENT['candidate']), 'resume inputs do not match recorded incident')
        require(args.release_version == INCIDENT['version'], 'resume version differs from original artifact')
        require(bool(args.initiator), 'workflow initiator required')
        # Reuse the existing planner for all downstream housekeeping inputs.
        spec = importlib.util.spec_from_file_location('version_plan', Path(__file__).with_name('release-version-plan.py'))
        planner = importlib.util.module_from_spec(spec); spec.loader.exec_module(planner)
        planner.resolve_plan(INCIDENT['version'], args.next_snapshot)
        if args.mode == 'orchestrate':
            require(os.environ.get('GITHUB_REF') == 'refs/heads/main' and os.environ.get('GITHUB_SHA') == args.reviewed_main, 'orchestrator must use current main dispatch')
            result = adapter.orchestrate(args.reviewed_main, args.initiator, args.request_id, args.next_snapshot, args.dry_run)
        elif args.mode in ('packages', 'housekeeping'):
            require(not args.dry_run, 'packages are a post-publication effect, unavailable in dry-run')
            state = adapter.observe(args.reviewed_main, args.initiator, args.control_branch, args.event_sha)
            require(not state['release']['draft'] and state['latest_matches'] and state['policy_blocker'] is None, 'core publication or environment not confirmed before packages')
            if args.mode == 'packages':
                request = 'recovery-0.1.8-41279261127'
                result = {'package_run_id': adapter.dispatch_and_wait('release-package.yml', '0.1.8', {'release_tag': '0.1.8', 'request_id': request}, f'Release Packages 0.1.8 {request}')}
            else:
                result = adapter.housekeeping(args.reviewed_main, args.next_snapshot)
        else:
            require(args.mode != 'resume' or (args.control_branch and os.environ.get('GITHUB_REF') == 'refs/heads/' + args.control_branch), 'resume requires exact control event ref')
            result = adapter.resume(args.reviewed_main, args.initiator, args.dry_run or args.mode == 'preflight', args.control_branch, args.event_sha)
        print(json.dumps(result, sort_keys=True))
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
                summary.write('\n```json\n' + json.dumps(result, indent=2) + '\n```\n')
        return 0
    except (ValueError, KeyError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print(f'::error::{error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
