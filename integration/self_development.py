"""LocalChat opt-in development service: source worktrees, verified QA and branch publication.
Project documents and app settings stay outside editable source; installed application files are never overwritten here.
"""
import argparse,hashlib,json,os,re,shutil,subprocess,sys,time,uuid,zipfile,contextlib
from collections import Counter
from pathlib import Path,PurePosixPath

CORE=('LocalMemoriesTest','NormalModeTest','SensitiveGuardTest','ContextGuardTest','CodexConnectionTest')
PROTECTED={'Build.ps1','AGENTS.md','.gitignore','integration/self_development.py','src/localchat/SelfDevelopment.java','src/localchat/FileGuard.java','src/localchat/SensitiveGuard.java','src/localchat/CodexConnection.java','tests/SelfDevelopmentTest.java','integration/test_self_development.py'}
ROOT_FILES={'VERSION','CHANGELOG.md','DEVELOPMENT_STATUS.md','README.md'}
PRIVATE=re.compile(r'(?i)(?:sk-[a-z0-9_-]{20,}|xai-[a-z0-9_-]{20,}|gsk_[a-z0-9_-]{20,}|gh[pousr]_[a-z0-9]{20,}|bearer\s+\S+|[A-Z]:[\\/]+Users[\\/]+[^\\/\s]+|(?:PRODUCTIE|Projecten)[\\/]+[5-9]\d{5})')
def digest(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def atomic(path,value):
 path=Path(path);path.parent.mkdir(parents=True,exist_ok=True);temp=path.with_name(path.name+'.'+uuid.uuid4().hex+'.tmp');temp.write_text(json.dumps(value,ensure_ascii=True,indent=2),encoding='utf-8');os.replace(temp,path)
def origin(value):return value.strip().removesuffix('.git').removesuffix('/').replace('git@github.com:','https://github.com/').lower()
def version(value):
 if not re.fullmatch(r'\d+\.\d+\.\d+',value.strip()):raise ValueError('VERSION must contain one semantic version')
 return tuple(map(int,value.strip().split('.')))
def run(args,cwd,timeout=120,env=None):
 p=subprocess.run([str(x) for x in args],cwd=cwd,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
 if p.returncode:raise RuntimeError(('Command failed: '+str(args[0])+'\n'+p.stdout+'\n'+p.stderr)[-6000:])
 return p.stdout.strip()
def alive(pid):
 if not isinstance(pid,int) or not 0<pid<2147483647:return False
 if os.name=='nt':
  check=subprocess.run(['powershell','-NoProfile','-Command','[bool](Get-Process -Id '+str(pid)+' -ErrorAction SilentlyContinue)'],capture_output=True,text=True,creationflags=subprocess.CREATE_NO_WINDOW)
  return check.stdout.strip().lower()=='true'
 try:os.kill(pid,0);return True
 except ProcessLookupError:return False

def allowed(name,write=False):
 if not isinstance(name,str) or len(name)>240 or '\\' in name or ':' in name or '\x00' in name or '\n' in name:return False
 path=PurePosixPath(name)
 if path.is_absolute() or any(p in ('..','.git') or p.startswith('.') for p in path.parts):return False
 if write and name in PROTECTED:return False
 return name in ROOT_FILES or bool(re.fullmatch(r'(?:src/localchat/[A-Za-z0-9_]+\.java|src/localchat/i18n/(?:nl|ro|es|pl|en|tr)\.json|tests/[A-Za-z0-9_]+\.java|integration/[A-Za-z0-9_.-]+\.(?:py|md))',name)) or (not write and name in PROTECTED)

class Service:
 def __init__(self,home,source,expected,owner="fixture"):
  self.home=Path(home).resolve();self.source=Path(source).resolve();self.root=self.home/'self-development'/'jobs';self.root.mkdir(parents=True,exist_ok=True);self.expected=expected;self.owner=owner
  if str(self.source).startswith('\\\\') or str(self.home).startswith('\\\\'):raise ValueError('Development requires local folders')
 def git(self,*args,cwd=None):return run(['git',*args],cwd or self.source)
 def job(self,key):
  if not re.fullmatch('[a-f0-9]{32}',key or ''):raise ValueError('Invalid development session')
  folder=self.root/key
  if folder.is_symlink() or not folder.resolve().is_relative_to(self.root.resolve()):raise ValueError('Session is outside development storage')
  state=json.loads((folder/'state.json').read_text(encoding='utf-8'));repo=Path(state['repo'])
  if state.get('owner')!=self.owner:raise ValueError('Development session belongs to another chat')
  if repo!=folder/'repo' or repo.is_symlink() or not repo.resolve().is_relative_to(folder.resolve()):raise ValueError('Invalid worktree path')
  return folder,repo,state
 def save(self,folder,state):atomic(folder/'state.json',state)
 def begin(self,goal):
  if not isinstance(goal,str) or not 3<=len(goal)<=3000:raise ValueError('A concrete development goal is required')
  if self.git('rev-parse','--is-inside-work-tree')!='true':raise ValueError('Configured source is not a Git repository')
  if origin(self.git('remote','get-url','origin'))!=origin(self.expected):raise ValueError('Origin differs from the configured application source repository')
  if self.git('status','--porcelain'):raise ValueError('Source has local changes; preserve/finish them before automatic development')
  key=uuid.uuid4().hex;folder=self.root/key;folder.mkdir();repo=folder/'repo';base=self.git('rev-parse','HEAD');branch='localchat/selfdev/'+key
  self.git('worktree','add','-b',branch,str(repo),base)
  baseline=self.git('show',base+':VERSION');version(baseline)
  state=dict(owner=self.owner,id=key,repo=str(repo),base=base,base_version=baseline,branch=branch,goal=goal,status='editing',origin=self.expected,created=time.time(),tested=None)
  self.save(folder,state);return dict(session=key,branch=branch,base_version=baseline,status='editing',instructions='Read AGENTS.md first. Publish generic code only, never project documents, catalogues, settings, credentials or private traces. Use list/read/write/edit/remove; edit replaces one exact snippet without resending the full file. Add a regression test; update VERSION, CHANGELOG.md and DEVELOPMENT_STATUS.md. validate runs trusted build plus pinned core tests; publish is blocked without a matching pass. Production files and the installed application remain outside this workspace.')
 def path(self,repo,name,write=False):
  if not allowed(name,write):raise ValueError('Path is outside editable source or is a protected host/policy file')
  path=repo/name
  if not path.resolve().is_relative_to(repo.resolve()):raise ValueError('Source path escapes worktree')
  return path
 def changed(self,repo):
  tracked=self.git('diff','--name-only','HEAD',cwd=repo).splitlines();untracked=self.git('ls-files','--others','--exclude-standard',cwd=repo).splitlines();return sorted(set(tracked+untracked))
 def private_additions(self,repo,name,text):
  # Existing checked-in documentation may contain historical example paths.
  # Permit unchanged baseline matches, but never add another private value/count.
  try:prior=self.git('show','HEAD:'+name,cwd=repo)
  except RuntimeError:prior=''
  return bool(Counter(m.group(0) for m in PRIVATE.finditer(text))-Counter(m.group(0) for m in PRIVATE.finditer(prior)))
 def fingerprint(self,repo):
  changed=self.changed(repo)
  for name in changed:
   self.path(repo,name,True)
   path=repo/name
   if path.exists():
    data=path.read_bytes()
    if len(data)>1024*1024 or self.private_additions(repo,name,data.decode('utf-8')):raise ValueError('Source contains private data, credentials, or an oversized file: '+name)
  return hashlib.sha256(json.dumps([(n,digest(repo/n) if (repo/n).exists() else None) for n in changed]).encode()).hexdigest(),changed
 @contextlib.contextmanager
 def locked(self,key):
  folder,_,_=self.job(key);lock=folder/'operation.lock'
  if lock.exists():
   pid=int(lock.read_text())
   if alive(pid):raise ValueError('Another operation is active for this session; use status')
   lock.unlink()
  fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY)
  try:
   os.write(fd,str(os.getpid()).encode());os.close(fd);yield
  finally:
   if lock.exists():lock.unlink()
 def request(self,args):
  if isinstance(args,dict) and args.get('action') in ('write','edit','remove','validate','publish'):
   with self.locked(args.get('session')):return self._request(args)
  return self._request(args)
 def _request(self,args):
  if not isinstance(args,dict) or set(args)-{'action','goal','session','path','content','offset','find','replace'}:raise ValueError('Unknown development arguments')
  action=args.get('action')
  if action=='begin':return self.begin(args.get('goal'))
  if action=='status' and not args.get('session'):
   rows=[]
   for path in self.root.glob('*/state.json'):
    try:
     item=json.loads(path.read_text(encoding='utf-8'))
     if item.get('owner')==self.owner:rows.append({key:item.get(key) for key in ('id','status','branch','created','base_version')})
    except (OSError,ValueError):pass
   return dict(sessions=sorted(rows,key=lambda r:r['created'],reverse=True)[:20])
  folder,repo,state=self.job(args.get('session'))
  if action=='status' and state['status']=='testing':
   lock=folder/'operation.lock'
   try:active=alive(int(lock.read_text())) if lock.exists() else False
   except (OSError,ValueError):active=False
   if not active:
    state.update(status='interrupted',tested=None,error='Validation stopped before completion; sources preserved. Run validate again before publication.');self.save(folder,state)
  if action=='status':return dict(session=state['id'],status=state['status'],branch=state['branch'],base_version=state['base_version'],tested=state['tested'],published=state.get('published'),error=state.get('error'),test_log_tail=(folder/'tests.log').read_text(encoding='utf-8',errors='replace')[-8000:] if (folder/'tests.log').exists() else '',changed=self.changed(repo))
  if action=='list':
   files=sorted(set(self.git('ls-files',cwd=repo).splitlines()+self.changed(repo)));files=[n for n in files if allowed(n)];offset=max(0,int(args.get('offset',0)));return dict(files=files[offset:offset+100],next_offset=offset+100 if len(files)>offset+100 else None)
  if action=='read':
   path=self.path(repo,args.get('path'));text=path.read_text(encoding='utf-8');offset=max(0,int(args.get('offset',0)));return dict(path=args['path'],text=text[offset:offset+12000],next_offset=offset+12000 if len(text)>offset+12000 else None)
  if state.get('published'):
   if action=='publish':return dict(status='published',session=state['id'],**state['published'])
   raise ValueError('Published session is immutable; begin another session')
  if state.get('commit_pending') and action!='publish':raise ValueError('Finish publishing the tested commit before further edits')
  if action in ('write','edit','remove'):
   path=self.path(repo,args.get('path'),True)
   if action in ('write','edit'):
    text=args.get('content')
    if action=='edit':
     find=args.get('find');replacement=args.get('replace')
     if not isinstance(find,str) or not 1<=len(find)<=50000 or not isinstance(replacement,str) or len(replacement)>100000:raise ValueError('Exact edit requires bounded find/replace text')
     text=path.read_text(encoding='utf-8')
     if text.count(find)!=1:raise ValueError('Edit must match exactly once; read the current source first')
     text=text.replace(find,replacement,1)
    if not isinstance(text,str) or len(text.encode())>1024*1024 or self.private_additions(repo,args.get('path'),text):raise ValueError('Invalid/oversized/private source content')
    path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8')
   else:path.unlink()
   state.update(status='editing',tested=None);state.pop('error',None);self.save(folder,state);return dict(status='written' if action in ('write','edit') else 'removed',path=args['path'])
  if action=='validate':return self.validate(folder,repo,state)
  if action=='publish':return self.publish(folder,repo,state)
  raise ValueError('Unknown development action')
 def requirements(self,repo,state):
  if self.git('rev-parse','HEAD',cwd=repo)!=state['base']:raise ValueError('Worktree HEAD changed outside the development service')
  fingerprint,changed=self.fingerprint(repo)
  if not all(n in changed for n in ('VERSION','CHANGELOG.md','DEVELOPMENT_STATUS.md')):raise ValueError('Update VERSION, CHANGELOG.md and DEVELOPMENT_STATUS.md before validation')
  value=(repo/'VERSION').read_text().strip()
  if version(value)<=version(state['base_version']):raise ValueError('New version must be greater than session base')
  if value not in (repo/'CHANGELOG.md').read_text(encoding='utf-8') or value not in (repo/'DEVELOPMENT_STATUS.md').read_text(encoding='utf-8'):raise ValueError('Both documentation files must describe the new version')
  tests=[n for n in changed if n.startswith('tests/') and n.endswith('Test.java') and (repo/n).is_file()]
  if not tests:raise ValueError('A new or changed regression test is required')
  return fingerprint,changed,value
 def support(self,repo):
  # Owned modules only; never seed production catalogues, API settings or project files into development.
  engine=self.source/'pdfcontrol';target=repo/'pdfcontrol';target.mkdir(exist_ok=True)
  for path in engine.glob('*.py'):shutil.copy2(path,target/path.name)
  for name in ('localchat_engine_manifest.json','lower_right_ignored_labels.json'):
   if (engine/name).is_file():shutil.copy2(engine/name,target/name)
  for name in ('bureaus.json','bureaus_prive.json','mbs_artikelnamen.json','mbs_catalogus.json','kennisbank.json'):
   # These private engine resources stay ignored and are never staged or published.
   if (engine/name).is_file():shutil.copy2(engine/name,target/name)
  runtime=target/'runtime'
  if not runtime.exists():
   if os.name!='nt':runtime.symlink_to(engine/'runtime',target_is_directory=True)
   else:run(['powershell','-NoProfile','-Command',"New-Item -ItemType Junction -Path '"+str(runtime).replace("'","''")+"' -Target '"+str(engine/'runtime').replace("'","''")+"' | Out-Null"],repo)
  java_home=os.environ.get('JAVA_HOME','')
  candidates=([Path(java_home)] if java_home else [])+list((self.source/'.tools').glob('jdk*'))
  jdk=next((p for p in candidates if (p/'bin/javac.exe').is_file()),None)
  if not jdk:raise ValueError('A configured JDK 17+ is required for development validation')
  if (engine/'webapp').is_dir() and not (target/'webapp').exists():shutil.copytree(engine/'webapp',target/'webapp')
  return jdk
 def execute_qa(self,folder,repo,state):
  jdk=self.support(repo)
  build=self.git('show',state['base']+':Build.ps1')
  # Execute a pinned host runner; model edits never select the QA command or disable the gate.
  build=build.replace('$PSScriptRoot','$taskSelfDevRoot');match=re.match(r'(?s)param\(.*?\n\)',build)
  if not match:raise ValueError('Unknown trusted build structure')
  qa=self.home/'qa'/('sd-'+uuid.uuid4().hex[:8]);qa.mkdir(parents=True,exist_ok=True);(qa/'jtmp').mkdir(exist_ok=True)
  build=build.replace('-Dlocalchat.home=$taskBuild/','-Dlocalchat.home=$taskSelfDevQaHome/')
  at=match.end();build=build[:at]+"\n$taskSelfDevQaHome='"+str(qa).replace("'","''")+"'\n"+"\n$taskSelfDevRoot='"+str(repo).replace("'","''")+"'\n"+build[at:]
  runner=folder/'runner.ps1';runner.write_text(build,encoding='utf-8');env=dict(os.environ);env['JAVA_HOME']=str(jdk);env['JAVA_TOOL_OPTIONS']='-Djdk.net.unixdomain.tmpdir='+str(qa/'jtmp')
  for key in list(env):
   if re.search(r'(?i)(api.?key|access.?token|secret|codex_app_tools|codex_internal_originator)',key):env.pop(key,None)
  log=folder/'tests.log'
  with log.open('w',encoding='utf-8') as out:
   proc=subprocess.run(['powershell','-NoProfile','-ExecutionPolicy','Bypass','-File',str(runner),'-DistributionPath',str(folder/'never-installed'),'-SkipDeploy','-SkipNativeQA'],cwd=repo,env=env,stdout=out,stderr=subprocess.STDOUT,timeout=3600,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
  if proc.returncode:raise ValueError('Build/tests failed. Read tests.log through validation status; no commit or push was performed.')
  # Changed regression tests run even when they were not registered by the baseline Build.ps1.
  current_cp=os.pathsep.join(str(repo/p) for p in ('build/classes','build/tests','lib/flatlaf-3.7.2.jar'))
  executed=[]
  for name in self.changed(repo):
   if name.startswith('tests/') and name.endswith('Test.java') and (repo/name).is_file():
    if not re.search(r'static\s+void\s+main\s*\(', (repo/name).read_text(encoding='utf-8')):continue
    cls=Path(name).stem
    output=run([jdk/'bin/java.exe','-Dlocalchat.test=true','-Dlocalchat.skipNativeQA=true','-Dlocalchat.home='+str(qa/'changed'/cls),'-Daudit.python='+str(repo/'pdfcontrol/runtime/python/python.exe'),'-Daudit.package='+str(repo/'pdfcontrol'),'-Dpdfcontrol.package='+str(repo/'pdfcontrol'),'-Dpdfcontrol.projects='+str(repo/'build/pdfcontrol-tests/PROJECTS'),'-cp',current_cp,'localchat.'+cls],repo,300,env)
    with log.open('a',encoding='utf-8') as out:out.write('\nAdditional regression '+cls+'\n'+output+'\n')
    executed.append(cls)
  if not executed:raise ValueError('No executable changed regression test was found')
  # Re-run immutable baseline core tests against candidate production classes.
  baseline=folder/'baseline-tests';baseline.mkdir(exist_ok=True)
  for name in self.git('ls-tree','-r','--name-only',state['base'],cwd=repo).splitlines():
   if re.fullmatch('tests/[A-Za-z0-9_]+.java',name):(baseline/Path(name).name).write_text(self.git('show',state['base']+':'+name,cwd=repo),encoding='utf-8')
  cp=str(repo/'build/classes')+os.pathsep+str(repo/'lib/flatlaf-3.7.2.jar');classes=folder/'baseline-classes';classes.mkdir(exist_ok=True)
  run([jdk/'bin/javac.exe','-encoding','UTF-8','--release','17','-cp',cp,'-d',classes,*baseline.glob('*.java')],repo,180,env)
  for name in CORE:run([jdk/'bin/java.exe','-Dlocalchat.test=true','-Dlocalchat.home='+str(folder/'baseline-home'/name),'-cp',cp+os.pathsep+str(classes),'localchat.'+name],repo,180,env)
  return dict(log=str(log),log_sha256=digest(log),baseline=list(CORE),additional_tests=executed,physical_mouse_keyboard_tested=False)
 def validate(self,folder,repo,state):
  fingerprint,changed,value=self.requirements(repo,state);state.update(status='testing',tested=None);self.save(folder,state)
  try:
   proof=self.execute_qa(folder,repo,state)
   if self.fingerprint(repo)[0]!=fingerprint:raise ValueError('Sources changed during validation; rerun tests')
   jar=repo/'build/LocalChat.jar'
   with zipfile.ZipFile(jar) as z:
    if z.read('VERSION').decode().strip()!=value:raise ValueError('Built JAR version mismatch')
   evidence=dict(fingerprint=fingerprint,version=value,jar=str(jar),jar_sha256=digest(jar),**proof)
   update=self.bundle(folder,repo,evidence);evidence['update_bundle']=str(update);evidence['update_manifest_sha256']=digest(update/'manifest.json')
   state.pop('error',None);state.update(status='tested',tested=evidence);self.save(folder,state);return dict(status='tested',session=state['id'],**state['tested'])
  except Exception as e:
   state.update(status='failed',error=str(e));self.save(folder,state);raise
 def bundle(self,folder,repo,proof):
  directory=folder/'update';directory.mkdir(exist_ok=True)
  files={'LocalChat.jar':Path(proof['jar']),'VERSION':repo/'VERSION','CHANGELOG.md':repo/'CHANGELOG.md','DEVELOPMENT_STATUS.md':repo/'DEVELOPMENT_STATUS.md','CITESTE.md':repo/'README.md'}
  files['self-development/service.py']=Path(__file__)
  for skill in ('pdfcontrol-octop','productie-projecten'):
   for source,target in (('DRAWING_IMPORT.md','localchat-import-din-desene.md'),('LOCALCHAT_WORKFLOW.md','localchat-workflow.md')):
    path=repo/'integration'/source
    if path.is_file():files['skills/'+skill+'/references/'+target]=path
  manifest=repo/'pdfcontrol/localchat_engine_manifest.json'
  if manifest.exists():
   engine=json.loads(manifest.read_text(encoding='utf-8'))
   for name,sha in engine['sha256'].items():
    if not re.fullmatch(r'[A-Za-z0-9_]+\.py',name) or digest(repo/'pdfcontrol'/name)!=sha:raise ValueError('Invalid built engine manifest')
    files['pdfcontrol/'+name]=repo/'pdfcontrol'/name
   files['pdfcontrol/localchat_engine_manifest.json']=manifest
  for name,path in files.items():
   target=directory/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,target)
  result={'version':proof['version'],'files':{n:digest(directory/n) for n in files},'physical_mouse_keyboard_tested':False}
  atomic(directory/'manifest.json',result);return directory
 def publish(self,folder,repo,state):
  if origin(self.git('remote','get-url','origin',cwd=repo))!=origin(self.expected):raise ValueError('Origin changed; publication refused')
  if state.get('commit_pending'):
   pending=state['commit_pending']
   if self.git('rev-parse','HEAD',cwd=repo)!=pending['commit'] or self.git('status','--porcelain',cwd=repo) or digest(pending['artifact'])!=pending['artifact_sha256']:raise ValueError('Committed publication changed; validation is no longer valid')
  else:
   fingerprint,changed,value=self.requirements(repo,state);proof=state.get('tested')
   if not proof or proof['fingerprint']!=fingerprint or proof['version']!=value or digest(proof['jar'])!=proof['jar_sha256'] or digest(proof['log'])!=proof['log_sha256']:raise ValueError('Publication requires passing tests for these exact sources and artifact')
   update=Path(proof['update_bundle'])
   if digest(update/'manifest.json')!=proof['update_manifest_sha256']:raise ValueError('Update manifest changed after tests')
   manifest=json.loads((update/'manifest.json').read_text(encoding='utf-8'))
   if any(digest(update/name)!=sha for name,sha in manifest['files'].items()):raise ValueError('Update artifact changed after tests')
   if self.git('diff','--cached','--name-only',cwd=repo):raise ValueError('Unexpected staged changes; publication refused')
   self.git('add','--',*changed,cwd=repo);self.git('commit','-m','LocalChat '+value+': tested development '+state['id'],cwd=repo);commit=self.git('rev-parse','HEAD',cwd=repo)
   pending=dict(update_bundle=proof['update_bundle'],commit=commit,branch=state['branch'],version=value,artifact=proof['jar'],artifact_sha256=proof['jar_sha256'],installation='pending_review_and_application_shutdown')
   state.update(status='committed',commit_pending=pending);self.save(folder,state)
  if origin(self.git('remote','get-url','origin',cwd=repo))!=origin(self.expected):raise ValueError('Origin changed; publication refused')
  self.git('push','origin','HEAD:refs/heads/'+state['branch'],cwd=repo)
  state.update(status='published',published=pending);state.pop('commit_pending',None);self.save(folder,state)
  return dict(status='published',session=state['id'],**state['published'])

def main():
 p=argparse.ArgumentParser();p.add_argument('--home',required=True);p.add_argument('--source',required=True);p.add_argument('--origin',required=True);p.add_argument('--request',required=True);p.add_argument('--owner',required=True);a=p.parse_args()
 try:
  result=Service(a.home,a.source,a.origin,a.owner).request(json.loads(Path(a.request).read_text(encoding='utf-8-sig')));print(json.dumps(result,ensure_ascii=True))
 except Exception as e:print(json.dumps({'error':str(e)},ensure_ascii=True));raise SystemExit(1)
if __name__=='__main__':main()
