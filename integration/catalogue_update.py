"""Preview/apply a factory XLSX snapshot with local archives and an explicit delta."""
import argparse,collections,contextlib,datetime,hashlib,json,os,shutil,sys,uuid
from pathlib import Path

def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def atomic(p,data):
 p=Path(p);p.parent.mkdir(parents=True,exist_ok=True);temp=p.with_name(p.name+'.'+uuid.uuid4().hex+'.tmp')
 try:temp.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');os.replace(temp,p)
 finally:
  if temp.exists():temp.unlink()
def delta(old,new):
 def index(data):
  out=collections.defaultdict(list)
  for r in data.get('records',[]):out[r['code']].append({'description':r['description'],'category':r['category'],'fields':r['fields']})
  return {k:sorted(v,key=lambda row:json.dumps(row,sort_keys=True,ensure_ascii=False)) for k,v in out.items()}
 a,b=index(old),index(new)
 return {'new_codes':sorted(b.keys()-a.keys()),'absent_codes':sorted(a.keys()-b.keys()),'new_articles':[{'code':k,'entries':b[k]} for k in sorted(b.keys()-a.keys())],'absent_articles':[{'code':k,'entries':a[k]} for k in sorted(a.keys()-b.keys())],'changed_codes':[{'code':k,'before':a[k],'after':b[k]} for k in sorted(a.keys()&b.keys()) if a[k]!=b[k]]}
def _update(source,factory,target,reader,apply=False,allow_absent=False,expected_source=None,expected_target=None):
 if factory not in {'soest','alphen','veenoord','lelystad'}:raise ValueError('Factory must be explicit')
 source,target=Path(source).resolve(),Path(target).resolve()
 if target.name!='catalogus_'+factory+'.json':raise ValueError('Target factory mismatch')
 if source.suffix.lower()!='.xlsx' or not source.is_file() or source.stat().st_size>100*1024*1024:raise ValueError('Select a valid XLSX catalogue under 100 MiB')
 if expected_source is not None and sha(source)!=expected_source:raise ValueError('Source changed since preview; compare again')
 actual_target=sha(target) if target.exists() else 'missing'
 if expected_target is not None and actual_target!=expected_target:raise ValueError('Active catalogue changed since preview; compare again')
 before_hash=sha(source);new=reader.lees_catalogus(source,factory)
 if before_hash!=sha(source):raise ValueError('Source changed during read')
 cat=reader.Catalogus(new)
 if not cat.by_code:raise ValueError('Catalogue has no MBS import articles')
 if new['excluded_rows'] or cat.conflicts:raise ValueError('Excluded rows or conflicting import codes require review')
 old=json.loads(target.read_text(encoding='utf-8-sig')) if target.exists() else {'factory':factory,'records':[]}
 if old.get('factory')!=factory:raise ValueError('Existing snapshot factory mismatch')
 old_bytes=target.read_bytes() if target.exists() else None
 changes=delta(old,new);result={'factory':factory,'source':str(source),'source_sha256':before_hash,'records':len(new['records']),'import_records':sum(r['category']=='import' for r in new['records']),'new_codes':len(changes['new_codes']),'changed_codes':len(changes['changed_codes']),'absent_codes':len(changes['absent_codes']),'applied':False,'target_sha256':actual_target,'previous_verified_at':old.get('verified_at'),'import_records_without_units':sum(r['category']=='import' and not (r['fields'].get('eenheid') or r['fields'].get('verkoopeenheid')) for r in new['records'])}
 if not apply:return result,changes
 if changes['absent_codes'] and not allow_absent:raise ValueError('Absent codes require review; confirm the complete source before accepting removals')
 folder=target.parent;folder.mkdir(parents=True,exist_ok=True);archive=folder/'catalogue_sources'/factory;archive.mkdir(parents=True,exist_ok=True)
 copy=archive/(before_hash+'.xlsx')
 if not copy.exists():shutil.copy2(source,copy)
 if sha(copy)!=before_hash:raise ValueError('Source archive mismatch')
 if target.exists():
  old_copy=archive/(sha(target)+'.json')
  if not old_copy.exists():shutil.copy2(target,old_copy)
  if sha(old_copy)!=sha(target):raise ValueError('Previous snapshot archive mismatch')
 if before_hash!=sha(source):raise ValueError('Source changed before update')
 if (sha(target) if target.exists() else 'missing')!=actual_target:raise ValueError('Active catalogue changed since preview; compare again')
 registry=folder/'catalogues_registry.json';registry_bytes=registry.read_bytes() if registry.exists() else None
 state=json.loads(registry_bytes.decode('utf-8-sig')) if registry_bytes else {}
 stamp=datetime.datetime.now(datetime.timezone.utc).isoformat();new['verified_at']=stamp
 # Save comparison before replacing the active snapshot; original XLSX stays intact.
 atomic(archive/(before_hash+'-delta.json'),dict(result,changes=changes,verified_at=stamp))
 result.update(applied=True,verified_at=stamp,snapshot=str(target),source_archive=str(copy))
 state[factory]=dict(result)
 try:
  atomic(target,new);atomic(registry,state)
 except Exception:
  # Lock is held: restore exact old bytes if registry activation fails.
  for path,previous in [(target,old_bytes),(registry,registry_bytes)]:
   if previous is None:path.unlink(missing_ok=True)
   else:
    recovery=path.with_name(path.name+'.rollback-'+uuid.uuid4().hex)
    try:recovery.write_bytes(previous);os.replace(recovery,path)
    finally:recovery.unlink(missing_ok=True)
  raise
 return result,changes

