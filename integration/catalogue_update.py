"""Preview/apply a factory XLSX snapshot with local archives and an explicit delta."""
import argparse,collections,datetime,hashlib,json,os,shutil,sys,uuid
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
 return {'new_codes':sorted(b.keys()-a.keys()),'absent_codes':sorted(a.keys()-b.keys()),'changed_codes':[{'code':k,'before':a[k],'after':b[k]} for k in sorted(a.keys()&b.keys()) if a[k]!=b[k]]}
def update(source,factory,target,reader,apply=False,allow_absent=False):
 if factory not in {'soest','alphen','veenoord','lelystad'}:raise ValueError('Factory must be explicit')
 source,target=Path(source).resolve(),Path(target).resolve()
 if target.name!='catalogus_'+factory+'.json':raise ValueError('Target factory mismatch')
 before_hash=sha(source);new=reader.lees_catalogus(source,factory)
 if before_hash!=sha(source):raise ValueError('Source changed during read')
 cat=reader.Catalogus(new)
 if new['excluded_rows'] or cat.conflicts:raise ValueError('Excluded rows or conflicting import codes require review')
 old=json.loads(target.read_text(encoding='utf-8-sig')) if target.exists() else {'factory':factory,'records':[]}
 if old.get('factory')!=factory:raise ValueError('Existing snapshot factory mismatch')
 changes=delta(old,new);result={'factory':factory,'source':str(source),'source_sha256':before_hash,'records':len(new['records']),'import_records':sum(r['category']=='import' for r in new['records']),'new_codes':len(changes['new_codes']),'changed_codes':len(changes['changed_codes']),'absent_codes':len(changes['absent_codes']),'applied':False}
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
 stamp=datetime.datetime.now(datetime.timezone.utc).isoformat();new['verified_at']=stamp
 # Save comparison before replacing the active snapshot; original XLSX stays intact.
 atomic(archive/(before_hash+'-delta.json'),dict(result,changes=changes,verified_at=stamp))
 atomic(target,new);result['applied']=True
 registry=folder/'catalogues_registry.json';state=json.loads(registry.read_text(encoding='utf-8')) if registry.exists() else {}
 state[factory]=dict(result,verified_at=stamp,snapshot=str(target),source_archive=str(copy));atomic(registry,state)
 return result,changes

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',required=True);p.add_argument('--factory',required=True);p.add_argument('--target',required=True);p.add_argument('--engine',default=str(Path(__file__).resolve().parent));p.add_argument('--apply',action='store_true');p.add_argument('--accept-absent',action='store_true');a=p.parse_args()
 sys.path.insert(0,str(Path(a.engine).resolve()));import mbs_artikelcatalogus
 result,_=update(a.source,a.factory,a.target,mbs_artikelcatalogus,a.apply,a.accept_absent);print(json.dumps(result,indent=2))
if __name__=='__main__':main()
