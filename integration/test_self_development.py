import sys,tempfile,unittest,json,subprocess,zipfile,hashlib,os
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parent));import self_development as dev

def git(where,*args):return dev.run(['git',*args],where)
class FixtureService(dev.Service):
 def execute_qa(self,folder,repo,state):
  (repo/'build').mkdir(exist_ok=True);value=(repo/'VERSION').read_text().strip()
  with zipfile.ZipFile(repo/'build/LocalChat.jar','w') as z:z.writestr('VERSION',value)
  log=folder/'tests.log';log.write_text('Synthetic trusted-runner fixture PASS',encoding='utf-8')
  return dict(log=str(log),log_sha256=dev.digest(log),baseline=list(dev.CORE),additional_tests=['AddedTest'],physical_mouse_keyboard_tested=False)
class DevelopmentTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.repo=self.root/'source';self.repo.mkdir();self.remote=self.root/'remote.git';self.home=self.root/'app';self.home.mkdir()
  git(self.root,'init','--bare',str(self.remote));git(self.repo,'init');git(self.repo,'config','user.name','Fixture');git(self.repo,'config','user.email','fixture@example.invalid')
  for name,text in [('VERSION','1.0.0'),('CHANGELOG.md','Original'),('DEVELOPMENT_STATUS.md','Original'),('README.md','Source; historical PRODUCTIE/'+ '507998/example'),('AGENTS.md','Keep guards'),('Build.ps1','trusted build')]: (self.repo/name).write_text(text,encoding='utf-8')
  (self.repo/'src/localchat').mkdir(parents=True);(self.repo/'src/localchat/App.java').write_text('class App {}');(self.repo/'tests').mkdir();(self.repo/'.gitignore').write_text('build/\npdfcontrol/\n')
  git(self.repo,'add','.');git(self.repo,'commit','-m','fixture baseline');git(self.repo,'remote','add','origin',str(self.remote));git(self.repo,'push','origin','HEAD:refs/heads/main');self.base=git(self.repo,'rev-parse','HEAD')
  self.service=FixtureService(self.home,self.repo,str(self.remote),'chat-one');self.started=self.service.request(dict(action='begin',goal='Implement a generic application helper'));self.key=self.started['session'];self.folder,self.work,self.state=self.service.job(self.key)
 def tearDown(self):
  # Git worktrees are read-only test fixtures after completion. Avoid recursive deletion across junctions.
  git(self.repo,'worktree','remove','--force',str(self.work));self.tmp.cleanup()
 def call(self,action,**args):return self.service.request(dict(action=action,session=self.key,**args))
 def prepare(self):
  for path,content in [('VERSION','1.0.1'),('CHANGELOG.md','1.0.1 Added helper'),('DEVELOPMENT_STATUS.md','1.0.1 Test evidence'),('src/localchat/Helper.java','class Helper {}'),('tests/AddedTest.java','class AddedTest { public static void main(String[] args){if(1!=1)throw new AssertionError();} }')]:self.call('write',path=path,content=content)
 def test_isolation_and_owner(self):
  self.call('write',path='src/localchat/App.java',content='class App { int x; }');self.assertEqual((self.repo/'src/localchat/App.java').read_text(),'class App {}');self.assertIn('int x',self.call('read',path='src/localchat/App.java')['text']);self.assertEqual(git(self.repo,'rev-parse','HEAD'),self.base)
  with self.assertRaises(ValueError):FixtureService(self.home,self.repo,str(self.remote),'another-chat').request(dict(action='status',session=self.key))
 def test_exact_edit_and_invalid_matches(self):
  self.call('edit',path='src/localchat/App.java',find='class App {}',replace='class App { int result; }');self.assertIn('int result',self.call('read',path='src/localchat/App.java')['text']);self.assertEqual((self.repo/'src/localchat/App.java').read_text(),'class App {}')
  with self.assertRaises(ValueError):self.call('edit',path='src/localchat/App.java',find='missing',replace='no')
  self.call('write',path='src/localchat/App.java',content='class App { int result; int other; }')
  with self.assertRaises(ValueError):self.call('edit',path='src/localchat/App.java',find='int ',replace='long ')
  with self.assertRaises(ValueError):self.call('edit',path='src/localchat/App.java',find='int result;',replace='String secret="'+'sk-'+'x'*30+'";')
  with self.assertRaises(ValueError):self.call('edit',path='Build.ps1',find='trusted',replace='untrusted')
 def test_path_policy(self):
  for name in ('../settings.properties','/tmp/x','C:/x','src/localchat/../x.java','src/localchat/FileGuard.java','integration/self_development.py','Build.ps1','.git/config','pdfcontrol/data_lokaal/catalogus.json','src/localchat/X.java:secret'):
   with self.subTest(name=name),self.assertRaises(ValueError):self.call('write',path=name,content='forbidden')
 def test_untrusted_test_status_argument(self):
  with self.assertRaises(ValueError):self.call('publish',passed=True)
  with self.assertRaises(ValueError):self.call('publish')
 def test_version_documentation_test_required(self):
  self.prepare();self.call('write',path='VERSION',content='1.0.0')
  with self.assertRaises(ValueError):self.call('validate')
  self.call('write',path='VERSION',content='1.0.1');self.call('remove',path='tests/AddedTest.java')
  with self.assertRaises(ValueError):self.call('validate')
 def test_exact_tested_sources_then_real_local_push(self):
  self.prepare();tested=self.call('validate');self.assertEqual(tested['status'],'tested');published=self.call('publish');self.assertEqual(published['status'],'published');self.assertEqual(git(self.remote,'rev-parse','refs/heads/'+self.started['branch']),published['commit']);self.assertEqual(git(self.remote,'rev-parse','main'),self.base);self.assertEqual(git(self.repo,'rev-parse','HEAD'),self.base)
  self.assertEqual(self.call('publish')['commit'],published['commit']);self.assertTrue((Path(published['update_bundle'])/'manifest.json').is_file())
  with self.assertRaises(ValueError):self.call('write',path='README.md',content='post-publication mutation')
 def test_changed_source_invalidates_pass(self):
  self.prepare();self.call('validate');self.call('write',path='README.md',content='new version description')
  with self.assertRaises(ValueError):self.call('publish')
 def test_artifact_and_log_tampering(self):
  self.prepare();self.call('validate');(self.work/'build/LocalChat.jar').write_bytes(b'changed')
  with self.assertRaises(ValueError):self.call('publish')
 def test_changed_remote_blocked(self):
  self.prepare();self.call('validate');git(self.work,'remote','set-url','origin',str(self.root/'different.git'))
  with self.assertRaises(ValueError):self.call('publish')
 def test_privacy(self):
  for text in ('API secret sk-'+'x'*30,'C:/'+'Users/'+'PrivatePerson/file.pdf','PRODUCTIE/'+'507999/doc.pdf'):
   with self.assertRaises(ValueError):self.call('write',path='README.md',content=text)
 def test_inherited_example_not_new_private_data(self):
  prior=self.call('read',path='README.md')['text'];self.call('write',path='README.md',content='Generic documentation update\n'+prior)
  self.service.fingerprint(self.work)
  with self.assertRaises(ValueError):self.call('write',path='README.md',content=prior+' and PRODUCTIE/'+'507997/private')
  with self.assertRaises(ValueError):self.call('write',path='README.md',content=prior+prior)
 def test_failed_qa_never_commits(self):
  self.prepare()
  with patch.object(self.service,'execute_qa',side_effect=RuntimeError('fixture failure')):
   with self.assertRaises(RuntimeError):self.call('validate')
  self.assertEqual(self.call('status')['status'],'failed');self.assertEqual(git(self.work,'rev-parse','HEAD'),self.base)
  with self.assertRaises(ValueError):self.call('publish')
 def test_push_retry_keeps_same_tested_commit(self):
  self.prepare();self.call('validate');original=self.service.git;attempts=[]
  def fail_once(*args,**kwargs):
   if args and args[0]=='push' and not attempts:attempts.append(1);raise RuntimeError('connection unavailable')
   return original(*args,**kwargs)
  with patch.object(self.service,'git',side_effect=fail_once):
   with self.assertRaises(RuntimeError):self.call('publish')
   pending=self.call('status');self.assertEqual(pending['status'],'committed');commit=git(self.work,'rev-parse','HEAD');self.assertEqual(self.call('publish')['commit'],commit)
 def test_no_source_changes_at_begin(self):
  (self.repo/'README.md').write_text('user work in progress')
  with self.assertRaises(ValueError):self.service.request(dict(action='begin',goal='Another helper'))
 def test_interrupted_validation_not_false_running_or_passed(self):
  state=json.loads((self.folder/'state.json').read_text());state['status']='testing';dev.atomic(self.folder/'state.json',state);(self.folder/'operation.lock').write_text('2147483647')
  status=self.call('status');self.assertEqual(status['status'],'interrupted');self.assertIsNone(status['tested'])
  with self.assertRaises(ValueError):self.call('publish')
  self.call('write',path='README.md',content='Resume after interruption');self.assertEqual(self.call('status')['status'],'editing')
 def test_lock_and_status(self):
  (self.folder/'operation.lock').write_text(str(os.getpid()))
  self.assertEqual(self.call('status')['status'],'editing')
  with self.assertRaises(ValueError):self.call('write',path='README.md',content='changed')
  (self.folder/'operation.lock').unlink()

if __name__=='__main__':unittest.main()