@contextlib.contextmanager
def update_lock(folder):
 folder=Path(folder);folder.mkdir(parents=True,exist_ok=True)
 with (folder/'.catalogue-update.lock').open('a+b') as handle:
  handle.seek(0,2)
  if handle.tell()==0:handle.write(b'0');handle.flush()
  handle.seek(0)
  try:
   if os.name=='nt':
    import msvcrt;msvcrt.locking(handle.fileno(),msvcrt.LK_NBLCK,1)
   else:
    import fcntl;fcntl.flock(handle.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
  except OSError:raise ValueError('Another catalogue import is running; retry after it finishes')
  try:yield
  finally:
   handle.seek(0)
   if os.name=='nt':msvcrt.locking(handle.fileno(),msvcrt.LK_UNLCK,1)
   else:fcntl.flock(handle.fileno(),fcntl.LOCK_UN)

def update(source,factory,target,reader,apply=False,allow_absent=False,expected_source=None,expected_target=None):
 if apply:
  with update_lock(Path(target).resolve().parent):return _update(source,factory,target,reader,True,allow_absent,expected_source,expected_target)
 return _update(source,factory,target,reader,False,allow_absent,expected_source,expected_target)

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--source',required=True);p.add_argument('--factory',required=True);p.add_argument('--target',required=True)
 p.add_argument('--engine',default=str(Path(__file__).resolve().parent));p.add_argument('--apply',action='store_true');p.add_argument('--accept-absent',action='store_true')
 p.add_argument('--expected-source-sha256');p.add_argument('--expected-target-sha256');p.add_argument('--details',action='store_true');a=p.parse_args()
 try:
  sys.path.insert(0,str(Path(a.engine).resolve()));import mbs_artikelcatalogus
  result,changes=update(a.source,a.factory,a.target,mbs_artikelcatalogus,a.apply,a.accept_absent,a.expected_source_sha256,a.expected_target_sha256)
  if a.details:result['changes']=changes
  print(json.dumps(result,ensure_ascii=True))
 except Exception as error:
  print(json.dumps({'error':str(error),'applied':False},ensure_ascii=True));sys.exit(1)

if __name__=='__main__':main()
