#!/usr/bin/env python3
"""Real Git histories, downloaded bytes and subprocess-boundary release regressions."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name('release-publication-state.py')
M = None
if SCRIPT.exists():
    spec = importlib.util.spec_from_file_location('publication_state', SCRIPT)
    M = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(M)


class PublicationStateTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(M, 'preserved-draft recovery helper is missing')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'user.name', 'Recovery test')
        for name in M.METADATA:
            p = self.repo / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text('version: 0.1.8-SNAPSHOT\n' if name == 'CITATION.cff' else '<project><version>0.1.8-SNAPSHOT</version></project>\n')
        updater=self.repo/'.github/scripts/update-citation.py'
        updater.parent.mkdir(parents=True,exist_ok=True)
        updater.write_text(Path(__file__).with_name('update-citation.py').read_text())
        (self.repo/'release.properties').write_text('next.release.version=0.1.8\n')
        self.commit('baseline')
        self.base = self.git('rev-parse', 'HEAD')
        for name in M.METADATA:
            p = self.repo / name
            p.write_text(p.read_text().replace('0.1.8-SNAPSHOT', '0.1.8'))
        self.commit('candidate')
        self.candidate = self.git('rev-parse', 'HEAD')
        self.git('branch', 'release/candidate-0.1.8-41279140538-1')
        self.git('tag', '-a', '0.1.8', '-m', 'release')
        self.git('checkout', '-qb', 'repair')
        p = self.repo / 'docs/release-recovery.md'
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text('reviewed recovery\n')
        self.commit('repair')
        self.git('checkout', '-q', 'main')
        self.git('reset', '--hard', self.base)
        self.git('merge', '--no-ff', 'repair', '-m', 'reviewed repair')
        self.main = self.git('rev-parse', 'HEAD')
        self.data = b'real artifact bytes\n'
        self.jar_hash = hashlib.sha256(self.data).hexdigest()
        self.checksum = f'{self.jar_hash}  backend-0.1.8.jar\n'.encode()
        self.expected = dict(M.INCIDENT, baseline=self.base, candidate=self.candidate,
                             tag_object=self.git('rev-parse', '0.1.8'), jar_sha256=self.jar_hash)
        self.assets = [dict(id=1, name='backend-0.1.8.jar', state='uploaded', size=len(self.data), digest='sha256:'+self.jar_hash),
                       dict(id=2, name='backend-0.1.8.jar.sha256', state='uploaded', size=len(self.checksum), digest='sha256:'+hashlib.sha256(self.checksum).hexdigest())]
        self.release = dict(id=430398234, tag_name='0.1.8', draft=True, prerelease=False,
                            url='https://api.github.com/repos/carstenartur/Fresnel/releases/430398234', assets=self.assets, published_at=None)
        self.run = dict(id=41279261127, head_sha=self.candidate, head_branch=self.expected['candidate_branch'],
                        path='.github/workflows/publish-release.yml', event='workflow_dispatch', run_attempt=1,
                        repository={'full_name':'carstenartur/Fresnel'}, status='completed', conclusion='failure')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, text=True, stderr=subprocess.DEVNULL).strip()

    def commit(self, message):
        self.git('add', '.')
        self.git('commit', '-qm', message)

    def receipt(self, digest):
        return [{'verificationResult': {'statement': {
            'predicateType': 'https://slsa.dev/provenance/v1',
            'subject': [{'digest': {'sha256': digest}}],
            'predicate': {'runDetails': {'metadata': {'invocationId': 'https://github.com/carstenartur/Fresnel/actions/runs/41279261127/attempts/1'}}}}}}]

    def binding(self):
        return M.validate_release_binding(self.release, self.candidate, self.run, self.expected)

    def test_numeric_inputs_are_canonical_and_strict(self):
        self.assertEqual((430398234, 41279261127, self.candidate), M.validate_resume_inputs('430398234','41279261127',self.candidate))
        for value in ('0','-1',' 1','1.0','1; id','01',True):
            with self.subTest(value=value), self.assertRaises(ValueError):
                M.validate_resume_inputs(value,'41279261127',self.candidate)
        with self.assertRaises(ValueError):
            M.validate_resume_inputs('1','2',self.candidate.upper())

    def test_exact_draft_resolves_without_tag_endpoint(self):
        self.assertEqual(430398234, M.resolve_unique_draft([self.release], '0.1.8'))

    def test_draft_absence_ambiguity_wrong_state_and_prerelease(self):
        for releases in ([], [self.release,self.release], [dict(self.release,tag_name='0.1.9')],
                         [dict(self.release,draft=False)], [dict(self.release,prerelease=True)]):
            with self.subTest(releases=releases), self.assertRaises(ValueError):
                M.resolve_unique_draft(releases, '0.1.8')

    def test_original_binding_accepts_exact_numeric_draft_and_published_retry(self):
        self.binding()
        self.release.update(draft=False, published_at='2026-11-02T00:00:00Z')
        self.binding()

    def test_release_run_tag_repository_mismatch_fails(self):
        for target,key,value in [(self.release,'id',7),(self.release,'tag_name','0.1.9'),(self.release,'prerelease',True),
                                  (self.run,'head_sha','a'*40),(self.run,'path','.github/workflows/ci.yml'),
                                  (self.run,'repository',{'full_name':'other/repo'}),(self.run,'id',1)]:
            old=target[key]; target[key]=value
            with self.subTest(key=key), self.assertRaises(ValueError): self.binding()
            target[key]=old
        with self.assertRaises(ValueError): M.validate_release_binding(self.release,'b'*40,self.run,self.expected)

    def test_real_merge_preserves_original_candidate_and_allowed_repair(self):
        M.validate_reviewed_ancestry(self.repo,self.candidate,self.main,self.expected)
        self.assertEqual(2,len(self.git('rev-list','--parents','-n','1',self.main).split())-1)

    def test_real_squash_without_candidate_ancestry_fails(self):
        self.git('checkout','-q','--detach',self.base)
        self.git('merge','--squash','repair'); self.commit('squashed')
        with self.assertRaisesRegex(ValueError,'ancestor'):
            M.validate_reviewed_ancestry(self.repo,self.candidate,self.git('rev-parse','HEAD'),self.expected)

    def test_original_candidate_allows_only_recorded_release_date(self):
        self.git('checkout','-q','--detach',self.candidate)
        with (self.repo/'CITATION.cff').open('a') as output:
            output.write('date-released: 2026-11-01\n')
        self.git('commit','-qam','recorded release date')
        dated=self.git('rev-parse','HEAD')
        # Rebuild the candidate as a direct metadata-only child, as the real run did.
        self.git('reset','--soft',self.base)
        self.git('commit','-qm','release with date')
        dated=self.git('rev-parse','HEAD')
        expected=dict(self.expected,candidate=dated)
        self.git('checkout','-qb','dated-repair')
        (self.repo/'docs').mkdir(exist_ok=True)
        (self.repo/'docs/release-recovery.md').write_text('recovery')
        self.commit('dated repair')
        M.validate_reviewed_ancestry(self.repo,dated,self.git('rev-parse','HEAD'),expected)

    def test_runtime_and_pom_drift_after_candidate_fail(self):
        for name in ('runtime.py','pom.xml'):
            p=self.repo/name; p.write_text('changed'); self.commit('unreviewed drift')
            with self.subTest(name=name), self.assertRaises(ValueError):
                M.validate_reviewed_ancestry(self.repo,self.candidate,self.git('rev-parse','HEAD'),self.expected)
            self.git('reset','--hard',self.main)

    def test_wrong_parent_or_runtime_candidate_fails(self):
        with self.assertRaises(ValueError):
            M.validate_reviewed_ancestry(self.repo,self.candidate,self.main,dict(self.expected,baseline='a'*40))
        self.git('checkout','-q','--detach',self.candidate)
        (self.repo/'runtime.py').write_text('changed'); self.commit('bad candidate')
        with self.assertRaises(ValueError):
            M.validate_reviewed_ancestry(self.repo,self.git('rev-parse','HEAD'),self.main,self.expected)

    def verify_asset(self):
        return M.validate_asset_receipt(self.assets,self.data,self.checksum,self.receipt(self.jar_hash),self.receipt(self.expected['image_digest'].split(':')[1]),self.expected)

    def test_downloaded_bytes_checksum_and_verified_receipts(self): self.verify_asset()

    def test_missing_duplicate_wrong_asset_digest_fail(self):
        original=copy.deepcopy(self.assets)
        for assets in ([], self.assets+[self.assets[0]], [dict(self.assets[0],digest='sha256:'+'a'*64),self.assets[1]]):
            self.assets=assets
            with self.assertRaises(ValueError): self.verify_asset()
        self.assets=original

    def test_changed_jar_or_checksum_fails(self):
        for key in ('data','checksum'):
            old=getattr(self,key); setattr(self,key,old+b'bad')
            with self.subTest(key=key), self.assertRaises(ValueError): self.verify_asset()
            setattr(self,key,old)

    def test_bad_attestation_subject_or_original_run_fails(self):
        for receipt in ([], self.receipt('a'*64), [{'verificationResult':{'statement':{}}}]):
            with self.assertRaises(ValueError):
                M.validate_asset_receipt(self.assets,self.data,self.checksum,receipt,self.receipt(self.expected['image_digest'].split(':')[1]),self.expected)
        wrong=self.receipt(self.jar_hash)
        wrong[0]['verificationResult']['statement']['predicate']['runDetails']['metadata']['invocationId']+='0'
        with self.assertRaises(ValueError):
            M.validate_asset_receipt(self.assets,self.data,self.checksum,wrong,self.receipt(self.expected['image_digest'].split(':')[1]),self.expected)

    def test_plan_is_idempotent_and_partial_latest_state_skips_promotion(self):
        self.assertEqual(['promote_latest','publish_release'], M.plan_resume(True,False))
        self.assertEqual(['publish_release'], M.plan_resume(True,True))
        self.assertEqual([], M.plan_resume(False,True))
        with self.assertRaises(ValueError): M.plan_resume(False,False)

    def test_environment_current_observation_fails_closed(self):
        with self.assertRaisesRegex(ValueError,'required reviewer'):
            M.validate_environment_policy({'protection_rules':[],'deployment_branch_policy':None},[], 'initiator')

    def policy(self):
        return {'protection_rules':[{'type':'required_reviewers','prevent_self_review':True,'reviewers':[{'type':'User','reviewer':{'login':'reviewer'}}]}],
                'deployment_branch_policy':{'protected_branches':False,'custom_branch_policies':True}}

    def test_environment_requires_exact_selected_refs_and_other_reviewer(self):
        refs=[{'name':'release/candidate-*','type':'branch'},{'name':'[0-9]*.[0-9]*.[0-9]*','type':'tag'}]
        M.validate_environment_policy(self.policy(),refs,'initiator')
        for policies in ([],refs+[{'name':'*','type':'branch'}]):
            with self.assertRaises(ValueError): M.validate_environment_policy(self.policy(),policies,'initiator')
        with self.assertRaises(ValueError): M.validate_environment_policy(self.policy(),refs,'reviewer')


class AdapterBoundaryTest(PublicationStateTest):
    def setUp(self):
        super().setUp()
        self.adapter=M.Adapter(self.repo,self.expected)
        self.expected['asset_ids']=[1,2]
        self.ledger=[]
        self.latest=False
        self.policy_ok=True
        self.main_drift=False
        self.reads=0
        self.workflow_runs=[]
        self.control_created=False
        self.next_head=None
        self.crypto_failure=False
        self.image_drift=False
        self.maintenance_sha=self.main
        self.telemetry_churn=False
        self.downloads=0
        self.late_content_drift=False
        self.latest_sequence=[]
        self.latest_lookup_error=None
        self.latest_malformed=False
        self.real_run=subprocess.run
        self.boundary=patch.object(M.subprocess,'run',side_effect=self.external)
        self.boundary.start(); self.addCleanup(self.boundary.stop)

    def external(self,args,**kwargs):
        if args[0]=='git' and args[1]=='push':
            self.ledger.append(args)
            self.next_head=self.git('rev-parse','HEAD')
            return subprocess.CompletedProcess(args,0,stdout='',stderr='')
        if args[0]=='mvn':
            self.ledger.append(args)
            version=next(a.split('=',1)[1] for a in args if a.startswith('-DnewVersion='))
            for name in M.METADATA-{'CITATION.cff'}:
                p=self.repo/name; p.write_text(p.read_text().replace('0.1.8',version))
            return subprocess.CompletedProcess(args,0,stdout='',stderr='')
        if args[0] not in ('gh','docker'):
            return self.real_run(args,**kwargs)
        self.ledger.append(args)
        if args[0]=='docker':
            if 'create' in args:
                self.latest=True; output=''
            else:
                if args[4].endswith(':latest'):
                    if self.latest_lookup_error:
                        raise subprocess.CalledProcessError(1,args,stderr=self.latest_lookup_error)
                    if self.latest_malformed:
                        return subprocess.CompletedProcess(args,0,stdout='not JSON',stderr='')
                    if self.latest_sequence:
                        observed=self.latest_sequence.pop(0)
                        if observed is None:
                            raise subprocess.CalledProcessError(1,args,stderr='ERROR: '+args[4]+': manifest unknown')
                        return subprocess.CompletedProcess(args,0,stdout=json.dumps({'digest':observed}),stderr='')
                output=json.dumps({'digest':self.expected['image_digest'] if not self.image_drift and (self.latest or ':latest' not in args[4]) else 'sha256:'+'a'*64})
        elif args[1:3]==['attestation','verify']:
            if self.crypto_failure: raise subprocess.CalledProcessError(1,args,stderr='certificate source digest mismatch')
            self.assertIn('--source-digest',args)
            self.assertEqual(self.candidate,args[args.index('--source-digest')+1])
            self.assertEqual(self.candidate,args[args.index('--signer-digest')+1])
            digest=self.expected['image_digest'].split(':')[1] if args[3].startswith('oci://') else self.jar_hash
            output=json.dumps(self.receipt(digest))
        elif args[1:3]==['run','list']:
            output=json.dumps(self.workflow_runs)
        elif args[1:3]==['workflow','run']:
            ref=args[args.index('--ref')+1]
            fields=dict(a.split('=',1) for i,a in enumerate(args) if i>0 and args[i-1]=='-f')
            title=('Release Packages '+fields['release_tag']+' '+fields['request_id'] if args[3]=='release-package.yml' else 'Publish Release '+fields['release_version']+' '+fields['request_id'])
            self.workflow_runs.append({'databaseId':999,'displayTitle':title,'headSha':self.candidate if ref=='0.1.8' else self.main,'status':'completed','conclusion':'success'})
            output=''
        elif args[1:3]==['run','rerun']:
            self.workflow_runs[0].update(status='completed',conclusion='success')
            output=''
        elif args[1:3]==['run','watch']:
            if self.workflow_runs[0]['conclusion']=='failure':
                raise subprocess.CalledProcessError(1,args,stderr='failed workflow')
            output=''
        elif args[1:3]==['pr','list']:
            output=json.dumps([{'number':42,'state':'OPEN','headRefOid':self.next_head,'baseRefName':'main'}] if self.next_head else [])
        elif args[1:3]==['pr','create']:
            self.assertTrue(Path(args[args.index('--body-file')+1]).is_file())
            output='https://github.com/carstenartur/Fresnel/pull/42'
        elif args[1]=='api':
            path=args[2].split('carstenartur/Fresnel/')[1]
            if path=='git/refs':
                if self.control_created: raise subprocess.CalledProcessError(1,args,stderr='HTTP 422 already exists')
                self.control_created=True; output='{}'
            elif path=='git/ref/heads/main':
                self.reads+=1
                sha='b'*40 if self.main_drift and self.reads>1 else self.main
                output=json.dumps({'object':{'sha':sha,'type':'commit','url':'https://api.github.com/repos/carstenartur/Fresnel/git/commits/'+sha}})
            elif path=='git/ref/heads/maintenance/0.1.x':
                output=json.dumps({'object':{'sha':self.maintenance_sha}})
            elif path=='git/refs/heads/maintenance/0.1.x':
                self.maintenance_sha=args[args.index('-f')+1].split('=',1)[1]
                output='{}'
            elif path=='git/ref/heads/release/prepare-next-0.1.9-SNAPSHOT':
                if not self.next_head: raise subprocess.CalledProcessError(1,args,stderr='HTTP 404')
                output=json.dumps({'object':{'sha':self.next_head}})
            elif path.startswith('git/ref/heads/release/candidate-recovery-'):
                output=json.dumps({'object':{'sha':self.main}})
            elif path=='git/ref/heads/'+self.expected['candidate_branch']:
                output=json.dumps({'object':{'sha':self.candidate}})
            elif path=='git/ref/tags/0.1.8':
                output=json.dumps({'object':{'sha':self.expected['tag_object'],'type':'tag'}})
            elif path=='git/tags/'+self.expected['tag_object']:
                output=json.dumps({'tag':'0.1.8','object':{'sha':self.candidate,'type':'commit'}})
            elif path=='releases/430398234':
                if '--method' in args:
                    self.release.update(draft=False,published_at='2026-11-02T00:00:00Z')
                output=json.dumps(self.release)
            elif path=='actions/runs/41279261127': output=json.dumps(self.run)
            elif path.startswith('actions/workflows/'):
                output=json.dumps([{'workflow_runs':[dict(id=r['databaseId'],display_title=r['displayTitle'],head_sha=r['headSha'],status=r['status'],conclusion=r['conclusion']) for r in self.workflow_runs]}])
            elif path.startswith('releases/430398234/assets'): output=json.dumps([self.assets])
            elif path in ('releases/assets/1','releases/assets/2'):
                index=0 if path.endswith('/1') else 1
                self.downloads+=1
                if self.telemetry_churn:
                    self.assets[index]['download_count']=self.assets[index].get('download_count',0)+1
                    self.assets[index]['updated_at']='asset-read-'+str(self.downloads)
                    self.assets[index]['created_at']='incidental-asset-clock-'+str(self.downloads)
                    self.release['updated_at']='release-read-'+str(self.downloads)
                    self.release['created_at']='incidental-release-clock-'+str(self.downloads)
                output=self.data if index==0 else self.checksum
                if self.late_content_drift and index==0 and self.reads>1:
                    output+=b'changed content'
            elif path=='environments/release':
                output=json.dumps(self.policy() if self.policy_ok else {'protection_rules':[],'deployment_branch_policy':None})
            elif path.startswith('environments/release/deployment-branch-policies'):
                output=json.dumps([{'branch_policies':[{'name':'release/candidate-*','type':'branch'},{'name':'[0-9]*.[0-9]*.[0-9]*','type':'tag'}]}])
            elif path.startswith('releases/tags/'):
                raise subprocess.CalledProcessError(1,args,stderr='HTTP 404')
            elif path=='releases?per_page=100': output=json.dumps([[self.release]])
            else: raise AssertionError('Unexpected API command '+repr(args))
        else: raise AssertionError('Unexpected external command '+repr(args))
        return subprocess.CompletedProcess(args,0,stdout=output,stderr='')

    def writes(self):
        return [a for a in self.ledger if '--method' in a or 'create' in a or a[1:3] in (['workflow','run'],['run','rerun'],['pr','create']) or a[:2]==['git','push']]

    def resume(self,**kwargs):
        return self.adapter.resume(self.main,'initiator',control_branch='release/candidate-recovery-0.1.8-123-1',event_sha=self.main,**kwargs)

    def test_api_tag_404_does_not_prevent_exact_draft_verification(self):
        with self.assertRaises(subprocess.CalledProcessError): self.adapter.api('releases/tags/0.1.8')
        pages=self.adapter.api('releases?per_page=100','--paginate','--slurp')
        self.assertEqual(430398234,M.resolve_unique_draft(pages[0],'0.1.8'))
        self.resume(dry_run=True)
        self.assertEqual([],self.writes())

    def test_dry_run_has_no_writes_even_with_valid_policy(self):
        result=self.resume(dry_run=True)
        self.assertEqual(['promote_latest','publish_release'],result['effects'])
        self.assertEqual([],self.writes())

    def test_empty_environment_blocks_all_live_writes_and_reports_in_dry_run(self):
        self.policy_ok=False
        self.assertIn('required reviewer',self.resume(dry_run=True)['policy_blocker'])
        with self.assertRaisesRegex(ValueError,'required reviewer'): self.resume()
        self.assertEqual([],self.writes())

    def test_main_lease_drift_before_write_aborts(self):
        self.main_drift=True
        with self.assertRaisesRegex(ValueError,'main lease drift'): self.resume()
        self.assertEqual([],self.writes())

    def test_resume_promotes_by_immutable_digest_and_numeric_id_then_is_idempotent(self):
        self.assertTrue(self.resume()['core_published'])
        writes=self.writes()
        self.assertEqual(2,len(writes))
        self.assertEqual(self.expected['image']+'@'+self.expected['image_digest'],writes[0][-1])
        self.assertEqual('repos/carstenartur/Fresnel/releases/430398234',writes[1][2])
        self.ledger=[]
        self.assertEqual([],self.resume()['effects'])
        self.assertEqual([],self.writes())

    def extra_package_asset(self):
        return {'id':99,'name':'fresnel-linux.zip','state':'uploaded','size':3,'digest':'sha256:'+'c'*64}

    def test_extra_draft_asset_is_rejected_before_any_write(self):
        self.assets.append(self.extra_package_asset())
        with self.assertRaisesRegex(ValueError,'draft asset set'): self.resume()
        self.assertEqual([],self.writes())

    def test_extra_draft_asset_prevents_control_dispatch(self):
        self.assets.append(self.extra_package_asset())
        with self.assertRaisesRegex(ValueError,'draft asset set'):
            self.adapter.orchestrate(self.main,'initiator','123-1','0.1.9-SNAPSHOT')
        self.assertEqual([],self.writes())

    def test_published_platform_additions_allow_noop_and_post_publication_completion(self):
        self.assets.append(self.extra_package_asset())
        self.release.update(draft=False,published_at='2026-11-02T00:00:00Z')
        self.latest=True
        self.assertEqual([],self.resume()['effects'])
        title='Release Packages 0.1.8 recovery-0.1.8-41279261127'
        self.workflow_runs=[{'databaseId':999,'displayTitle':title,'headSha':self.candidate,'status':'completed','conclusion':'success'}]
        self.assertEqual(999,self.adapter.dispatch_and_wait('release-package.yml','0.1.8',{},title))
        self.assertEqual([],self.writes())
        self.assertIn('/pull/42',self.adapter.housekeeping(self.main,'0.1.9-SNAPSHOT')['next_pr'])
        self.assertEqual(2,len(self.writes()))
        self.assertFalse(self.release['draft'])
        self.assertEqual(3,len(self.assets))

    def test_different_old_latest_digests_block_publication_before_writes(self):
        self.latest_sequence=['sha256:'+'a'*64,'sha256:'+'b'*64]
        with self.assertRaisesRegex(ValueError,'state changed'): self.resume()
        self.assertEqual([],self.writes())

    def test_missing_then_present_latest_blocks_publication_before_writes(self):
        self.latest_sequence=[None,'sha256:'+'b'*64]
        with self.assertRaisesRegex(ValueError,'state changed'): self.resume()
        self.assertEqual([],self.writes())

    def test_different_old_latest_digests_block_control_dispatch(self):
        self.latest_sequence=['sha256:'+'a'*64,'sha256:'+'b'*64]
        with self.assertRaisesRegex(ValueError,'state changed'):
            self.adapter.orchestrate(self.main,'initiator','123-1','0.1.9-SNAPSHOT')
        self.assertEqual([],self.writes())

    def test_missing_then_present_latest_blocks_control_dispatch(self):
        self.latest_sequence=[None,'sha256:'+'b'*64]
        with self.assertRaisesRegex(ValueError,'state changed'):
            self.adapter.orchestrate(self.main,'initiator','123-1','0.1.9-SNAPSHOT')
        self.assertEqual([],self.writes())

    def test_observation_retains_actual_latest_digest_or_confirmed_absence(self):
        for digest in ('sha256:'+'a'*64,self.expected['image_digest'],None):
            with self.subTest(digest=digest):
                self.latest_sequence=[digest]
                state=self.adapter.observe(self.main,'initiator')
                self.assertIn('latest_digest',state)
                self.assertEqual(digest,state['latest_digest'])
                self.assertEqual(digest==self.expected['image_digest'],state['latest_matches'])

    def test_unchanged_missing_latest_can_be_promoted_from_verified_digest(self):
        self.latest_sequence=[None,None]
        self.assertTrue(self.resume()['core_published'])
        self.assertEqual(2,len(self.writes()))
        self.assertEqual(self.expected['image']+'@'+self.expected['image_digest'],self.writes()[0][-1])

    def test_latest_absence_requires_error_bound_to_requested_manifest(self):
        tag=self.expected['image']+':latest'
        for stderr in ('ERROR: '+tag+': manifest unknown','ERROR: '+tag+': not found'):
            with self.subTest(stderr=stderr):
                self.latest_lookup_error=stderr
                self.assertIsNone(self.adapter.digest(tag,missing_ok=True))
                with self.assertRaises(subprocess.CalledProcessError): self.adapter.digest(tag)
        self.assertEqual([],self.writes())

    def test_latest_auth_proxy_network_and_unqualified_errors_are_not_absence(self):
        tag=self.expected['image']+':latest'
        for stderr in ('failed to authorize: token endpoint 404 Not Found',
                       'proxy response 404 Not Found',
                       'failed to authorize: unauthorized',
                       'network DNS failure / no such host',
                       'manifest unknown',
                       'ERROR: other.example/image:latest: manifest unknown',
                       'failed to authorize: '+tag+': not found',
                       'proxy response for '+tag+': not found'):
            with self.subTest(stderr=stderr):
                self.latest_lookup_error=stderr
                with self.assertRaises(subprocess.CalledProcessError):
                    self.adapter.digest(tag,missing_ok=True)
                with self.assertRaises(subprocess.CalledProcessError): self.resume()
                self.assertEqual([],self.writes())

    def test_latest_malformed_json_propagates_before_any_write(self):
        self.latest_malformed=True
        with self.assertRaises(json.JSONDecodeError): self.resume()
        self.assertEqual([],self.writes())

    def test_partial_latest_only_resume_publishes_without_rebuilding(self):
        self.latest=True
        self.resume()
        self.assertEqual(1,len(self.writes()))
        self.assertIn('draft=false',self.writes()[0])

    def test_asset_identity_drift_before_first_write_aborts(self):
        original=self.adapter.observe
        calls=0
        def observe(*args):
            nonlocal calls
            calls+=1
            if calls==2:
                self.assets[0]['id']=7
            return original(*args)
        with patch.object(self.adapter,'observe',side_effect=observe):
            with self.assertRaisesRegex(ValueError,'asset IDs'): self.resume()
        self.assertEqual([],self.writes())

    def test_resume_accepts_download_counter_and_timestamp_churn(self):
        self.telemetry_churn=True
        self.assertTrue(self.resume()['core_published'])
        self.assertEqual(2,len(self.writes()))
        self.assertGreaterEqual(self.downloads,8)
        self.assertGreater(self.assets[0]['download_count'],1)
        self.ledger=[]
        self.assertEqual([],self.resume()['effects'])
        self.assertEqual([],self.writes())

    def test_orchestration_accepts_download_counter_and_timestamp_churn(self):
        self.telemetry_churn=True
        result=self.adapter.orchestrate(self.main,'initiator','123-1','0.1.9-SNAPSHOT')
        self.assertEqual(999,result['publication_run_id'])
        self.assertEqual(2,len(self.writes()))
        self.assertEqual(4,self.downloads)

    def test_late_downloaded_content_drift_still_blocks_before_writes(self):
        self.telemetry_churn=True
        self.late_content_drift=True
        with self.assertRaisesRegex(ValueError,'metadata/hash mismatch'): self.resume()
        self.assertEqual([],self.writes())

    def test_release_content_drift_still_blocks_before_writes(self):
        original=self.adapter.observe
        calls=0
        def observe(*args):
            nonlocal calls
            calls+=1
            if calls==2:
                self.release['body']='different release description'
            return original(*args)
        with patch.object(self.adapter,'observe',side_effect=observe):
            with self.assertRaisesRegex(ValueError,'state changed'): self.resume()
        self.assertEqual([],self.writes())

    def test_housekeeping_existing_reviewed_pr_is_idempotent(self):
        self.release.update(draft=False,published_at='2026-11-02T00:00:00Z')
        self.latest=True
        self.git('checkout','-qb','release/prepare-next-0.1.9-SNAPSHOT')
        for name in M.METADATA:
            path=self.repo/name
            path.write_text(path.read_text().replace('0.1.8','0.1.9-SNAPSHOT'))
        (self.repo/'release.properties').write_text('next.release.version=0.1.9\n')
        self.commit('next development iteration')
        self.next_head=self.git('rev-parse','HEAD')
        self.git('checkout','-q','main')
        result=self.adapter.housekeeping(self.main,'0.1.9-SNAPSHOT')
        self.assertEqual(42,result['next_pr'])
        self.assertEqual([],self.writes())
        self.assertEqual(self.main,self.git('rev-parse','HEAD'))

    def maintenance_remote(self, sha):
        remote=tempfile.TemporaryDirectory(); self.addCleanup(remote.cleanup)
        self.real_run(['git','init','--bare','-q',remote.name],check=True)
        self.git('remote','add','origin',remote.name)
        self.real_run(['git','push','-q','origin',sha+':refs/heads/maintenance/0.1.x'],cwd=self.repo,check=True)
        self.maintenance_sha=sha

    def test_maintenance_fast_forward_uses_reviewed_main_without_force(self):
        self.maintenance_remote(self.base)
        self.adapter.update_maintenance(self.main)
        self.assertEqual(self.main,self.maintenance_sha)
        writes=self.writes()
        self.assertEqual(1,len(writes))
        self.assertIn('force=false',writes[0])
        self.assertEqual('repos/carstenartur/Fresnel/git/refs/heads/maintenance/0.1.x',writes[0][2])

    def test_divergent_maintenance_branch_is_not_overwritten(self):
        self.git('checkout','-q','--detach',self.base)
        (self.repo/'runtime.txt').write_text('different maintenance work')
        self.commit('maintenance work')
        divergent=self.git('rev-parse','HEAD')
        self.git('checkout','-q','main')
        self.maintenance_remote(divergent)
        with self.assertRaisesRegex(ValueError,'cannot fast-forward'):
            self.adapter.update_maintenance(self.main)
        self.assertEqual(divergent,self.maintenance_sha)
        self.assertEqual([],self.writes())

    def test_cryptographic_failure_and_versioned_image_drift_prevent_writes(self):
        self.crypto_failure=True
        with self.assertRaises(subprocess.CalledProcessError): self.resume()
        self.crypto_failure=False; self.image_drift=True
        with self.assertRaisesRegex(ValueError,'versioned image digest changed'): self.resume()
        self.assertEqual([],self.writes())

    def test_next_development_creation_starts_at_reviewed_main_and_preserves_repair(self):
        self.release.update(draft=False,published_at='2026-11-02T00:00:00Z'); self.latest=True
        result=self.adapter.housekeeping(self.main,'0.1.9-SNAPSHOT')
        self.assertIn('/pull/42',result['next_pr'])
        self.assertEqual(self.main,self.git('rev-parse','HEAD^'))
        self.assertEqual('reviewed recovery',self.git('show','HEAD:docs/release-recovery.md'))
        self.assertEqual(self.candidate,self.git('rev-parse',self.expected['candidate_branch']))
        self.assertEqual(2,len(self.writes()))
        self.ledger=[]
        # Simulate a new runner checkout at reviewed main for a retry.
        self.git('checkout','-q','main')
        self.assertEqual(42,self.adapter.housekeeping(self.main,'0.1.9-SNAPSHOT')['next_pr'])
        self.assertEqual([],self.writes())

    def test_package_dispatch_uses_original_tag_and_reuses_successful_run(self):
        request='recovery-0.1.8-41279261127'
        fields={'release_tag':'0.1.8','request_id':request}
        title='Release Packages 0.1.8 '+request
        self.assertEqual(999,self.adapter.dispatch_and_wait('release-package.yml','0.1.8',fields,title))
        dispatched=[a for a in self.ledger if a[1:3]==['workflow','run']]
        self.assertEqual(1,len(dispatched))
        self.assertEqual('0.1.8',dispatched[0][dispatched[0].index('--ref')+1])
        self.ledger=[]
        self.adapter.dispatch_and_wait('release-package.yml','0.1.8',fields,title)
        self.assertEqual([],self.writes())

    def test_completed_non_success_runs_require_diagnosis_without_retry_or_admission(self):
        title='Release Packages 0.1.8 recovery-0.1.8-41279261127'
        for conclusion in ('failure','cancelled','timed_out','action_required','neutral','skipped','stale','startup_failure',None,'unknown'):
            with self.subTest(conclusion=conclusion):
                self.ledger=[]
                self.workflow_runs=[{'databaseId':999,'displayTitle':title,'headSha':self.candidate,'status':'completed','conclusion':conclusion}]
                with self.assertRaises(ValueError) as failure:
                    self.adapter.dispatch_and_wait('release-package.yml','0.1.8',{'release_tag':'0.1.8'},title)
                message=str(failure.exception)
                self.assertIn('https://github.com/carstenartur/Fresnel/actions/runs/999',message)
                self.assertIn('completed',message)
                self.assertIn(str(conclusion),message)
                self.assertIn('diagnosis',message)
                self.assertEqual([],self.writes())

    def test_active_matching_runs_are_watched_without_retry_or_admission(self):
        title='Release Packages 0.1.8 recovery-0.1.8-41279261127'
        for status in ('queued','in_progress','pending','waiting','requested'):
            with self.subTest(status=status):
                self.ledger=[]
                self.workflow_runs=[{'databaseId':999,'displayTitle':title,'headSha':self.candidate,'status':status,'conclusion':None}]
                self.assertEqual(999,self.adapter.dispatch_and_wait('release-package.yml','0.1.8',{'release_tag':'0.1.8'},title))
                self.assertEqual([],self.writes())
                self.assertTrue(any(a[1:3]==['run','watch'] for a in self.ledger))

    def test_externally_authorized_retry_is_rediscovered_without_helper_mutation(self):
        title='Release Packages 0.1.8 recovery-0.1.8-41279261127'
        self.workflow_runs=[{'databaseId':999,'displayTitle':title,'headSha':self.candidate,'status':'completed','conclusion':'failure'}]
        with self.assertRaises(ValueError):
            self.adapter.dispatch_and_wait('release-package.yml','0.1.8',{'release_tag':'0.1.8'},title)
        self.workflow_runs[0].update(status='in_progress',conclusion=None)
        self.assertEqual(999,self.adapter.dispatch_and_wait('release-package.yml','0.1.8',{'release_tag':'0.1.8'},title))
        self.assertEqual([],self.writes())

    def test_package_wrong_head_fails_before_wait_or_retry(self):
        title='Release Packages 0.1.8 recovery'
        self.workflow_runs=[{'databaseId':999,'displayTitle':title,'headSha':self.main,'status':'completed','conclusion':'failure'}]
        with self.assertRaisesRegex(ValueError,'head differs'):
            self.adapter.dispatch_and_wait('release-package.yml','0.1.8',{},title)
        self.assertEqual([],self.writes())

    def test_canonical_orchestration_control_ref_is_exact_reviewed_main(self):
        result=self.adapter.orchestrate(self.main,'initiator','123-1','0.1.9-SNAPSHOT')
        self.assertEqual(999,result['publication_run_id'])
        writes=self.writes()
        self.assertEqual(2,len(writes))
        self.assertIn('sha='+self.main,writes[0])
        self.assertIn('expected_candidate_sha='+self.candidate,writes[1])
        self.assertIn('expected_main_sha='+self.main,writes[1])

    def test_control_sha_cannot_replace_artifact_source(self):
        with self.assertRaisesRegex(ValueError,'control SHA'):
            self.adapter.resume(self.main,'initiator',control_branch='release/candidate-recovery-0.1.8-123-1',event_sha=self.candidate)
        self.assertEqual([],self.writes())

    def test_wrong_next_citation_version_is_rejected(self):
        self.git('checkout','-qb','invalid-next')
        for name in M.METADATA:
            path=self.repo/name
            path.write_text(path.read_text().replace('0.1.8','0.1.9-SNAPSHOT'))
        (self.repo/'CITATION.cff').write_text('version: 99.0.0-SNAPSHOT\n')
        (self.repo/'release.properties').write_text('next.release.version=0.1.9\n')
        self.commit('wrong next citation')
        with self.assertRaisesRegex(ValueError,'CITATION.cff'):
            self.adapter.validate_next_iteration(self.main,self.git('rev-parse','HEAD'),'0.1.9-SNAPSHOT')

    def test_next_properties_must_contain_only_planned_version_change(self):
        self.git('checkout','-qb','invalid-properties')
        for name in M.METADATA:
            path=self.repo/name
            path.write_text(path.read_text().replace('0.1.8','0.1.9-SNAPSHOT'))
        (self.repo/'release.properties').write_text('next.release.version=0.1.9\nunsafe.option=true\n')
        self.commit('unexpected next properties')
        with self.assertRaisesRegex(ValueError,'property'):
            self.adapter.validate_next_iteration(self.main,self.git('rev-parse','HEAD'),'0.1.9-SNAPSHOT')

    def test_orchestration_dry_run_cannot_create_control_ref_or_dispatch(self):
        self.adapter.orchestrate(self.main,'initiator','123-1','0.1.9-SNAPSHOT',dry_run=True)
        self.assertEqual([],self.writes())


if __name__ == '__main__': unittest.main()
